package com.t.tcine.domain.tv.service;

import com.t.tcine.domain.movie.dto.IndexStatus;
import com.t.tcine.infra.tmdb.TmdbClient;
import com.t.tcine.infra.tmdb.TmdbClient.TmdbTv;
import com.t.tcine.infra.tmdb.TmdbClient.TvDetail;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * TMDB TV 시리즈(드라마·예능·애니) 데이터를 가져와 임베딩하고 Qdrant 별도 컬렉션(tcine-tv-openai)에 쌓는다.
 * - 시리즈 한 편 = 문서 하나. ID가 TMDB tvId 기반 고정 UUID라 재실행 시 덮어쓰기(upsert) 및 사전 중복 건너뛰기가 동작한다.
 * - 제작/연출(creator), 출연진(cast), 방송사/OTT(networks), 시즌/회차 수, 장르, 키워드, 줄거리를 모두 임베딩 본문과 메타데이터에 담는다.
 */
@Service
public class TvIndexService {

    private static final Logger log = LoggerFactory.getLogger(TvIndexService.class);

    /** 수동 전체 색인: 한국 드라마/예능, 한국 최신작, 평점순, 인기, 애니메이션, 방영 중, 주간 트렌딩 */
    private static final List<String> CATEGORIES = List.of(
            "korean", "korean_now", "top_rated", "popular", "animation", "on_the_air", "trending_week");
    private static final int MAX_PAGES = 100;
    /** 자동(증분) 색인: 신작이 들어오는 목록의 앞쪽만 본다. 이미 있는 시리즈는 건너뛴다 */
    private static final List<String> AUTO_CATEGORIES = List.of("korean_now", "on_the_air", "popular", "trending_week");
    private static final int AUTO_PAGES = 3;
    private static final int BATCH_SIZE = 20;
    private static final int DETAIL_THREADS = 4;
    private static final long BATCH_PAUSE_MILLIS = 1500;
    private static final int MAX_BATCH_ATTEMPTS = 3;
    private static final int MAX_OVERVIEW_IN_PAYLOAD = 600;

    private final TmdbClient tmdb;
    private final VectorStore tvVectorStore;
    private final QdrantClient qdrant;
    private final EmbeddingModel embeddingModel;
    private final String collection;
    private final boolean embeddingConfigured;

    private volatile IndexStatus status = IndexStatus.idle();

    public TvIndexService(TmdbClient tmdb, QdrantClient qdrant, EmbeddingModel embeddingModel,
                          @Value("${tv.collection-name:tcine-tv-openai}") String collection,
                          @Value("${spring.ai.openai.api-key:}") String openAiKey) {
        this.embeddingConfigured = openAiKey != null && !openAiKey.isBlank() && !"not-configured".equals(openAiKey);
        this.tmdb = tmdb;
        this.qdrant = qdrant;
        this.embeddingModel = embeddingModel;
        this.collection = collection;
        this.tvVectorStore = QdrantVectorStore.builder(qdrant, embeddingModel)
                .collectionName(collection)
                .initializeSchema(false)
                .build();
    }

    public VectorStore vectorStore() {
        return tvVectorStore;
    }

    public String collectionName() {
        return collection;
    }

    public IndexStatus status() {
        return status;
    }

    /** 색인된 TV 시리즈 수 (Qdrant에 연결할 수 없으면 -1) */
    public long count() {
        try {
            if (!qdrant.collectionExistsAsync(collection).get()) {
                return 0;
            }
            return qdrant.countAsync(collection).get();
        } catch (ExecutionException | RuntimeException e) {
            log.warn("Qdrant TV 시리즈 수 조회 실패: {}", e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    public boolean start(int pages) {
        return startWith(CATEGORIES, pages);
    }

    /** 새로 들어온 시리즈만 채우는 자동 색인 (스케줄러가 매일 호출). 이미 실행 중이면 false */
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
        status = new IndexStatus(true, 0, "시리즈 색인을 시작합니다…");
        Thread thread = new Thread(() -> run(categories, safePages), "tv-index");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void run(List<String> categories, int pages) {
        int done = 0;
        ExecutorService detailPool = null;
        try {
            ensureCollection();
            Map<Integer, String> genres = tmdb.tvGenres();
            Set<Integer> seen = new HashSet<>();
            List<Document> batch = new ArrayList<>();
            detailPool = Executors.newFixedThreadPool(DETAIL_THREADS);
            for (String category : categories) {
                for (int page = 1; page <= pages; page++) {
                    status = new IndexStatus(true, done, category + " " + page + "쪽 가져오는 중…");
                    List<TmdbTv> shows = tmdb.listTv(category, page);
                    if (shows.isEmpty()) {
                        break;
                    }
                    List<TmdbTv> fresh = new ArrayList<>();
                    for (TmdbTv show : shows) {
                        if (show.overview() != null && !show.overview().isBlank() && seen.add(show.id())) {
                            fresh.add(show);
                        }
                    }
                    List<TmdbTv> todo = withoutIndexed(fresh);
                    status = new IndexStatus(true, done, category + " " + page + "쪽 상세 정보(제작·출연·OTT) 가져오는 중…");
                    Map<Integer, Future<Optional<TvDetail>>> details = new HashMap<>();
                    for (TmdbTv show : todo) {
                        details.put(show.id(), detailPool.submit(() -> tmdb.detailTv(show.id())));
                    }
                    for (TmdbTv show : todo) {
                        batch.add(toDocument(show, genres, detailOf(details.get(show.id()))));
                        if (batch.size() >= BATCH_SIZE) {
                            done += flush(batch, done, category);
                        }
                    }
                }
            }
            done += flush(batch, done, "마무리");
            status = new IndexStatus(false, done, "완료: " + done + "편의 시리즈를 색인했어요.");
            log.info("TV 시리즈 색인 완료: {}편", done);
        } catch (Exception e) {
            log.warn("TV 시리즈 색인 실패: {}", e.getMessage());
            status = new IndexStatus(false, done, "실패: " + e.getMessage() + " (" + done + "편까지 색인됨)");
        } finally {
            if (detailPool != null) {
                detailPool.shutdown();
            }
        }
    }

    private List<TmdbTv> withoutIndexed(List<TmdbTv> shows) {
        if (shows.isEmpty()) {
            return shows;
        }
        try {
            List<PointId> ids = shows.stream().map(s -> PointIdFactory.id(UUID.fromString(docId(s.id())))).toList();
            Set<String> existing = new HashSet<>();
            for (RetrievedPoint point : qdrant.retrieveAsync(collection, ids, false, false, null).get()) {
                existing.add(point.getId().getUuid());
            }
            return shows.stream().filter(s -> !existing.contains(docId(s.id()))).toList();
        } catch (ExecutionException | RuntimeException e) {
            log.warn("이미 색인된 시리즈 조회 실패(전부 새로 넣습니다): {}", e.getMessage());
            return shows;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return shows;
        }
    }

    private static String docId(int tmdbTvId) {
        return UUID.nameUUIDFromBytes(("tmdb-tv-" + tmdbTvId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static TvDetail detailOf(Future<Optional<TvDetail>> future) {
        try {
            return future == null ? null : future.get().orElse(null);
        } catch (ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private int flush(List<Document> batch, int doneSoFar, String label) {
        if (batch.isEmpty()) {
            return 0;
        }
        int size = batch.size();
        for (int attempt = 1; attempt <= MAX_BATCH_ATTEMPTS; attempt++) {
            try {
                status = new IndexStatus(true, doneSoFar, label + " 임베딩 중… (" + (doneSoFar + size) + "편째)");
                tvVectorStore.add(new ArrayList<>(batch));
                batch.clear();
                pause(BATCH_PAUSE_MILLIS);
                return size;
            } catch (RuntimeException e) {
                if (String.valueOf(e.getMessage()).contains("insufficient_quota")) {
                    throw new IllegalStateException("OpenAI 크레딧이 부족해요. 잔액을 확인해 주세요.", e);
                }
                log.warn("시리즈 묶음 색인 실패({}/{}): {}", attempt, MAX_BATCH_ATTEMPTS, e.getMessage());
                pause(5000L * attempt);
            }
        }
        batch.clear();
        return 0;
    }

    private Document toDocument(TmdbTv show, Map<Integer, String> genreNames, TvDetail detail) {
        String genres = show.genreIds() == null ? "" : show.genreIds().stream()
                .map(id -> genreNames.getOrDefault(id, ""))
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + ", " + b).orElse("");
        int year = yearOf(show.firstAirDate());
        double rating = show.voteAverage() == null ? 0 : show.voteAverage();
        String title = show.name() == null ? "" : show.name();
        String original = show.originalName() == null ? "" : show.originalName();

        String creator = detail == null || detail.creator() == null ? "" : detail.creator();
        String cast = detail == null ? "" : String.join(", ", detail.cast());
        String networks = detail == null || detail.networks() == null ? "" : detail.networks();
        String keywords = detail == null ? "" : String.join(", ", detail.keywords());
        String tagline = detail == null || detail.tagline() == null ? "" : detail.tagline();
        int seasons = detail == null || detail.seasons() == null ? 0 : detail.seasons();
        int episodes = detail == null || detail.episodes() == null ? 0 : detail.episodes();

        StringBuilder sb = new StringBuilder();
        sb.append("제목: ").append(title)
                .append(original.isBlank() || original.equals(title) ? "" : " (" + original + ")").append("\n");
        sb.append("방영: ").append(year > 0 ? String.valueOf(year) : "알 수 없음").append("\n");
        if (!networks.isBlank()) {
            sb.append("방송/OTT: ").append(networks).append("\n");
        }
        if (!creator.isBlank()) {
            sb.append("제작/연출: ").append(creator).append("\n");
        }
        if (!cast.isBlank()) {
            sb.append("출연: ").append(cast).append("\n");
        }
        sb.append("장르: ").append(genres.isBlank() ? "알 수 없음" : genres).append("\n");
        if (seasons > 0 || episodes > 0) {
            sb.append("분량: 시즌 ").append(seasons).append("개, 총 ").append(episodes).append("부작\n");
        }
        if (!keywords.isBlank()) {
            sb.append("키워드: ").append(keywords).append("\n");
        }
        if (!tagline.isBlank()) {
            sb.append("한줄 소개: ").append(tagline).append("\n");
        }
        sb.append("줄거리: ").append(show.overview());
        String text = sb.toString();

        Map<String, Object> meta = new HashMap<>();
        meta.put("tmdbId", show.id());
        meta.put("title", title);
        meta.put("originalTitle", original);
        meta.put("year", year);
        meta.put("genres", genres);
        meta.put("rating", rating);
        meta.put("poster", show.posterPath() == null ? "" : show.posterPath());
        meta.put("creator", creator);
        meta.put("cast", cast);
        meta.put("networks", networks);
        meta.put("seasons", seasons);
        meta.put("episodes", episodes);
        String overview = show.overview();
        meta.put("overview", overview.length() > MAX_OVERVIEW_IN_PAYLOAD
                ? overview.substring(0, MAX_OVERVIEW_IN_PAYLOAD) + "…" : overview);

        return new Document(docId(show.id()), text, meta);
    }

    private void ensureCollection() throws ExecutionException, InterruptedException {
        if (qdrant.collectionExistsAsync(collection).get()) {
            return;
        }
        int dimensions = embeddingModel.embed("차원 확인").length;
        qdrant.createCollectionAsync(collection, VectorParams.newBuilder()
                .setSize(dimensions)
                .setDistance(Distance.Cosine)
                .build()).get();
        log.info("Qdrant TV 컬렉션 생성: {} ({}차원)", collection, dimensions);
    }

    private static int yearOf(String firstAirDate) {
        if (firstAirDate == null || firstAirDate.length() < 4) {
            return 0;
        }
        try {
            return Integer.parseInt(firstAirDate.substring(0, 4));
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
