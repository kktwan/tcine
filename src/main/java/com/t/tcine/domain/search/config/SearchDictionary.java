package com.t.tcine.domain.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 검색어 해석·순위 규칙에 쓰는 사전 (src/main/resources/search-dictionary.yml).
 * 새 OTT나 장르 표현은 코드가 아니라 yml 에 추가한다.
 */
@ConfigurationProperties(prefix = "search.dictionary")
public record SearchDictionary(
        String similarQueryPattern,
        String classicEraPattern,
        List<String> stopwords,
        List<String> protectedWordSuffixes,
        List<String> particleSuffixes,
        List<String> seriesRequestTriggers,
        String seriesTitleStripPattern,
        List<String> animationTriggers,
        List<String> familyTriggers,
        List<OttSpacing> ottSpacing,
        List<GenreRule> genres,
        List<GenreSynonym> genreKeywordSynonyms,
        boolean tvKeywordMatchGenreName,
        LightMood lightMood,
        Map<String, List<ConflictRule>> conflicts,
        List<Network> networks) {

    public SearchDictionary {
        stopwords = nn(stopwords);
        protectedWordSuffixes = nn(protectedWordSuffixes);
        particleSuffixes = nn(particleSuffixes);
        seriesRequestTriggers = nn(seriesRequestTriggers);
        animationTriggers = nn(animationTriggers);
        familyTriggers = nn(familyTriggers);
        ottSpacing = nn(ottSpacing);
        genres = nn(genres);
        genreKeywordSynonyms = nn(genreKeywordSynonyms);
        networks = nn(networks);
        conflicts = conflicts == null ? Map.of() : Map.copyOf(conflicts);
        if (lightMood == null) lightMood = new LightMood(null, null, null);
    }

    /** 질의 표기 정리 규칙 하나 (정규식 → 치환) */
    public record OttSpacing(String pattern, String replacement) {}

    /** 장르 요청 규칙: triggers 중 하나가 질의에 있으면 작품 유형별 targets 장르를 요청한 것으로 본다 */
    public record GenreRule(String name, List<String> triggers, List<String> movie, List<String> tv) {
        public GenreRule {
            triggers = nn(triggers);
            movie = nn(movie);
            tv = nn(tv);
        }

        public List<String> targets(boolean isTv) {
            return isTv ? tv : movie;
        }
    }

    /** 요청 장르가 장르 필드에 없을 때 키워드에서 대신 찾는 동의어 */
    public record GenreSynonym(String genre, List<String> keywords, List<String> tvKeywords) {
        public GenreSynonym {
            keywords = nn(keywords);
            tvKeywords = nn(tvKeywords);
        }
    }

    /** "가볍고 따뜻한 분위기" 판단 조건 */
    public record LightMood(List<String> triggerGenres, List<String> triggers, List<String> movieExtraTriggers) {
        public LightMood {
            triggerGenres = nn(triggerGenres);
            triggers = nn(triggers);
            movieExtraTriggers = nn(movieExtraTriggers);
        }
    }

    /** 가벼운 분위기 요청에서 차단할 장르 규칙 */
    public record ConflictRule(List<String> blocked, List<String> unlessRequested, List<String> unlessCandidateHas) {
        public ConflictRule {
            blocked = nn(blocked);
            unlessRequested = nn(unlessRequested);
            unlessCandidateHas = nn(unlessCandidateHas);
        }
    }

    /** OTT/방송사 하나의 표기 사전 */
    public record Network(String id, List<String> queryTriggers, List<String> termTriggers, List<String> dataAliases) {
        public Network {
            queryTriggers = nn(queryTriggers);
            termTriggers = nn(termTriggers);
            dataAliases = nn(dataAliases);
        }
    }

    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
