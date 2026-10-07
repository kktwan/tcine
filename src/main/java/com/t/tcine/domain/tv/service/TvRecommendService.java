package com.t.tcine.domain.tv.service;

import com.t.tcine.domain.movie.dto.ModelAnswer;
import com.t.tcine.domain.movie.dto.ModelAnswer.ModelPick;
import com.t.tcine.domain.tv.dto.TvResult;
import com.t.tcine.domain.tv.dto.TvResult.TvCard;
import com.t.tcine.infra.tmdb.TmdbClient.TvFull;
import com.t.tcine.domain.search.util.QueryAnalyzer;
import com.t.tcine.domain.search.util.RankingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class TvRecommendService {
    private static final Logger log = LoggerFactory.getLogger(TvRecommendService.class);

    private static final int CANDIDATES = 20;
    private static final int FETCH = 60;
    private static final int MAX_CARDS = 18;
    private static final long TIMEOUT_SECONDS = 25;
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int CACHE_MAX_ENTRIES = 300;
    private static final String POSTER_BASE = "https://image.tmdb.org/t/p/w342";

    private final VectorStore tvVectorStore;
    private final TvCorpusManager corpusManager;
    private final TvIndexService indexService;
    private final TvDetailService detailService;
    private final ChatClient chatClient;
    private final boolean aiEnabled;
    private final boolean embeddingConfigured;
    private final int dailyLimit;

    @Value("classpath:prompts/tv-curator.st")
    private Resource promptResource;

    private final ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
        Thread thread = new Thread(r, "tv-recommend");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, CachedResult> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedResult> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    private final Map<String, Integer> usage = new HashMap<>();
    private LocalDate usageDay = LocalDate.now();

    public TvRecommendService(TvCorpusManager corpusManager, TvIndexService indexService,
                              TvDetailService detailService, ChatClient.Builder builder,
                              @Value("${ai.api-key:}") String apiKey,
                              @Value("${spring.ai.openai.api-key:}") String openAiKey,
                              @Value("${movie.daily-limit:30}") int dailyLimit) {
        this.corpusManager = corpusManager;
        this.indexService = indexService;
        this.tvVectorStore = indexService.vectorStore();
        this.detailService = detailService;
        this.chatClient = builder.build();
        this.aiEnabled = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
        this.embeddingConfigured = openAiKey != null && !openAiKey.isBlank() && !"not-configured".equals(openAiKey);
        this.dailyLimit = dailyLimit;
    }

    public boolean isEnabled() {
        return aiEnabled;
    }

    public TvResult searchFast(String query) {
        String q = QueryAnalyzer.normalize(query, 150);
        if (q.isEmpty()) return TvResult.empty(null);
        try {
            List<Document> matches = findKeywordMatches(q);
            List<TvCard> cards = matches.stream()
                    .limit(MAX_CARDS)
                    .map(document -> card(intOf(document.getMetadata().get("tmdbId")), document, null))
                    .sorted(BY_YEAR_DESC)
                    .toList();
            return new TvResult(null, cards, false, cards.isEmpty() ? "일치하는 시리즈가 없어요. 제목·배우·제작진·OTT를 확인해 주세요." : null);
        } catch (Exception e) {
            log.warn("TV 키워드 검색 실패: {}", e.getMessage());
            long indexed = indexService.count();
            return TvResult.empty(indexed == 0 ? "아직 색인된 시리즈 데이터가 없어요. 관리자가 시리즈 데이터를 먼저 쌓아야 해요." : "시리즈 검색을 사용할 수 없어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    private List<Document> findKeywordMatches(String q) throws Exception {
        List<Document> documents = corpusManager.getCorpus();
        return documents.stream()
                .filter(document -> RankingEngine.keywordScore(document, q, true) > 0)
                .sorted(Comparator.comparingInt((Document document) -> -RankingEngine.keywordScore(document, q, true))
                        .thenComparing(Comparator.comparingDouble((Document document) -> doubleOf(document.getMetadata().get("rating"))).reversed())
                        .thenComparingInt(document -> {
                            int year = intOf(document.getMetadata().get("year"));
                            return year > 0 ? -year : Integer.MAX_VALUE;
                        }))
                .toList();
    }

    public TvResult recommend(String username, String query) {
        String q = QueryAnalyzer.normalize(query, 150);
        if (q.isEmpty()) return TvResult.empty(null);
        String cacheKey = QueryAnalyzer.compact(q).isEmpty() ? q.toLowerCase(Locale.ROOT) : QueryAnalyzer.compact(q);
        return run(username, cacheKey, q, q, 0);
    }

    public TvResult recommendSimilar(String username, int tvId) {
        Optional<TvFull> found = detailService.get(tvId);
        if (found.isEmpty()) return TvResult.empty("시리즈 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
        TvFull t = found.get();
        StringBuilder text = new StringBuilder();
        if (t.genres() != null && !t.genres().isBlank()) text.append("장르: ").append(t.genres()).append("\n");
        if (t.keywords() != null && !t.keywords().isEmpty()) text.append("키워드: ").append(String.join(", ", t.keywords())).append("\n");
        if (t.tagline() != null && !t.tagline().isBlank()) text.append("한줄 소개: ").append(t.tagline()).append("\n");
        if (t.overview() != null && !t.overview().isBlank()) text.append("줄거리: ").append(t.overview());
        
        if (text.length() == 0) return TvResult.empty("이 작품은 비교할 정보가 부족해요.");
        String request = "\"" + t.title() + "\"와 세계관·분위기·장르·서사 결이 비슷한 다른 시리즈 (기준 작품 제외).\n[기준 작품 정보]\n"
                + shorten(text.toString(), 600);
        return run(username, "similar-tv:" + tvId, text.toString(), request, tvId);
    }

    private TvResult run(String username, String key, String searchText, String requestText, int excludeId) {
        if (!aiEnabled) return TvResult.empty("AI가 아직 설정되지 않았어요. (서버에 GEMINI_API_KEY가 필요해요)");
        if (!embeddingConfigured) return TvResult.empty("OpenAI 키가 아직 설정되지 않았어요. (시리즈 검색의 임베딩에 필요해요)");
        long now = System.currentTimeMillis();
        synchronized (cache) {
            CachedResult cached = cache.get(key);
            if (cached != null && cached.expiresAt() > now) return cached.result();
        }
        if (!tryConsume(username)) return TvResult.empty("오늘 사용 횟수를 모두 썼어요. 내일 다시 이용해 주세요.");

        long started = System.currentTimeMillis();
        List<Document> docs;
        String excludeTitleCompact = "";
        String effectiveRequestText = requestText;
        Document referenceDoc = null;
        try {
            String effectiveSearchText = searchText;
            String targetTitle = excludeId == 0 ? QueryAnalyzer.similarTargetTitle(requestText) : "";

            if (excludeId > 0) {
                referenceDoc = corpusManager.getCorpus().stream()
                        .filter(d -> intOf(d.getMetadata().get("tmdbId")) == excludeId)
                        .findFirst().orElse(null);
                if (referenceDoc != null) excludeTitleCompact = QueryAnalyzer.compact(str(referenceDoc.getMetadata().get("title")));
            } else if (!targetTitle.isEmpty()) {
                List<Document> targetMatches = findKeywordMatches(targetTitle).stream()
                        .filter(d -> RankingEngine.keywordScore(d, targetTitle, true) >= 65).toList();
                if (!targetMatches.isEmpty()) {
                    excludeTitleCompact = QueryAnalyzer.compact(targetTitle);
                    Document ref = targetMatches.get(0);
                    referenceDoc = ref;
                    Map<String, Object> rm = ref.getMetadata();
                    String mergedGenres = targetMatches.stream().limit(3)
                            .map(d -> str(d.getMetadata().get("genres"))).filter(s -> !s.isBlank())
                            .flatMap(s -> Arrays.stream(s.split(","))).map(String::trim).filter(s -> !s.isEmpty()).distinct()
                            .collect(Collectors.joining(", "));
                    String mergedKeywords = targetMatches.stream().limit(3)
                            .map(d -> str(d.getMetadata().getOrDefault("keywords", RankingEngine.extractFieldFromContent(d.getText(), "키워드:"))))
                            .filter(s -> !s.isBlank()).flatMap(s -> Arrays.stream(s.split(","))).map(String::trim).filter(s -> !s.isEmpty()).distinct()
                            .limit(20).collect(Collectors.joining(", "));
                    if (!mergedGenres.isBlank()) rm.put("genres", mergedGenres);
                    if (!mergedKeywords.isBlank()) rm.put("keywords", mergedKeywords);
                    String refTagline = str(rm.getOrDefault("tagline", RankingEngine.extractFieldFromContent(ref.getText(), "한줄 소개:")));
                    StringBuilder searchSb = new StringBuilder();
                    searchSb.append("장르: ").append(str(rm.get("genres"))).append("\n");
                    if (!mergedKeywords.isBlank()) searchSb.append("핵심 키워드: ").append(mergedKeywords).append("\n");
                    if (!refTagline.isBlank()) searchSb.append("분위기: ").append(refTagline).append("\n");
                    searchSb.append("줄거리: ").append(str(rm.get("overview")));
                    effectiveSearchText = searchSb.toString();
                    effectiveRequestText = "\"" + str(rm.get("title")) + "\"와 세계관·분위기·하위 장르·서사 구조가 비슷한 다른 시리즈 (기준 작품은 반드시 제외).\n[기준 작품 정보]\n"
                            + shorten(effectiveSearchText, 500);
                }
            }

            final String finalSearchText = effectiveSearchText;
            final boolean runKeywordSearch = (excludeId == 0 && excludeTitleCompact.isEmpty());

            CompletableFuture<List<Document>> keywordFuture = runKeywordSearch
                    ? CompletableFuture.supplyAsync(() -> {
                        try { return findKeywordMatches(searchText); } catch (Exception e) { return List.of(); }
                    }, pool) : CompletableFuture.completedFuture(List.of());

            CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(() ->
                    tvVectorStore.similaritySearch(SearchRequest.builder().query(finalSearchText).topK(FETCH).similarityThreshold(0.0).build()), pool);

            List<Document> keywordDocs = keywordFuture.join();
            List<Document> vectorDocs = vectorFuture.join();

            docs = RankingEngine.mergeAndRank(keywordDocs, vectorDocs, searchText, runKeywordSearch, FETCH, true);
        } catch (Exception e) {
            log.warn("시리즈 하이브리드 검색 실패: {}", e.getMessage());
            long indexed = indexService.count();
            return TvResult.empty(indexed == 0 ? "아직 색인된 시리즈가 없어요. 관리자가 시리즈 데이터를 먼저 쌓아야 해요." : "시리즈 데이터베이스(Qdrant)에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        long searchedAt = System.currentTimeMillis();
        if (docs.isEmpty()) return TvResult.empty("색인된 시리즈가 없어요. 관리자가 시리즈 데이터를 먼저 쌓아야 해요.");

        final Document refForRank = referenceDoc;
        final String queryForRank = requestText;
        final Set<String> requestedNetworks = refForRank == null ? QueryAnalyzer.extractRequestedNetworks(queryForRank) : Set.of();
        final Set<String> requestedGenres = refForRank == null ? QueryAnalyzer.extractRequestedGenres(queryForRank, true) : Set.of();
        List<Document> ranked = new ArrayList<>(docs);
        ranked.sort(Comparator.comparingDouble((Document d) -> -RankingEngine.boostedScore(d, queryForRank, refForRank, requestedGenres, requestedNetworks, true)));

        long strictNetworkMatches = ranked.stream().filter(d -> RankingEngine.matchesRequestedNetworks(d, requestedNetworks)).count();
        boolean enforceNetworkFilter = !requestedNetworks.isEmpty() && strictNetworkMatches >= 1;

        long strictGenreMatches = ranked.stream()
                .filter(d -> (!enforceNetworkFilter || RankingEngine.matchesRequestedNetworks(d, requestedNetworks))
                        && RankingEngine.matchesRequestedGenres(d, requestedGenres, true)
                        && !RankingEngine.hasConflictingGenre(d, queryForRank, requestedGenres, true))
                .count();
        boolean enforceGenreFilter = !requestedGenres.isEmpty() && strictGenreMatches >= 1;

        Map<Integer, Document> byId = new LinkedHashMap<>();
        for (Document doc : ranked) {
            int id = intOf(doc.getMetadata().get("tmdbId"));
            if (id <= 0 || id == excludeId || byId.size() >= CANDIDATES) continue;
            if (!excludeTitleCompact.isEmpty()) {
                String titleCompact = QueryAnalyzer.compact(str(doc.getMetadata().get("title")));
                String origCompact = QueryAnalyzer.compact(str(doc.getMetadata().get("originalTitle")));
                if (titleCompact.contains(excludeTitleCompact) || origCompact.contains(excludeTitleCompact)) continue;
            }
            if (enforceNetworkFilter && !RankingEngine.matchesRequestedNetworks(doc, requestedNetworks)) continue;
            if (refForRank == null && RankingEngine.hasConflictingGenre(doc, queryForRank, requestedGenres, true)) continue;
            if (enforceGenreFilter && !RankingEngine.matchesRequestedGenres(doc, requestedGenres, true)) continue;
            byId.putIfAbsent(id, doc);
        }

        TvResult result;
        final String finalRequestText = effectiveRequestText;
        try {
            Future<ModelAnswer> future = pool.submit(() -> askModel(finalRequestText, byId));
            ModelAnswer answer = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            result = toResult(requestText, answer, byId, excludeId == 0 && excludeTitleCompact.isEmpty());
        } catch (TimeoutException e) {
            log.warn("시리즈 추천 AI 시간 초과({}초)", TIMEOUT_SECONDS);
            result = fallback(byId, "AI 응답이 늦어서, 의미가 비슷한 시리즈를 그대로 보여드려요.");
        } catch (ExecutionException e) {
            log.warn("시리즈 추천 AI 실패: {}", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            result = fallback(byId, "AI 설명을 만들지 못해서, 의미가 비슷한 시리즈를 그대로 보여드려요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TvResult.empty("요청이 중단됐어요.");
        }

        log.info("시리즈 추천 소요: 검색(임베딩+Qdrant) {}ms, AI {}ms, 합계 {}ms",
                searchedAt - started, System.currentTimeMillis() - searchedAt, System.currentTimeMillis() - started);
        if (result.hasCards() && result.message() == null) {
            synchronized (cache) { cache.put(key, new CachedResult(result, now + CACHE_TTL_MILLIS)); }
        }
        return result;
    }

    private static final Comparator<TvCard> BY_YEAR_DESC = Comparator
            .comparingInt((TvCard c) -> c.year() != null && c.year() > 0 ? c.year() : 0).reversed()
            .thenComparing(Comparator.comparingDouble(TvCard::rating).reversed());

    private ModelAnswer askModel(String q, Map<Integer, Document> byId) {
        StringBuilder sb = new StringBuilder();
        sb.append("요청: \"").append(q).append("\"\n\n[후보 시리즈]\n");
        for (Map.Entry<Integer, Document> e : byId.entrySet()) {
            Document doc = e.getValue();
            Map<String, Object> m = doc.getMetadata();
            String keywords = str(m.getOrDefault("keywords", RankingEngine.extractFieldFromContent(doc.getText(), "키워드:")));
            sb.append(e.getKey()).append('|').append(str(m.get("title")))
                    .append('|').append(intOf(m.get("year"))).append('|').append(str(m.get("networks")))
                    .append('|').append(str(m.get("creator"))).append('|').append(shorten(str(m.get("cast")), 28))
                    .append('|').append(str(m.get("genres")));
            if (!keywords.isBlank()) sb.append('|').append(shorten(keywords, 40));
            sb.append('|').append(String.format("%.1f", doubleOf(m.get("rating"))))
                    .append('|').append(shorten(str(m.get("overview")), 75)).append('\n');
        }
        
        String systemPrompt = "";
        try {
            systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to read system prompt resource", e);
        }

        return chatClient.prompt().system(systemPrompt).user(sb.toString()).call().entity(ModelAnswer.class);
    }

    private TvResult toResult(String query, ModelAnswer answer, Map<Integer, Document> byId, boolean allowEntityCompletion) {
        String summary = answer == null || answer.summary() == null ? "" : answer.summary().trim();
        List<TvCard> cards = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        if (answer != null && answer.picks() != null) {
            for (ModelPick pick : answer.picks()) {
                if (cards.size() >= MAX_CARDS || pick == null || pick.id() == null) break;
                int id = parseId(pick.id());
                Document doc = byId.get(id);
                if (doc == null || !used.add(id)) continue;
                cards.add(card(id, doc, clip(pick.reason())));
            }
        }

        if (allowEntityCompletion && !QueryAnalyzer.extractCoreTerms(query).isEmpty()) {
            List<Map.Entry<Integer, Document>> entityMatches = byId.entrySet().stream()
                    .filter(e -> RankingEngine.keywordScore(e.getValue(), query, true) >= 65).toList();
            long pickedEntityMatches = cards.stream()
                    .filter(c -> byId.containsKey(c.id()) && RankingEngine.keywordScore(byId.get(c.id()), query, true) >= 65).count();
            if (entityMatches.size() >= 2 && pickedEntityMatches >= 1 && pickedEntityMatches < entityMatches.size()) {
                for (Map.Entry<Integer, Document> entry : entityMatches) {
                    if (cards.size() >= MAX_CARDS) break;
                    int id = entry.getKey();
                    if (used.add(id)) cards.add(card(id, entry.getValue(), null));
                }
            }
        }

        if (cards.isEmpty()) return fallback(byId, summary.isEmpty() ? "AI가 고르지 못해서, 의미가 비슷한 시리즈를 그대로 보여드려요." : summary);
        cards.sort(BY_YEAR_DESC);
        return new TvResult(summary.isEmpty() ? null : summary, cards, true, null);
    }

    private TvResult fallback(Map<Integer, Document> byId, String message) {
        List<TvCard> cards = new ArrayList<>();
        for (Map.Entry<Integer, Document> e : byId.entrySet()) {
            if (cards.size() >= MAX_CARDS) break;
            cards.add(card(e.getKey(), e.getValue(), null));
        }
        cards.sort(BY_YEAR_DESC);
        return new TvResult(null, cards, false, message);
    }

    private static TvCard card(int id, Document doc, String reason) {
        Map<String, Object> m = doc.getMetadata();
        String poster = str(m.get("poster"));
        int year = intOf(m.get("year"));
        int seasons = intOf(m.get("seasons"));
        int episodes = intOf(m.get("episodes"));
        return new TvCard(id, str(m.get("title")), str(m.get("originalTitle")),
                year > 0 ? year : null, str(m.get("genres")), doubleOf(m.get("rating")),
                str(m.get("overview")), poster.isBlank() ? null : POSTER_BASE + poster,
                "https://www.themoviedb.org/tv/" + id, reason,
                str(m.get("creator")), str(m.get("cast")), str(m.get("networks")),
                seasons > 0 ? seasons : null, episodes > 0 ? episodes : null);
    }

    private static int parseId(String value) { try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return -1; } }
    private static String str(Object value) { return value == null ? "" : value.toString(); }
    private static int intOf(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private static double doubleOf(Object value) { return value instanceof Number n ? n.doubleValue() : 0; }
    private static String shorten(String value, int max) { return value.length() > max ? value.substring(0, max) + "…" : value; }
    private static String clip(String reason) { return reason == null || reason.isBlank() ? null : shorten(reason.replaceAll("[\\r\\n]+", " ").trim(), 90); }

    private synchronized boolean tryConsume(String username) {
        LocalDate today = LocalDate.now();
        if (!today.equals(usageDay)) { usage.clear(); usageDay = today; }
        int used = usage.getOrDefault(username, 0);
        if (used >= dailyLimit) return false;
        usage.put(username, used + 1);
        return true;
    }

    private record CachedResult(TvResult result, long expiresAt) {}
}
