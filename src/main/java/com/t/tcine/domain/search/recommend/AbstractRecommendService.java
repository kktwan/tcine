package com.t.tcine.domain.search.recommend;

import com.t.tcine.domain.movie.dto.ModelAnswer;
import com.t.tcine.domain.movie.dto.ModelAnswer.ModelPick;
import com.t.tcine.domain.search.MediaKind;
import com.t.tcine.domain.search.corpus.CorpusSource;
import com.t.tcine.domain.search.query.QueryAnalyzer;
import com.t.tcine.domain.search.ranking.RankingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static com.t.tcine.domain.search.ranking.DocFields.*;

/**
 * 영화·시리즈 AI 추천의 공통 흐름 (하이브리드 검색 → 재순위/필터 → Gemini 큐레이션).
 *
 * <pre>
 * run()
 *  ├ resolveReference  "OO와 비슷한 작품" 이면 기준 작품과 검색 문장을 정한다
 *  ├ retrieve          키워드 검색 + 벡터 검색을 병렬로 돌려 RRF 로 합친다
 *  ├ selectCandidates  boostedScore 로 재정렬하고 장르·OTT·시리즈 필터를 적용한다
 *  └ curate            Gemini 가 고르고 설명을 붙인다 (시간 초과·실패 시 검색 순서 그대로)
 * </pre>
 *
 * 영화와 시리즈의 차이(카드 모양, 후보 목록 표기, 문구, 기준 작품 시리즈 제외 방식)는 하위 클래스가 채운다.
 */
public abstract class AbstractRecommendService<R extends Recommendation<C>, C extends RecommendCard> {

    private static final Logger log = LoggerFactory.getLogger(AbstractRecommendService.class);

    protected static final String POSTER_BASE = "https://image.tmdb.org/t/p/w342";

    private static final int CANDIDATES = 20;
    private static final int FETCH = 60;
    private static final int MAX_CARDS = 18;
    private static final long TIMEOUT_SECONDS = 25;
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int CACHE_MAX_ENTRIES = 300;
    private static final int MAX_QUERY_LENGTH = 150;

    private final MediaKind kind;
    private final String label;
    private final RecommendMessages messages;
    private final VectorStore vectorStore;
    private final CorpusSource corpus;
    private final LongSupplier indexedCount;
    private final ChatClient chatClient;
    private final Resource promptResource;
    private final QueryAnalyzer analyzer;
    private final RankingEngine ranking;
    private final boolean aiEnabled;
    private final boolean embeddingConfigured;

    private final ExecutorService pool;
    private final ResultCache<R> cache = new ResultCache<>(CACHE_MAX_ENTRIES, CACHE_TTL_MILLIS);
    private final UsageLimiter limiter;

    private final Comparator<RecommendCard> byYearDesc = Comparator
            .<RecommendCard>comparingInt(c -> c.year() != null && c.year() > 0 ? c.year() : 0).reversed()
            .thenComparing(Comparator.<RecommendCard>comparingDouble(RecommendCard::rating).reversed());

    protected AbstractRecommendService(MediaKind kind, String label, String threadName, RecommendMessages messages,
                                       VectorStore vectorStore, CorpusSource corpus, LongSupplier indexedCount,
                                       ChatClient chatClient, Resource promptResource,
                                       QueryAnalyzer analyzer, RankingEngine ranking,
                                       String apiKey, String openAiKey, int dailyLimit) {
        this.kind = kind;
        this.label = label;
        this.messages = messages;
        this.vectorStore = vectorStore;
        this.corpus = corpus;
        this.indexedCount = indexedCount;
        this.chatClient = chatClient;
        this.promptResource = promptResource;
        this.analyzer = analyzer;
        this.ranking = ranking;
        this.aiEnabled = isConfigured(apiKey);
        this.embeddingConfigured = isConfigured(openAiKey);
        this.limiter = new UsageLimiter(dailyLimit);
        this.pool = Executors.newFixedThreadPool(6, r -> {
            Thread thread = new Thread(r, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    // ───────────────────────── 하위 클래스가 채우는 부분 ─────────────────────────

    protected abstract R newResult(String summary, List<C> cards, boolean ai, String message);

    protected abstract C card(int id, Document doc, String reason);

    /** Gemini 에게 보여 줄 후보 한 줄 ("id|제목|연도|…") */
    protected abstract String candidateLine(int id, Document doc);

    /** 기준 작품과 같은 시리즈를 후보에서 걸러 낼 때 쓰는 제목 줄기(compact 형태). 없으면 빈 문자열 */
    protected abstract String seriesStem(String title);

    /** run() 전에 먼저 처리할 요청이 있으면 그 결과를, 없으면 null (예: 영화의 "시리즈 전체 보기") */
    protected R handleBeforeRun(String q) {
        return null;
    }

    // ───────────────────────── 공개 진입점 ─────────────────────────

    public boolean isEnabled() {
        return aiEnabled;
    }

    /** AI 없이 제목·인물·장르 키워드로만 찾는다 */
    public R searchFast(String query) {
        String q = QueryAnalyzer.normalize(query, MAX_QUERY_LENGTH);
        if (q.isEmpty()) return emptyResult(null);
        try {
            List<Document> matches = findKeywordMatches(q).stream().limit(MAX_CARDS).toList();
            Map<Integer, Document> byId = new LinkedHashMap<>();
            matches.forEach(document -> byId.putIfAbsent(intOf(document.getMetadata().get("tmdbId")), document));
            List<C> cards = new ArrayList<>();
            matches.forEach(document -> cards.add(card(intOf(document.getMetadata().get("tmdbId")), document, null)));
            sortCards(cards, q, byId, true);
            return newResult(null, cards, false, cards.isEmpty() ? messages.noKeywordMatch() : null);
        } catch (Exception e) {
            log.warn("{} 키워드 검색 실패: {}", label, e.getMessage());
            return emptyResult(indexedCount.getAsLong() == 0 ? messages.keywordNotReady() : messages.keywordUnavailable());
        }
    }

    public R recommend(String username, String query) {
        String q = QueryAnalyzer.normalize(query, MAX_QUERY_LENGTH);
        if (q.isEmpty()) return emptyResult(null);
        R redirected = handleBeforeRun(q);
        if (redirected != null) return redirected;
        String cacheKey = QueryAnalyzer.compact(q).isEmpty() ? q.toLowerCase(Locale.ROOT) : QueryAnalyzer.compact(q);
        return run(username, cacheKey, q, q, 0);
    }

    /** 기준 작품 한 편과 비슷한 작품 추천. 하위 클래스가 상세 정보를 읽어 이 메서드로 넘긴다 */
    protected R recommendSimilarTo(String username, String cacheKey, int id, String title, String genres,
                                   Collection<String> keywords, String tagline, String overview) {
        StringBuilder text = new StringBuilder();
        if (genres != null && !genres.isBlank()) text.append("장르: ").append(genres).append("\n");
        if (keywords != null && !keywords.isEmpty()) text.append("키워드: ").append(String.join(", ", keywords)).append("\n");
        if (tagline != null && !tagline.isBlank()) text.append("한줄 소개: ").append(tagline).append("\n");
        if (overview != null && !overview.isBlank()) text.append("줄거리: ").append(overview);

        if (text.length() == 0) return emptyResult(messages.insufficientInfo());
        String request = messages.similarByIdRequest().formatted(title, shorten(text.toString(), 600));
        return run(username, cacheKey, text.toString(), request, id);
    }

    protected R emptyResult(String message) {
        return newResult(null, List.of(), false, message);
    }

    protected String detailMissingMessage() {
        return messages.detailMissing();
    }

    // ───────────────────────── 추천 실행 ─────────────────────────

    private R run(String username, String key, String searchText, String requestText, int excludeId) {
        if (!aiEnabled) return emptyResult(RecommendMessages.AI_NOT_CONFIGURED);
        if (!embeddingConfigured) return emptyResult(messages.embeddingNeeded());
        long now = System.currentTimeMillis();
        R cached = cache.get(key, now);
        if (cached != null) return cached;
        if (!limiter.tryConsume(username)) return emptyResult(RecommendMessages.LIMIT_REACHED);

        long started = System.currentTimeMillis();
        Reference reference;
        List<Document> docs;
        try {
            reference = resolveReference(searchText, requestText, excludeId);
            docs = retrieve(searchText, reference, excludeId);
        } catch (Exception e) {
            log.warn("{} 하이브리드 검색 실패: {}", label, e.getMessage());
            return emptyResult(indexedCount.getAsLong() == 0 ? messages.noIndexedAtAll() : messages.qdrantUnavailable());
        }
        long searchedAt = System.currentTimeMillis();
        if (docs.isEmpty()) return emptyResult(messages.indexEmpty());
        if (isIrrelevant(docs, reference, excludeId, searchText)) {
            log.info("{} 추천: 관련 작품 없음으로 판단해 AI 를 부르지 않음 (질의={})", label, searchText);
            return emptyResult(messages.noRelevantResult());
        }

        Map<Integer, Document> candidates = selectCandidates(docs, reference, requestText, excludeId);

        R result;
        try {
            result = curate(reference, candidates, requestText, excludeId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return emptyResult(RecommendMessages.INTERRUPTED);
        }

        log.info("{} 추천 소요: 검색(임베딩+Qdrant) {}ms, AI {}ms, 합계 {}ms, 벡터1위 {}, 결과 {}편", label,
                searchedAt - started, System.currentTimeMillis() - searchedAt, System.currentTimeMillis() - started,
                String.format("%.3f", topVectorScore(docs)), result.cardCount());
        if (result.hasCards() && result.message() == null) cache.put(key, result, now);
        return result;
    }

    /** 1단계: 기준 작품(있다면)과 그에 맞춘 검색 문장·요청 문장을 정한다 */
    private Reference resolveReference(String searchText, String requestText, int excludeId) throws Exception {
        Reference plain = new Reference(null, "", searchText, requestText);

        if (excludeId > 0) {
            Document refDoc = corpus.getCorpus().stream()
                    .filter(d -> intOf(d.getMetadata().get("tmdbId")) == excludeId)
                    .findFirst().orElse(null);
            return refDoc == null ? plain : new Reference(refDoc, seriesStem(str(refDoc.getMetadata().get("title"))), searchText, requestText);
        }

        String targetTitle = analyzer.similarTargetTitle(requestText);
        if (targetTitle.isEmpty()) return plain;
        List<Document> targetMatches = findKeywordMatches(targetTitle).stream()
                .filter(d -> ranking.isEntityMatch(ranking.keywordScore(d, targetTitle, kind, false))).toList();
        if (targetMatches.isEmpty()) return plain;

        Document ref = targetMatches.get(0);
        Map<String, Object> rm = ref.getMetadata();
        String mergedGenres = targetMatches.stream().limit(3)
                .map(d -> str(d.getMetadata().get("genres"))).filter(s -> !s.isBlank())
                .flatMap(s -> Arrays.stream(s.split(","))).map(String::trim).filter(s -> !s.isEmpty()).distinct()
                .collect(Collectors.joining(", "));
        String mergedKeywords = targetMatches.stream().limit(3)
                .map(d -> str(d.getMetadata().getOrDefault("keywords", extractFieldFromContent(d.getText(), "키워드:"))))
                .filter(s -> !s.isBlank()).flatMap(s -> Arrays.stream(s.split(","))).map(String::trim).filter(s -> !s.isEmpty()).distinct()
                .limit(20).collect(Collectors.joining(", "));
        if (!mergedGenres.isBlank()) rm.put("genres", mergedGenres);
        if (!mergedKeywords.isBlank()) rm.put("keywords", mergedKeywords);
        String refTagline = str(rm.getOrDefault("tagline", extractFieldFromContent(ref.getText(), "한줄 소개:")));

        StringBuilder searchSb = new StringBuilder();
        searchSb.append("장르: ").append(str(rm.get("genres"))).append("\n");
        if (!mergedKeywords.isBlank()) searchSb.append("핵심 키워드: ").append(mergedKeywords).append("\n");
        if (!refTagline.isBlank()) searchSb.append("분위기: ").append(refTagline).append("\n");
        searchSb.append("줄거리: ").append(str(rm.get("overview")));
        String effectiveSearchText = searchSb.toString();
        String effectiveRequestText = messages.similarByTitleRequest().formatted(str(rm.get("title")), shorten(effectiveSearchText, 500));
        return new Reference(ref, QueryAnalyzer.compact(targetTitle), effectiveSearchText, effectiveRequestText);
    }

    /** 2단계: 키워드 검색과 벡터 검색을 병렬로 돌려 RRF 로 합친다 */
    private List<Document> retrieve(String searchText, Reference reference, int excludeId) {
        final String vectorQuery = reference.searchText();
        final boolean runKeywordSearch = excludeId == 0 && reference.excludeTitleCompact().isEmpty();

        CompletableFuture<List<Document>> keywordFuture = runKeywordSearch
                ? CompletableFuture.supplyAsync(() -> {
                    try { return findKeywordMatches(searchText); } catch (Exception e) { return List.<Document>of(); }
                }, pool)
                : CompletableFuture.completedFuture(List.of());

        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(() ->
                vectorStore.similaritySearch(SearchRequest.builder().query(vectorQuery).topK(FETCH).similarityThreshold(0.0).build()), pool);

        List<Document> keywordDocs = keywordFuture.join();
        List<Document> vectorDocs = vectorFuture.join();
        return ranking.mergeAndRank(keywordDocs, vectorDocs, searchText, runKeywordSearch, FETCH, kind);
    }

    /**
     * 검색 품질 진단용: Gemini 를 부르지 않고 검색 단계의 점수 분포만 돌려준다
     * (관련도 하한을 정하거나 평가 세트에서 질의별 점수를 볼 때 쓴다).
     */
    public RetrievalProbe probe(String query) throws Exception {
        String q = QueryAnalyzer.normalize(query, MAX_QUERY_LENGTH);
        Reference reference = resolveReference(q, q, 0);
        List<Document> vectorDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(reference.searchText()).topK(FETCH).similarityThreshold(0.0).build());
        List<Double> vectorScores = vectorDocs.stream().map(d -> d.getScore() == null ? 0.0 : d.getScore()).toList();
        List<Document> keywordDocs = findKeywordMatches(q);
        int topKeyword = keywordDocs.isEmpty() ? 0 : ranking.keywordScore(keywordDocs.get(0), q, kind);
        return new RetrievalProbe(vectorScores, keywordDocs.size(), topKeyword, reference.doc() != null);
    }

    /**
     * 검색 품질 진단용: 후보가 어떤 점수로 순위가 매겨지는지 항목별로 돌려준다 (Gemini 호출 없음).
     * similarity = RRF·벡터가 매긴 의미 점수, bonus = 장르·최신작·평점·기준작품·OTT 가감점 합계.
     */
    public List<CandidateExplain> explain(String query, int limit) throws Exception {
        String q = QueryAnalyzer.normalize(query, MAX_QUERY_LENGTH);
        Reference reference = resolveReference(q, q, 0);
        List<Document> docs = retrieve(q, reference, 0);
        Document refDoc = reference.doc();
        Set<String> requestedNetworks = refDoc == null && kind.isTv() ? analyzer.extractRequestedNetworks(q) : Set.of();
        Set<String> requestedGenres = refDoc == null ? analyzer.extractRequestedGenres(q, kind) : Set.of();

        List<CandidateExplain> rows = new ArrayList<>();
        for (Document d : docs) {
            if (refDoc != null && d.getId().equals(refDoc.getId())) continue;
            double total = ranking.boostedScore(d, q, refDoc, requestedGenres, requestedNetworks, kind);
            double similarity = ranking.similarityScore(d);
            Map<String, Object> m = d.getMetadata();
            rows.add(new CandidateExplain(str(m.get("title")), intOf(m.get("year")), str(m.get("genres")),
                    doubleOf(m.get("vector_score")), similarity, total - similarity, total));
        }
        rows.sort(Comparator.comparingDouble(CandidateExplain::total).reversed());
        return rows.stream().limit(limit).toList();
    }

    public record CandidateExplain(String title, int year, String genres, double vector, double similarity,
                                   double bonus, double total) {}

    /** 검색 단계 점수 분포: 벡터 유사도(내림차순), 키워드 일치 작품 수, 키워드 최고점, 기준 작품을 찾았는지 */
    public record RetrievalProbe(List<Double> vectorScores, int keywordMatches, int topKeywordScore, boolean referenceFound) {}

    /** 합쳐진 후보 중 벡터 유사도 최고점 (관련도 하한을 정할 때 운영 로그에서 본다) */
    private static double topVectorScore(List<Document> docs) {
        return docs.stream().mapToDouble(d -> doubleOf(d.getMetadata().get("vector_score"))).max().orElse(0);
    }

    /**
     * 검색어와 관련 있는 작품이 없다고 볼 수 있는지: 벡터 유사도 1위가 하한보다 낮고, 제목·인물이 맞은 작품도 없을 때.
     * 기준 작품이 있는 "비슷한 작품" 요청에는 적용하지 않는다. 하한이 0 이하면 항상 false.
     */
    private boolean isIrrelevant(List<Document> docs, Reference reference, int excludeId, String searchText) {
        double min = ranking.minVectorScore();
        if (min <= 0 || excludeId != 0 || reference.doc() != null) return false;
        double topVector = docs.stream().mapToDouble(d -> doubleOf(d.getMetadata().get("vector_score"))).max().orElse(0);
        if (topVector >= min) return false;
        return docs.stream().noneMatch(d -> ranking.isEntityMatch(ranking.keywordScore(d, searchText, kind, false)));
    }

    /** 3단계: 재순위 후 장르·OTT·기준 작품 시리즈 필터를 적용해 Gemini 에게 줄 후보 CANDIDATES 편을 추린다 */
    private Map<Integer, Document> selectCandidates(List<Document> docs, Reference reference, String queryForRank, int excludeId) {
        final Document refForRank = reference.doc();
        final String excludeTitleCompact = reference.excludeTitleCompact();
        final Set<String> requestedNetworks = refForRank == null && kind.isTv() ? analyzer.extractRequestedNetworks(queryForRank) : Set.of();
        final Set<String> requestedGenres = refForRank == null ? analyzer.extractRequestedGenres(queryForRank, kind) : Set.of();

        List<Document> ranked = new ArrayList<>(docs);
        ranked.sort(Comparator.comparingDouble((Document d) ->
                -ranking.boostedScore(d, queryForRank, refForRank, requestedGenres, requestedNetworks, kind)));

        long strictNetworkMatches = ranked.stream().filter(d -> ranking.matchesRequestedNetworks(d, requestedNetworks)).count();
        boolean enforceNetworkFilter = !requestedNetworks.isEmpty() && strictNetworkMatches >= 1;

        long strictGenreMatches = ranked.stream()
                .filter(d -> (!enforceNetworkFilter || ranking.matchesRequestedNetworks(d, requestedNetworks))
                        && ranking.matchesRequestedGenres(d, requestedGenres, kind)
                        && !ranking.hasConflictingGenre(d, queryForRank, requestedGenres, kind))
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
            if (enforceNetworkFilter && !ranking.matchesRequestedNetworks(doc, requestedNetworks)) continue;
            if (refForRank == null && ranking.hasConflictingGenre(doc, queryForRank, requestedGenres, kind)) continue;
            if (enforceGenreFilter && !ranking.matchesRequestedGenres(doc, requestedGenres, kind)) continue;
            byId.putIfAbsent(id, doc);
        }
        return byId;
    }

    /** 4단계: Gemini 가 후보 중에서 고르고 설명을 붙인다. 시간 초과·실패 시 검색 순서 그대로 보여 준다 */
    private R curate(Reference reference, Map<Integer, Document> byId, String requestText, int excludeId) throws InterruptedException {
        final String modelRequest = reference.requestText();
        try {
            final String entityQuery = entityFirstAllowed(reference, excludeId) ? requestText : null;
            Future<ModelAnswer> future = pool.submit(() -> askModel(modelRequest, byId, entityQuery));
            ModelAnswer answer = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return toResult(requestText, answer, byId, entityFirstAllowed(reference, excludeId));
        } catch (TimeoutException e) {
            log.warn("{} 추천 AI 시간 초과({}초)", label, TIMEOUT_SECONDS);
            return fallback(requestText, byId, entityFirstAllowed(reference, excludeId), messages.aiTimeout());
        } catch (ExecutionException e) {
            log.warn("{} 추천 AI 실패: {}", label, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return fallback(requestText, byId, entityFirstAllowed(reference, excludeId), messages.aiFailed());
        }
    }

    // ───────────────────────── 검색·Gemini 호출·결과 조립 ─────────────────────────

    /** 코퍼스에서 키워드 점수가 있는 작품을 점수·평점·최신순으로 */
    private List<Document> findKeywordMatches(String q) throws Exception {
        return corpus.getCorpus().stream()
                .filter(document -> ranking.keywordScore(document, q, kind) > 0)
                .sorted(Comparator.comparingInt((Document document) -> -ranking.keywordScore(document, q, kind))
                        .thenComparing(Comparator.comparingDouble((Document document) -> doubleOf(document.getMetadata().get("rating"))).reversed())
                        .thenComparingInt(document -> {
                            int year = intOf(document.getMetadata().get("year"));
                            return year > 0 ? -year : Integer.MAX_VALUE;
                        }))
                .toList();
    }

    /**
     * @param entityQuery 제목·인물·OTT 일치를 후보에 표시할 때 쓸 사용자 검색어 (기준 작품 요청처럼 표시하지 않을 때는 null).
     *                    후보 줄은 출연진 등을 줄여서 보내므로, 줄에서 안 보이는 일치를 AI 가 놓치지 않게 알려 준다.
     */
    private ModelAnswer askModel(String q, Map<Integer, Document> byId, String entityQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("요청: \"").append(q).append("\"\n\n").append(messages.candidateHeader()).append('\n');
        for (Map.Entry<Integer, Document> e : byId.entrySet()) {
            sb.append(candidateLine(e.getKey(), e.getValue()));
            if (entityQuery != null && ranking.isEntityMatch(ranking.keywordScore(e.getValue(), entityQuery, kind, false))) {
                sb.append(" [검색어 일치]");
            }
            sb.append('\n');
        }

        String systemPrompt = "";
        try {
            systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to read system prompt resource", e);
        }

        log.debug("{} 추천 AI 요청: {}", label, sb);
        ModelAnswer answer = chatClient.prompt().system(systemPrompt).user(sb.toString()).call().entity(ModelAnswer.class);
        log.debug("{} 추천 AI 응답: {}", label, answer);
        return answer;
    }

    private R toResult(String query, ModelAnswer answer, Map<Integer, Document> byId, boolean allowEntityCompletion) {
        String summary = answer == null || answer.summary() == null ? "" : answer.summary().trim();
        List<C> cards = new ArrayList<>();
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

        if (allowEntityCompletion && !analyzer.extractCoreTerms(query).isEmpty()) {
            completeEntityMatches(query, byId, cards, used);
        }

        // AI 가 하나도 고르지 못했어도 제목·인물이 정확히 맞은 후보는 보여 준다
        if (cards.isEmpty() && allowEntityCompletion) addEntityMatches(query, byId, cards, used);

        // AI 가 정상 응답했지만 맞는 작품이 없다고 한 경우: 관련 없는 후보를 그대로 보여 주지 않고 솔직히 알린다
        // (AI 의 summary 는 안내 문구가 아니라 코멘트이므로 쓰지 않는다)
        boolean aiSaidNone = answer != null && (answer.picks() == null || answer.picks().isEmpty());
        if (cards.isEmpty() && aiSaidNone) return emptyResult(messages.noRelevantResult());

        // AI 응답이 비정상(고른 id 가 후보에 없는 경우 등)이면 검색 순서대로 보여 준다
        if (cards.isEmpty()) return fallback(query, byId, allowEntityCompletion, messages.aiPickedNothing());
        sortCards(cards, query, byId, allowEntityCompletion);
        return newResult(summary.isEmpty() ? null : summary, cards, true, null);
    }

    /**
     * 제목·인물이 질의와 정확히 맞는 후보가 여럿인데 AI 가 일부만 골랐다면 나머지도 카드에 보탠다
     * (예: "해리포터" 검색에서 시리즈 일부만 고르는 경우).
     */
    private void addEntityMatches(String query, Map<Integer, Document> byId, List<C> cards, Set<Integer> used) {
        for (Map.Entry<Integer, Document> entry : byId.entrySet()) {
            if (cards.size() >= MAX_CARDS) break;
            if (ranking.isEntityMatch(ranking.keywordScore(entry.getValue(), query, kind, false)) && used.add(entry.getKey())) {
                cards.add(card(entry.getKey(), entry.getValue(), null));
            }
        }
    }

    private void completeEntityMatches(String query, Map<Integer, Document> byId, List<C> cards, Set<Integer> used) {
        List<Map.Entry<Integer, Document>> entityMatches = byId.entrySet().stream()
                .filter(e -> ranking.isEntityMatch(ranking.keywordScore(e.getValue(), query, kind, false))).toList();
        long pickedEntityMatches = cards.stream()
                .filter(c -> byId.containsKey(c.id()) && ranking.isEntityMatch(ranking.keywordScore(byId.get(c.id()), query, kind, false))).count();
        if (entityMatches.size() >= 2 && pickedEntityMatches >= 1 && pickedEntityMatches < entityMatches.size()) {
            for (Map.Entry<Integer, Document> entry : entityMatches) {
                if (cards.size() >= MAX_CARDS) break;
                int id = entry.getKey();
                if (used.add(id)) cards.add(card(id, entry.getValue(), null));
            }
        }
    }

    /** 기준 작품이 없는 일반 검색인지 (제목·인물 일치 작품을 앞세우는 규칙은 이때만 쓴다) */
    private static boolean entityFirstAllowed(Reference reference, int excludeId) {
        return excludeId == 0 && reference.excludeTitleCompact().isEmpty();
    }

    /**
     * 카드를 최신순으로 정렬하되, 제목·인물이 질의와 정확히 맞는 작품(키워드 점수 {@code entityStrong} 이상)은
     * 연도와 상관없이 앞에 둔다. 그 안에서도 최신순. 분위기·장르 검색처럼 일치 작품이 없으면 그냥 최신순이다.
     */
    private void sortCards(List<C> cards, String query, Map<Integer, Document> byId, boolean entityFirst) {
        if (!entityFirst) {
            cards.sort(byYearDesc);
            return;
        }
        Set<Integer> entityIds = new HashSet<>();
        for (C c : cards) {
            Document doc = byId.get(c.id());
            if (doc != null && ranking.isEntityMatch(ranking.keywordScore(doc, query, kind, false))) entityIds.add(c.id());
        }
        cards.sort(Comparator.<C>comparingInt(c -> entityIds.contains(c.id()) ? 0 : 1).thenComparing(byYearDesc));
    }

    private R fallback(String query, Map<Integer, Document> byId, boolean entityFirst, String message) {
        List<C> cards = new ArrayList<>();
        for (Map.Entry<Integer, Document> e : byId.entrySet()) {
            if (cards.size() >= MAX_CARDS) break;
            cards.add(card(e.getKey(), e.getValue(), null));
        }
        sortCards(cards, query, byId, entityFirst);
        return newResult(null, cards, false, message);
    }

    // ───────────────────────── 도우미 ─────────────────────────

    /** 후보 줄에 넣을 키워드 (메타데이터에 없으면 본문 "키워드:" 줄에서) */
    protected static String keywordsOf(Document doc) {
        return str(doc.getMetadata().getOrDefault("keywords", extractFieldFromContent(doc.getText(), "키워드:")));
    }

    protected static String shorten(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    private static boolean isConfigured(String key) {
        return key != null && !key.isBlank() && !"not-configured".equals(key);
    }

    private static int parseId(String value) {
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static String clip(String reason) {
        return reason == null || reason.isBlank() ? null : shorten(reason.replaceAll("[\\r\\n]+", " ").trim(), 90);
    }

    /**
     * 검색에 쓸 기준 작품 정보.
     * doc: 기준 작품(없으면 null), excludeTitleCompact: 후보에서 뺄 제목 줄기(없으면 빈 문자열),
     * searchText: 벡터 검색 문장, requestText: Gemini 에게 보낼 요청 문장
     */
    private record Reference(Document doc, String excludeTitleCompact, String searchText, String requestText) {}
}
