package com.t.tcine.domain.search.ranking;

import com.t.tcine.domain.search.MediaKind;
import com.t.tcine.domain.search.config.RankingProperties;
import com.t.tcine.domain.search.config.SearchDictionary;
import com.t.tcine.domain.search.query.QueryAnalyzer;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

import static com.t.tcine.domain.search.ranking.DocFields.*;

/**
 * 키워드 점수, RRF 결합, 재순위(boostedScore)를 계산한다.
 * 점수 가중치는 {@link RankingProperties}, 장르·OTT 어휘는 {@link SearchDictionary}에서 온다.
 */
@Component
public class RankingEngine {

    private static final String ANIMATION_GENRE = "애니메이션";

    private final QueryAnalyzer analyzer;
    private final SearchDictionary dict;
    private final RankingProperties props;

    public RankingEngine(QueryAnalyzer analyzer, SearchDictionary dict, RankingProperties props) {
        this.analyzer = analyzer;
        this.dict = dict;
        this.props = props;
    }

    // ───────────────────────── 키워드 점수 ─────────────────────────

    public int keywordScore(Document document, String query, MediaKind kind) {
        return keywordScore(document, query, kind, true);
    }

    /**
     * @param withNetworks false 면 OTT/방송사 일치를 점수에 반영하지 않는다.
     *                     OTT 는 후보 선정 뒤 필터로 따로 적용되므로, RRF 의 고유명사 보너스에는 쓰지 않는다
     *                     (쓰면 ×25 로 증폭돼 장르·의미 점수가 순위에 영향을 못 준다).
     */
    public int keywordScore(Document document, String query, MediaKind kind, boolean withNetworks) {
        boolean isTv = kind.isTv();
        boolean useNetworks = isTv && withNetworks;
        RankingProperties.Keyword w = props.getKeyword();
        Map<String, Object> metadata = document.getMetadata();
        List<String> terms = analyzer.extractCoreTerms(query);
        Set<String> requestedNetworks = useNetworks ? analyzer.extractRequestedNetworks(query) : Set.of();

        if (terms.isEmpty() && requestedNetworks.isEmpty()) return 0;

        String fullCore = String.join("", terms);
        String title = QueryAnalyzer.compact(str(metadata.get("title")));
        String originalTitle = QueryAnalyzer.compact(str(metadata.get("originalTitle")));
        String cast = QueryAnalyzer.compact(str(metadata.get("cast")));
        String keywords = QueryAnalyzer.compact(str(metadata.getOrDefault("keywords", extractFieldFromContent(document.getText(), "키워드:"))));
        String genres = QueryAnalyzer.compact(str(metadata.get("genres")));
        String overview = QueryAnalyzer.compact(str(metadata.get("overview")));

        String creatorOrDirector = QueryAnalyzer.compact(str(metadata.get(isTv ? "creator" : "director")));
        String networks = useNetworks ? QueryAnalyzer.compact(str(metadata.get("networks"))) : "";
        int castScore = w.cast(isTv);

        int totalScore = 0;

        if (useNetworks && !requestedNetworks.isEmpty() && matchesRequestedNetworks(networks, requestedNetworks)) {
            totalScore = Math.max(totalScore, w.getNetworkMatch());
        }

        if (fullCore.length() >= 2) {
            if (title.equals(fullCore) || originalTitle.equals(fullCore)) totalScore = Math.max(totalScore, w.getTitleEquals());
            else if (title.startsWith(fullCore) || originalTitle.startsWith(fullCore)) totalScore = Math.max(totalScore, w.getTitleStartsWith());
            else if (title.contains(fullCore) || originalTitle.contains(fullCore)) totalScore = Math.max(totalScore, w.getTitleContains());
            else if (creatorOrDirector.contains(fullCore)) totalScore = Math.max(totalScore, w.getCreator());
            else if (cast.contains(fullCore) || (useNetworks && networkMatches(networks, fullCore))) totalScore = Math.max(totalScore, castScore);
            else if (keywords.contains(fullCore)) totalScore = Math.max(totalScore, w.getKeywords());
        }

        int termSum = 0;
        int matchedTerms = 0;
        for (String term : terms) {
            if (term.length() < w.getMinTermLength()) continue;
            int termScore = 0;
            if (title.equals(term) || originalTitle.equals(term)) termScore = w.getTermTitleEquals();
            else if (title.startsWith(term) || originalTitle.startsWith(term)) termScore = w.getTermTitleStartsWith();
            else if (creatorOrDirector.contains(term)) termScore = w.getTermCreator();
            else if (cast.contains(term) || (useNetworks && networkMatches(networks, term))) termScore = castScore;
            else if (title.contains(term) || originalTitle.contains(term)) termScore = w.getTermTitleContains();
            else if (keywords.contains(term)) termScore = w.getTermKeywords();
            else if (genres.contains(term)) termScore = w.getTermGenres();
            else if (overview.contains(term)) termScore = w.getTermOverview();

            if (termScore > 0) {
                matchedTerms++;
                termSum += termScore;
            }
        }

        if (terms.size() >= 2 && matchedTerms == terms.size()) termSum += w.getAllTermsBonus();
        return Math.max(totalScore, termSum);
    }

    /** 관련도 하한 (0 이하면 꺼짐) */
    public double minVectorScore() {
        return props.getRelevance().getMinVectorScore();
    }

    /** 키워드 점수가 "작품·인물 이름이 정확히 맞았다"고 볼 만큼 높은지 */
    public boolean isEntityMatch(int keywordScore) {
        return keywordScore >= props.getKeyword().getEntityStrong();
    }

    /** 키워드 점수를 RRF 에 더할 고유명사 일치 보너스로 바꾼다 */
    private double entityBonus(int kwScore) {
        RankingProperties.Keyword w = props.getKeyword();
        if (kwScore >= w.getEntityStrong()) return kwScore / 100.0;
        if (kwScore >= w.getEntityWeak()) return w.getEntityWeakBonus();
        return 0.0;
    }

    // ───────────────────────── 재순위 ─────────────────────────

    public double boostedScore(Document d, String query, Document refDoc, Set<String> requestedGenres,
                               Set<String> requestedNetworks, MediaKind kind) {
        boolean isTv = kind.isTv();
        Map<String, Object> m = d.getMetadata();
        double similarity = similarityScore(d);

        return similarity + recencyBonus(m, query, isTv) + ratingBonus(m)
                + alignmentBonus(d, query, refDoc, requestedGenres, requestedNetworks, kind);
    }

    /** 순위 점수 중 "의미·키워드 검색이 매긴 부분" (RRF 점수 + 벡터 유사도). 나머지는 장르·최신작·평점 등 가감점 */
    public double similarityScore(Document d) {
        Map<String, Object> m = d.getMetadata();
        double rrfPart = m.containsKey("hybrid_score") ? doubleOf(m.get("hybrid_score")) * props.getRrfWeight() : 0.0;
        double vecPart = m.containsKey("vector_score") ? doubleOf(m.get("vector_score")) * props.getVectorWeight()
                : (d.getScore() != null ? d.getScore() * props.getVectorWeight() : 0.0);
        return rrfPart + vecPart;
    }

    private double recencyBonus(Map<String, Object> m, String query, boolean isTv) {
        int year = intOf(m.get("year"));
        if (analyzer.wantsClassic(query) || year <= 0) return 0.0;

        RankingProperties.Recency r = props.recency(isTv);
        int thisYear = LocalDate.now().getYear();
        double normalizedRecency = Math.max(0, Math.min(1.0, (year - r.getBaseYear()) / (double) Math.max(1, thisYear - r.getBaseYear())));
        double bonus = normalizedRecency * r.getWeight();
        for (RankingProperties.Penalty p : r.getPenalties()) {
            if (year < p.getBeforeYear()) {
                bonus += p.getPenalty();
                break;
            }
        }
        return bonus;
    }

    private double ratingBonus(Map<String, Object> m) {
        RankingProperties.Rating r = props.getRating();
        double rating = doubleOf(m.get("rating"));
        if (rating <= 0) return 0.0;
        if (rating < r.getLowThreshold()) return r.getLowPenalty();
        return Math.max(0, Math.min(1.0, (rating - r.getBase()) / r.getSpan())) * r.getWeight();
    }

    private double alignmentBonus(Document d, String query, Document refDoc, Set<String> requestedGenres,
                                  Set<String> requestedNetworks, MediaKind kind) {
        RankingProperties.Alignment a = props.getAlignment();
        Map<String, Object> m = d.getMetadata();
        String candGenres = str(m.get("genres"));
        boolean candIsAnimation = candGenres.contains(ANIMATION_GENRE);
        boolean queryWantsAnimation = analyzer.wantsAnimation(query);
        double bonus = 0.0;

        if (refDoc != null) {
            Map<String, Object> rm = refDoc.getMetadata();
            String refGenres = str(rm.get("genres"));
            boolean refIsAnimation = refGenres.contains(ANIMATION_GENRE);

            if (!refIsAnimation && candIsAnimation && !queryWantsAnimation) bonus += a.getAnimationMismatch();
            else if (refIsAnimation && candIsAnimation) bonus += a.getAnimationBoth();

            Set<String> refGenreSet = splitTokens(refGenres);
            Set<String> candGenreSet = splitTokens(candGenres);
            if (!refGenreSet.isEmpty() && !candGenreSet.isEmpty()) {
                long sharedGenres = candGenreSet.stream().filter(refGenreSet::contains).count();
                if (sharedGenres == 0) bonus += a.getNoSharedGenres();
                else bonus += Math.min(a.getSharedGenreCap(), sharedGenres * a.getSharedGenreEach());
            }

            Set<String> refKwSet = splitTokens(str(rm.get("keywords")));
            Set<String> candKwSet = splitTokens(str(m.get("keywords")));
            if (!refKwSet.isEmpty() && !candKwSet.isEmpty()) {
                long sharedKw = candKwSet.stream().filter(refKwSet::contains).count();
                bonus += Math.min(a.getSharedKeywordCap(), sharedKw * a.getSharedKeywordEach());
            }
        } else {
            if (!queryWantsAnimation && candIsAnimation && query != null && !analyzer.wantsFamily(query)) {
                bonus += a.getUnrelatedAnimation();
            }
            if (kind.isTv() && requestedNetworks != null && !requestedNetworks.isEmpty()) {
                String networksCompact = QueryAnalyzer.compact(str(m.get("networks")));
                bonus += matchesRequestedNetworks(networksCompact, requestedNetworks) ? a.getNetworkHit() : a.getNetworkMiss();
            }
            if (!requestedGenres.isEmpty()) {
                bonus += matchesRequestedGenres(d, requestedGenres, kind) ? a.getGenreHit() : a.getGenreMiss();
            }
            if (hasConflictingGenre(d, query, requestedGenres, kind)) {
                bonus += a.getConflictingGenre();
            }
        }
        return bonus;
    }

    // ───────────────────────── 장르 ─────────────────────────

    public boolean matchesRequestedGenres(Document doc, Set<String> requestedGenres, MediaKind kind) {
        if (requestedGenres.isEmpty()) return true;
        boolean isTv = kind.isTv();
        Map<String, Object> m = doc.getMetadata();
        String candGenres = QueryAnalyzer.compact(str(m.get("genres")));
        String candKeywords = QueryAnalyzer.compact(str(m.getOrDefault("keywords", extractFieldFromContent(doc.getText(), "키워드:"))));
        for (String req : requestedGenres) {
            if (candGenres.contains(req)) return true;
            if (isTv && dict.tvKeywordMatchGenreName() && candKeywords.contains(req)) return true;
            for (SearchDictionary.GenreSynonym syn : dict.genreKeywordSynonyms()) {
                if (!syn.genre().equals(req)) continue;
                if (containsAny(candKeywords, syn.keywords())) return true;
                if (isTv && containsAny(candKeywords, syn.tvKeywords())) return true;
            }
        }
        return false;
    }

    /** 가볍고 따뜻한 분위기를 요청했는데 어울리지 않는 장르(전쟁·공포·범죄 등)가 섞인 후보인지 */
    public boolean hasConflictingGenre(Document doc, String query, Set<String> requestedGenres, MediaKind kind) {
        if (query == null || query.isBlank()) return false;
        if (!wantsLightMood(query, requestedGenres, kind)) return false;

        String candGenres = QueryAnalyzer.compact(str(doc.getMetadata().get("genres")));
        for (SearchDictionary.ConflictRule rule : dict.conflicts().getOrDefault(kind.key(), List.of())) {
            if (!containsAny(candGenres, rule.blocked())) continue;
            if (rule.unlessRequested().stream().anyMatch(requestedGenres::contains)) continue;
            if (containsAny(candGenres, rule.unlessCandidateHas())) continue;
            return true;
        }
        return false;
    }

    private boolean wantsLightMood(String query, Set<String> requestedGenres, MediaKind kind) {
        SearchDictionary.LightMood mood = dict.lightMood();
        String q = QueryAnalyzer.compact(query);
        return mood.triggerGenres().stream().anyMatch(requestedGenres::contains)
                || containsAny(q, mood.triggers())
                || (!kind.isTv() && containsAny(q, mood.movieExtraTriggers()));
    }

    // ───────────────────────── OTT ─────────────────────────

    public boolean matchesRequestedNetworks(String networksCompact, Set<String> requestedNetworks) {
        if (requestedNetworks == null || requestedNetworks.isEmpty()) return true;
        if (networksCompact.isEmpty()) return false;
        for (String net : requestedNetworks) {
            if (networkMatches(networksCompact, net)) return true;
        }
        return false;
    }

    public boolean matchesRequestedNetworks(Document doc, Set<String> requestedNetworks) {
        return matchesRequestedNetworks(QueryAnalyzer.compact(str(doc.getMetadata().get("networks"))), requestedNetworks);
    }

    private boolean networkMatches(String networksCompact, String term) {
        if (networksCompact.isEmpty() || term.isEmpty()) return false;
        if (networksCompact.contains(term)) return true;
        for (SearchDictionary.Network network : dict.networks()) {
            if (containsAny(term, network.termTriggers())) {
                return containsAny(networksCompact, network.dataAliases());
            }
        }
        return false;
    }

    // ───────────────────────── RRF 결합 ─────────────────────────

    /** 키워드 검색과 벡터 검색 결과를 RRF 로 합쳐 상위 limit 건을 돌려준다 (메타데이터에 점수를 기록한다) */
    public List<Document> mergeAndRank(List<Document> keywordDocs, List<Document> vectorDocs, String searchText,
                                       boolean runKeywordSearch, int limit, MediaKind kind) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> docMap = new LinkedHashMap<>();
        int rrfK = props.getRrfK();

        for (int i = 0; i < keywordDocs.size(); i++) {
            Document doc = keywordDocs.get(i);
            double bonus = entityBonus(keywordScore(doc, searchText, kind, false));
            rrfScores.put(doc.getId(), rrfScores.getOrDefault(doc.getId(), 0.0) + (1.0 / (rrfK + i + 1)) + bonus);
            docMap.put(doc.getId(), doc);
        }
        for (int i = 0; i < vectorDocs.size(); i++) {
            Document doc = vectorDocs.get(i);
            enrichMetadataFromContent(doc);
            if (doc.getScore() != null) {
                doc.getMetadata().put("vector_score", doc.getScore());
            }
            double bonus = 0.0;
            if (runKeywordSearch && !docMap.containsKey(doc.getId())) {
                bonus = entityBonus(keywordScore(doc, searchText, kind, false));
            }
            rrfScores.put(doc.getId(), rrfScores.getOrDefault(doc.getId(), 0.0) + (1.0 / (rrfK + i + 1)) + bonus);
            Document existing = docMap.putIfAbsent(doc.getId(), doc);
            if (existing != null && doc.getScore() != null) {
                existing.getMetadata().put("vector_score", doc.getScore());
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    Document d = docMap.get(entry.getKey());
                    d.getMetadata().put("hybrid_score", entry.getValue());
                    return d;
                })
                .toList();
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }
}
