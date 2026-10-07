package com.t.tcine.domain.search.util;

import org.springframework.ai.document.Document;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryAnalyzer {

    private static final Pattern SIMILAR_QUERY_PATTERN = Pattern.compile(
            "^(.+?)\\s*(?:와|과|이랑|랑|하고)?\\s*(?:비슷한|유사한|같은|닮은|느낌의|스타일의|풍의|결의)\\s*(?:분위기의|느낌의|장르의|스타일의|결의)?\\s*(?:영화|드라마|시리즈|예능|애니|애니메이션|작품|추천.*)?$");

    private static final Pattern CLASSIC_ERA_PATTERN = Pattern.compile(
            "(고전|옛날|명작|클래식|추억|흑백|19[5-9][0-9]|[5-9]0년대|응답하라)");

    private static final Set<String> QUERY_STOPWORDS = Set.of(
            "영화", "드라마", "시리즈", "예능", "애니", "애니메이션", "작품", "전부", "전체", "모두", "정주행", "몇편", "모음",
            "감독", "작가", "배우", "출연", "주연", "연출", "제작", "나오는", "나온", "출연한", "찍은",
            "추천", "추천해줘", "알려줘", "찾아줘", "볼만한", "재밌는", "재미있는", "좋은", "최고의",
            "인기", "인기있는", "유명한", "최신", "신작", "요즘", "순", "순위", "리스트", "목록", "오리지널", "독점", "방영",
            "비슷한", "유사한", "같은", "닮은", "느낌", "느낌의", "스타일", "스타일의", "분위기", "분위기의"
    );

    private static final List<String> PROTECTED_WORD_SUFFIXES = List.of(
            "플레이", "스토리", "미스터리", "판타지", "코미디", "패밀리", "다큐멘터리", "하모니", "심포니",
            "데이", "보이", "토이", "조이", "에세이", "멜로", "솔로", "히어로", "티비", "비디오"
    );

    public static String normalize(String query, int maxLength) {
        if (query == null) return "";
        String q = query.replaceAll("[\\r\\n\"]+", " ").trim();
        return q.length() > maxLength ? q.substring(0, maxLength) : q;
    }

    public static String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·]+", "");
    }

    public static boolean isSeriesRequest(String query) {
        if (!similarTargetTitle(query).isEmpty()) return false;
        String c = compact(query);
        return c.contains("시리즈") || c.contains("전부") || c.contains("전체")
                || c.contains("모두") || c.contains("정주행") || c.contains("몇편");
    }

    public static String seriesTitleQuery(String query) {
        return query.replaceAll("(?i)(시리즈|전부|전체|모두|정주행|[0-9]+\\s*편)", " ").replaceAll("\\s+", " ").trim();
    }

    public static String similarTargetTitle(String query) {
        if (query == null) return "";
        Matcher matcher = SIMILAR_QUERY_PATTERN.matcher(query.trim());
        if (!matcher.matches()) return "";
        String rawTarget = matcher.group(1).trim();
        String stripped = rawTarget.replaceFirst("(와|과|이랑|랑|하고)$", "").trim();
        return compact(stripped).length() >= 2 ? stripped : "";
    }

    public static boolean wantsClassic(String query) {
        return query != null && CLASSIC_ERA_PATTERN.matcher(query).find();
    }

    public static String normalizeOttSpacing(String query) {
        if (query == null || query.isEmpty()) return "";
        return query
                .replaceAll("(?i)쿠팡\\s+플레이", "쿠팡플레이")
                .replaceAll("(?i)디즈니\\s*(\\+|플러스)", "디즈니플러스")
                .replaceAll("(?i)애플\\s*(tv\\+?|티비\\+?)", "애플티비")
                .replaceAll("(?i)아마존\\s+프라임(\\s+비디오)?", "아마존프라임")
                .replaceAll("(?i)프라임\\s+비디오", "프라임비디오");
    }

    public static List<String> extractCoreTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalizedQuery = normalizeOttSpacing(query);
        String[] rawTokens = normalizedQuery.toLowerCase(Locale.ROOT).split("\\s+");
        List<String> core = new ArrayList<>();
        for (String raw : rawTokens) {
            String c = compact(raw);
            if (c.isEmpty() || QUERY_STOPWORDS.contains(c)) continue;
            String stripped = stripKoreanParticle(c);
            if (!stripped.isEmpty() && !QUERY_STOPWORDS.contains(stripped)) {
                core.add(stripped);
            }
        }
        if (core.isEmpty()) {
            String fallback = compact(normalizedQuery);
            return fallback.isEmpty() ? List.of() : List.of(fallback);
        }
        return core;
    }

    private static String stripKoreanParticle(String token) {
        if (token.length() <= 2) return token;
        for (String suffix : PROTECTED_WORD_SUFFIXES) {
            if (token.endsWith(suffix)) return token;
        }
        String stripped = token.replaceFirst("(이랑|으로|에서|하고| 같은|같은|은|는|이|가|을|를|의|에|로|와|과|랑|도|만)$", "");
        return stripped.length() >= 2 ? stripped : token;
    }

    public static Set<String> extractRequestedGenres(String query, boolean isTv) {
        if (query == null || query.isBlank()) return Set.of();
        String c = compact(query);
        Set<String> genres = new HashSet<>();
        if (c.contains("로맨스") || c.contains("멜로") || c.contains("로코") || c.contains("로맨틱")
                || c.contains("달달한") || c.contains("설레는") || c.contains("첫사랑") || c.contains("연애")) {
            genres.add("로맨스");
        }
        if (c.contains("스릴러") || c.contains("서스펜스")) {
            if (isTv) { genres.add("미스터리"); genres.add("범죄"); } else { genres.add("스릴러"); }
        }
        if (c.contains("공포") || c.contains("호러") || c.contains("무서운") || c.contains("오컬트") || c.contains("귀신")) {
            if (isTv) { genres.add("미스터리"); genres.add("범죄"); } else { genres.add("공포"); }
        }
        if (c.contains("코미디") || c.contains("웃긴") || c.contains("유쾌한") || c.contains("코믹") || c.contains("시트콤")) {
            genres.add("코미디");
        }
        if (c.contains("액션") || c.contains("격투") || c.contains("첩보") || c.contains("블록버스터")) {
            genres.add("액션");
            if (isTv) genres.add("action");
        }
        if (c.contains("sf") || c.contains("공상과학") || c.contains("우주") || c.contains("타임루프")) {
            genres.add("sf");
            if (isTv) genres.add("scifi");
        }
        if (c.contains("판타지") || c.contains("마법")) {
            genres.add("판타지");
            if (isTv) { genres.add("fantasy"); genres.add("scifi"); }
        }
        if (c.contains("범죄") || c.contains("느와르") || c.contains("형사") || c.contains("마피아") || c.contains("수사") || c.contains("추리")) {
            genres.add("범죄");
            if (isTv) genres.add("미스터리");
        }
        if (c.contains("미스터리") || c.contains("추리")) {
            genres.add("미스터리");
        }
        if (c.contains("애니") || c.contains("만화")) {
            genres.add("애니메이션");
        }
        if (c.contains("전쟁") || c.contains("군대") || c.contains("전투")) {
            genres.add("전쟁");
        }
        if (c.contains("역사") || c.contains("사극") || c.contains("시대극")) {
            genres.add("역사");
        }
        if (c.contains("음악") || c.contains("뮤지컬") || c.contains("밴드")) {
            genres.add("음악");
        }
        if (c.contains("가족") || c.contains("어린이")) {
            genres.add("가족");
        }
        if (c.contains("다큐")) {
            genres.add("다큐멘터리");
        }
        if (isTv && (c.contains("예능") || c.contains("리얼리티") || c.contains("토크쇼"))) {
            genres.add("reality");
            genres.add("talk");
            genres.add("예능");
        }
        return genres;
    }

    public static Set<String> extractRequestedNetworks(String query) {
        if (query == null || query.isBlank()) return Set.of();
        String c = compact(query);
        Set<String> nets = new HashSet<>();
        if (c.contains("쿠팡") || c.contains("coupang")) nets.add("coupang");
        if (c.contains("넷플릭스") || c.contains("넷플") || c.contains("netflix")) nets.add("netflix");
        if (c.contains("디즈니") || c.contains("disney")) nets.add("disney");
        if (c.contains("애플티비") || c.contains("애플tv") || c.contains("appletv")) nets.add("apple");
        if (c.contains("티빙") || c.contains("tving")) nets.add("tving");
        if (c.contains("웨이브") || c.contains("wavve")) nets.add("wavve");
        if (c.contains("왓챠") || c.contains("watcha")) nets.add("watcha");
        if (c.contains("티비엔") || c.contains("tvn")) nets.add("tvn");
        if (c.contains("제이티비씨") || c.contains("jtbc")) nets.add("jtbc");
        if (c.contains("에스비에스") || c.contains("sbs")) nets.add("sbs");
        if (c.contains("케이비에스") || c.contains("kbs")) nets.add("kbs");
        if (c.contains("엠비씨") || c.contains("mbc")) nets.add("mbc");
        if (c.contains("ena")) nets.add("ena");
        if (c.contains("오씨엔") || c.contains("ocn")) nets.add("ocn");
        return nets;
    }

}
