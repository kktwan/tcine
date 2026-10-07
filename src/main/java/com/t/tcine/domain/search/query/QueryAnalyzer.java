package com.t.tcine.domain.search.query;

import com.t.tcine.domain.search.MediaKind;
import com.t.tcine.domain.search.config.SearchDictionary;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 검색어를 해석한다: 핵심어 추출, "OO와 비슷한" 기준 작품, 요청 장르·OTT 인식 등.
 * 어휘와 규칙은 모두 {@link SearchDictionary}(search-dictionary.yml)에서 온다.
 */
@Component
public class QueryAnalyzer {

    private static final Pattern SIMILAR_TARGET_SUFFIX = Pattern.compile("(와|과|이랑|랑|하고)$");

    private final SearchDictionary dict;
    private final Pattern similarQueryPattern;
    private final Pattern classicEraPattern;
    private final Pattern seriesTitleStripPattern;
    private final Pattern particlePattern;
    private final List<Pattern> ottSpacingPatterns;
    private final Set<String> stopwords;

    public QueryAnalyzer(SearchDictionary dict) {
        this.dict = dict;
        this.similarQueryPattern = Pattern.compile(dict.similarQueryPattern());
        this.classicEraPattern = Pattern.compile(dict.classicEraPattern());
        this.seriesTitleStripPattern = Pattern.compile(dict.seriesTitleStripPattern());
        this.particlePattern = Pattern.compile("(" + String.join("|", dict.particleSuffixes()) + ")$");
        this.ottSpacingPatterns = dict.ottSpacing().stream().map(r -> Pattern.compile(r.pattern())).toList();
        this.stopwords = Set.copyOf(dict.stopwords());
    }

    public static String normalize(String query, int maxLength) {
        if (query == null) return "";
        String q = query.replaceAll("[\\r\\n\"]+", " ").trim();
        return q.length() > maxLength ? q.substring(0, maxLength) : q;
    }

    public static String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·]+", "");
    }

    public boolean isSeriesRequest(String query) {
        if (!similarTargetTitle(query).isEmpty()) return false;
        return containsAny(compact(query), dict.seriesRequestTriggers());
    }

    public String seriesTitleQuery(String query) {
        return seriesTitleStripPattern.matcher(query).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    public String similarTargetTitle(String query) {
        if (query == null) return "";
        Matcher matcher = similarQueryPattern.matcher(query.trim());
        if (!matcher.matches()) return "";
        String rawTarget = matcher.group(1).trim();
        String stripped = SIMILAR_TARGET_SUFFIX.matcher(rawTarget).replaceFirst("").trim();
        return compact(stripped).length() >= 2 ? stripped : "";
    }

    public boolean wantsClassic(String query) {
        return query != null && classicEraPattern.matcher(query).find();
    }

    public String normalizeOttSpacing(String query) {
        if (query == null || query.isEmpty()) return "";
        String result = query;
        for (int i = 0; i < ottSpacingPatterns.size(); i++) {
            result = ottSpacingPatterns.get(i).matcher(result).replaceAll(dict.ottSpacing().get(i).replacement());
        }
        return result;
    }

    public List<String> extractCoreTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalizedQuery = normalizeOttSpacing(query);
        String[] rawTokens = normalizedQuery.toLowerCase(Locale.ROOT).split("\\s+");
        List<String> core = new ArrayList<>();
        for (String raw : rawTokens) {
            String c = compact(raw);
            if (c.isEmpty() || stopwords.contains(c)) continue;
            String stripped = stripKoreanParticle(c);
            if (!stripped.isEmpty() && !stopwords.contains(stripped)) {
                core.add(stripped);
            }
        }
        if (core.isEmpty()) {
            String fallback = compact(normalizedQuery);
            return fallback.isEmpty() ? List.of() : List.of(fallback);
        }
        return core;
    }

    private String stripKoreanParticle(String token) {
        if (token.length() <= 2) return token;
        for (String suffix : dict.protectedWordSuffixes()) {
            if (token.endsWith(suffix)) return token;
        }
        String stripped = particlePattern.matcher(token).replaceFirst("");
        return stripped.length() >= 2 ? stripped : token;
    }

    public Set<String> extractRequestedGenres(String query, MediaKind kind) {
        if (query == null || query.isBlank()) return Set.of();
        String c = compact(query);
        Set<String> genres = new HashSet<>();
        for (SearchDictionary.GenreRule rule : dict.genres()) {
            if (containsAny(c, rule.triggers())) genres.addAll(rule.targets(kind.isTv()));
        }
        return genres;
    }

    public Set<String> extractRequestedNetworks(String query) {
        if (query == null || query.isBlank()) return Set.of();
        String c = compact(query);
        Set<String> nets = new HashSet<>();
        for (SearchDictionary.Network network : dict.networks()) {
            if (containsAny(c, network.queryTriggers())) nets.add(network.id());
        }
        return nets;
    }

    /** 질의(원문)에 애니메이션을 직접 요청하는 표현이 있는지 */
    public boolean wantsAnimation(String query) {
        return query != null && containsAnyRaw(query, dict.animationTriggers());
    }

    /** 질의(원문)에 가족/어린이 요청 표현이 있는지 */
    public boolean wantsFamily(String query) {
        return query != null && containsAnyRaw(query, dict.familyTriggers());
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private static boolean containsAnyRaw(String text, List<String> needles) {
        return containsAny(text, needles);
    }
}
