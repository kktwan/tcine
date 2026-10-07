package com.t.tcine.domain.movie.service;

import com.t.tcine.domain.movie.dto.IndexStatus;
import com.t.tcine.infra.tmdb.TmdbClient;
import com.t.tcine.infra.tmdb.TmdbClient.MovieDetail;
import com.t.tcine.infra.tmdb.TmdbClient.TmdbMovie;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * TMDB 영화 데이터를 가져와 임베딩하고 Qdrant에 쌓는다 (관리자가 화면에서 시작, 백그라운드 스레드로 실행).
 * - 영화 한 편 = 문서 하나. ID가 TMDB id 기반으로 고정이라 다시 실행해도 덮어쓰기(upsert)라 중복이 생기지 않는다.
 * - 임베딩 무료 한도 때문에 작은 묶음으로 나눠 넣고, 묶음 사이에 쉰다. 실패한 묶음은 몇 번 재시도하고 건너뛴다.
 * - Qdrant 컬렉션은 앱 시작 때가 아니라 여기서 만든다 (Qdrant가 꺼져 있어도 앱이 뜨게 하려고).
 */
@Service
public class MovieIndexService {

    private static final Logger log = LoggerFactory.getLogger(MovieIndexService.class);

    /** 수동 전체 색인: 평점순·인기·한국·한국 상영/개봉 예정·일간/주간 트렌딩 목록 */
    private static final List<String> CATEGORIES = List.of(
            "top_rated", "popular", "korean", "now_playing", "upcoming", "trending_week", "trending_day");
    /** 종류별로 가져올 최대 쪽 수 (쪽당 20편). TMDB 자체 한도는 500쪽이지만 무료 임베딩 한도와 시간을 고려해 제한한다 */
    private static final int MAX_PAGES = 200;
    /** 자동(증분) 색인: 신작이 들어오는 목록의 앞쪽만 본다. 이미 있는 영화는 건너뛴다 */
    private static final List<String> AUTO_CATEGORIES = List.of("popular", "now_playing", "korean_now", "trending_week");
    private static final int AUTO_PAGES = 3;
    private static final int BATCH_SIZE = 20;
    /** 상세 API를 동시에 부르는 개수 (TMDB는 초당 약 40건까지 허용) */
    private static final int DETAIL_THREADS = 4;
    private static final long BATCH_PAUSE_MILLIS = 1500;
    private static final int MAX_BATCH_ATTEMPTS = 3;
    private static final int MAX_OVERVIEW_IN_PAYLOAD = 600;

    private final TmdbClient tmdb;
    private final VectorStore vectorStore;
    private final QdrantClient qdrant;
    private final EmbeddingModel embeddingModel;
    private final String collection;

    private final boolean embeddingConfigured;

    private volatile IndexStatus status = IndexStatus.idle();

    public MovieIndexService(TmdbClient tmdb, VectorStore vectorStore, QdrantClient qdrant,
                             EmbeddingModel embeddingModel,
                             @Value("${spring.ai.vectorstore.qdrant.collection-name:tcine-movies-openai}") String collection,
                             @Value("${spring.ai.openai.api-key:}") String openAiKey) {
        this.embeddingConfigured = openAiKey != null && !openAiKey.isBlank() && !"not-configured".equals(openAiKey);
        this.tmdb = tmdb;
        this.vectorStore = vectorStore;
        this.qdrant = qdrant;
        this.embeddingModel = embeddingModel;
        this.collection = collection;
    }

    public IndexStatus status() {
        return status;
    }

    /** 색인된 영화 수 (Qdrant에 연결할 수 없으면 -1) */
    public long count() {
        try {
            if (!qdrant.collectionExistsAsync(collection).get()) {
                return 0;
            }
            return qdrant.countAsync(collection).get();
        } catch (ExecutionException | RuntimeException e) {
            log.warn("Qdrant 영화 수 조회 실패: {}", e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * 색인을 백그라운드에서 시작한다. 목록 종류마다 pages 쪽(쪽당 20편)씩 가져온다.
     * 이미 실행 중이면 false.
     */
    public boolean start(int pages) {
        return startWith(CATEGORIES, pages);
    }

    /** 새로 들어온 영화만 채우는 자동 색인 (스케줄러가 매일 호출). 이미 실행 중이면 false */
    public boolean startAuto() {
        return startWith(AUTO_CATEGORIES, AUTO_PAGES);
    }

    private synchronized boolean startWith(List<String> categories, int pages) {
        if (status.running()) {
            return false;
        }
        if (!tmdb.isEnabled()) {
            status = new IndexStatus(false, 0, "TMDB_API_KEY가 설정되지 않았어요.");
            return true;
        }
        if (!embeddingConfigured) {
            status = new IndexStatus(false, 0, "OPENAI_API_KEY가 설정되지 않았어요. (임베딩에 필요해요)");
            return true;
        }
        int safePages = Math.max(1, Math.min(pages, MAX_PAGES));
        status = new IndexStatus(true, 0, "시작합니다…");
        Thread thread = new Thread(() -> run(categories, safePages), "movie-index");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void run(List<String> categories, int pages) {
        int done = 0;
        ExecutorService detailPool = null;
        try {
            ensureCollection();
            Map<Integer, String> genres = tmdb.genres();
            Set<Integer> seen = new HashSet<>();
            List<Document> batch = new ArrayList<>();
            detailPool = Executors.newFixedThreadPool(DETAIL_THREADS);
            for (String category : categories) {
                for (int page = 1; page <= pages; page++) {
                    status = new IndexStatus(true, done, category + " " + page + "쪽 가져오는 중…");
                    List<TmdbMovie> movies = tmdb.list(category, page);
                    if (movies.isEmpty()) {
                        break;
                    }
                    // 줄거리가 없으면 의미 검색에 쓸 수 없다. 이미 담은 영화도 건너뜀
                    List<TmdbMovie> fresh = new ArrayList<>();
                    for (TmdbMovie movie : movies) {
                        if (movie.overview() != null && !movie.overview().isBlank() && seen.add(movie.id())) {
                            fresh.add(movie);
                        }
                    }
                    // 이미 색인된 영화는 건너뛴다: 다시 임베딩하면 무료 한도(하루 1,000건)만 낭비한다
                    List<TmdbMovie> todo = withoutIndexed(fresh);
                    // 감독/출연/키워드는 영화마다 상세 API를 불러야 해서, 몇 개씩 동시에 받는다
                    status = new IndexStatus(true, done, category + " " + page + "쪽 상세 정보(감독·출연) 가져오는 중…");
                    Map<Integer, Future<Optional<MovieDetail>>> details = new HashMap<>();
                    for (TmdbMovie movie : todo) {
                        details.put(movie.id(), detailPool.submit(() -> tmdb.detail(movie.id())));
                    }
                    for (TmdbMovie movie : todo) {
                        batch.add(toDocument(movie, genres, detailOf(details.get(movie.id()))));
                        if (batch.size() >= BATCH_SIZE) {
                            done += flush(batch, done, category);
                        }
                    }
                }
            }
            done += flush(batch, done, "마무리");
            status = new IndexStatus(false, done, "완료: " + done + "편을 색인했어요.");
            log.info("영화 색인 완료: {}편", done);
        } catch (Exception e) {
            log.warn("영화 색인 실패: {}", e.getMessage());
            status = new IndexStatus(false, done, "실패: " + e.getMessage() + " (" + done + "편까지 색인됨)");
        } finally {
            if (detailPool != null) {
                detailPool.shutdown();
            }
        }
    }

    /** Qdrant에 이미 있는 영화를 뺀 목록 (조회에 실패하면 전부 새로 넣는다) */
    private List<TmdbMovie> withoutIndexed(List<TmdbMovie> movies) {
        if (movies.isEmpty()) {
            return movies;
        }
        try {
            List<PointId> ids = movies.stream().map(m -> PointIdFactory.id(UUID.fromString(docId(m.id())))).toList();
            Set<String> existing = new HashSet<>();
            for (RetrievedPoint point : qdrant.retrieveAsync(collection, ids, false, false, null).get()) {
                existing.add(point.getId().getUuid());
            }
            return movies.stream().filter(m -> !existing.contains(docId(m.id()))).toList();
        } catch (ExecutionException | RuntimeException e) {
            log.warn("이미 색인된 영화 조회 실패(전부 새로 넣습니다): {}", e.getMessage());
            return movies;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return movies;
        }
    }

    /** 같은 영화는 항상 같은 ID -> 다시 색인해도 덮어쓰기, 이미 있는지 확인도 가능 */
    private static String docId(int tmdbId) {
        return UUID.nameUUIDFromBytes(("tmdb-" + tmdbId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 상세 호출 결과를 꺼낸다. 실패하거나 없으면 null (목록의 기본 정보만으로 색인) */
    private static MovieDetail detailOf(Future<Optional<MovieDetail>> future) {
        try {
            return future == null ? null : future.get().orElse(null);
        } catch (ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 모아 둔 영화를 임베딩해서 넣는다. 넣은 편수를 돌려주고 batch 를 비운다. 실패하면 재시도 후 건너뛴다 */
    private int flush(List<Document> batch, int doneSoFar, String label) {
        if (batch.isEmpty()) {
            return 0;
        }
        int size = batch.size();
        for (int attempt = 1; attempt <= MAX_BATCH_ATTEMPTS; attempt++) {
            try {
                status = new IndexStatus(true, doneSoFar, label + " 임베딩 중… (" + (doneSoFar + size) + "편째)");
                vectorStore.add(new ArrayList<>(batch));
                batch.clear();
                pause(BATCH_PAUSE_MILLIS);
                return size;
            } catch (RuntimeException e) {
                if (String.valueOf(e.getMessage()).contains("insufficient_quota")) {
                    throw new IllegalStateException("OpenAI 크레딧이 부족해요. platform.openai.com 에서 잔액을 확인해 주세요. "
                            + "충전 후 색인을 다시 시작하면 이미 쌓은 영화는 건너뛰고 이어서 쌓아요.", e);
                }
                if (String.valueOf(e.getMessage()).contains("PerDay")) {
                    throw new IllegalStateException("임베딩 일일 한도를 다 썼어요. "
                            + "내일 다시 색인을 시작하면 이미 쌓은 영화는 건너뛰고 이어서 쌓아요.", e);
                }
                if (String.valueOf(e.getMessage()).contains("dimension")) {
                    throw new IllegalStateException("컬렉션의 벡터 차원이 임베딩과 달라요. Qdrant에서 " + collection
                            + " 컬렉션을 삭제한 뒤 다시 색인해 주세요.", e);
                }
                log.warn("영화 묶음 색인 실패({}/{}): {}", attempt, MAX_BATCH_ATTEMPTS, e.getMessage());
                pause(5000L * attempt); // 무료 한도에 걸린 경우를 위해 점점 길게 쉰다
            }
        }
        batch.clear(); // 계속 실패하면 이 묶음은 건너뛴다
        return 0;
    }

    private Document toDocument(TmdbMovie movie, Map<Integer, String> genreNames, MovieDetail detail) {
        String genres = movie.genreIds() == null ? "" : movie.genreIds().stream()
                .map(id -> genreNames.getOrDefault(id, ""))
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + ", " + b).orElse("");
        int year = yearOf(movie.releaseDate());
        double rating = movie.voteAverage() == null ? 0 : movie.voteAverage();
        String title = movie.title() == null ? "" : movie.title();
        String original = movie.originalTitle() == null ? "" : movie.originalTitle();

        String director = detail == null || detail.director() == null ? "" : detail.director();
        String cast = detail == null ? "" : String.join(", ", detail.cast());
        String keywords = detail == null ? "" : String.join(", ", detail.keywords());
        String tagline = detail == null || detail.tagline() == null ? "" : detail.tagline();
        int runtime = detail == null || detail.runtime() == null ? 0 : detail.runtime();

        // 임베딩될 본문: 제목, 감독/출연, 장르, 키워드, 줄거리가 의미 검색의 재료가 된다
        StringBuilder sb = new StringBuilder();
        sb.append("제목: ").append(title)
                .append(original.isBlank() || original.equals(title) ? "" : " (" + original + ")").append("\n");
        sb.append("개봉: ").append(year > 0 ? String.valueOf(year) : "알 수 없음").append("\n");
        if (!director.isBlank()) {
            sb.append("감독: ").append(director).append("\n");
        }
        if (!cast.isBlank()) {
            sb.append("출연: ").append(cast).append("\n");
        }
        sb.append("장르: ").append(genres.isBlank() ? "알 수 없음" : genres).append("\n");
        if (!keywords.isBlank()) {
            sb.append("키워드: ").append(keywords).append("\n");
        }
        if (runtime > 0) {
            sb.append("상영시간: ").append(runtime).append("분\n");
        }
        if (!tagline.isBlank()) {
            sb.append("한줄 소개: ").append(tagline).append("\n");
        }
        sb.append("줄거리: ").append(movie.overview());
        String text = sb.toString();

        Map<String, Object> meta = new HashMap<>();
        meta.put("tmdbId", movie.id());
        meta.put("title", title);
        meta.put("originalTitle", original);
        meta.put("year", year);
        meta.put("genres", genres);
        meta.put("rating", rating);
        meta.put("poster", movie.posterPath() == null ? "" : movie.posterPath());
        meta.put("director", director);
        meta.put("cast", cast);
        meta.put("runtime", runtime);
        String overview = movie.overview();
        meta.put("overview", overview.length() > MAX_OVERVIEW_IN_PAYLOAD
                ? overview.substring(0, MAX_OVERVIEW_IN_PAYLOAD) + "…" : overview);

        return new Document(docId(movie.id()), text, meta);
    }

    /** 컬렉션이 없으면 임베딩 차원에 맞춰 만든다 (코사인 거리) */
    private void ensureCollection() throws ExecutionException, InterruptedException {
        if (qdrant.collectionExistsAsync(collection).get()) {
            return;
        }
        // 설정한 차원(768)이 실제로 적용된 길이를 재기 위해 임베딩을 한 번 만들어 본다.
        // (embeddingModel.dimensions() 는 설정이 아니라 모델 기본값 3072 를 돌려줘서 쓰지 않는다)
        int dimensions = embeddingModel.embed("차원 확인").length;
        qdrant.createCollectionAsync(collection, VectorParams.newBuilder()
                .setSize(dimensions)
                .setDistance(Distance.Cosine)
                .build()).get();
        log.info("Qdrant 컬렉션 생성: {} ({}차원)", collection, dimensions);
    }

    private static int yearOf(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(releaseDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
