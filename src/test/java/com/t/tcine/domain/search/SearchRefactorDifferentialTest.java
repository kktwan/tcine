package com.t.tcine.domain.search;

import com.t.tcine.domain.search.config.RankingProperties;
import com.t.tcine.domain.search.config.SearchDictionary;
import com.t.tcine.domain.search.legacy.LegacyQueryAnalyzer;
import com.t.tcine.domain.search.legacy.LegacyRankingEngine;
import com.t.tcine.domain.search.query.QueryAnalyzer;
import com.t.tcine.domain.search.ranking.RankingEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 리팩터링 전(Legacy)·후 검색 로직이 같은 결과를 내는지 비교한다.
 * Legacy 클래스는 리팩터링 직전 코드의 복사본이다.
 */
class SearchRefactorDifferentialTest {

    private static QueryAnalyzer analyzer;
    private static RankingEngine engine;

    private static final List<String> QUERIES = List.of(
            "달달한 로맨스 영화 추천", "설레는 로코", "힐링되는 가족 영화", "잔잔한 영화", "따뜻한 드라마",
            "무서운 공포 영화", "스릴러 영화 추천해줘", "범죄 느와르", "마피아 영화", "추리 미스터리",
            "SF 우주 영화", "타임루프 영화", "판타지 마법 영화", "코미디 웃긴 영화", "액션 블록버스터",
            "애니메이션 추천", "만화 원작", "전쟁 영화", "사극 시대극", "음악 뮤지컬 밴드", "다큐 추천", "예능 리얼리티", "토크쇼",
            "넷플릭스 드라마", "넷플 로맨스", "디즈니 플러스 애니", "디즈니+ 영화", "애플 TV+ 시리즈", "애플티비", "티빙 예능",
            "웨이브 드라마", "쿠팡 플레이 시리즈", "쿠팡플레이", "왓챠 영화", "티비엔 드라마", "tvN 로맨스", "JTBC 드라마",
            "SBS 드라마", "KBS 사극", "MBC 예능", "ENA 드라마", "OCN 스릴러", "아마존 프라임 비디오", "프라임 비디오 영화",
            "토이스토리", "토이스토리 시리즈 전부", "해리포터 시리즈", "겨울왕국과 비슷한 영화", "기생충 같은 영화", "오징어게임과 비슷한 드라마",
            "인터스텔라 느낌의 SF", "봉준호 감독", "송강호 나오는 영화", "박찬욱 영화", "이병헌 출연한 드라마", "크리스토퍼 놀란",
            "옛날 명작 영화", "고전 로맨스", "90년대 영화", "응답하라 시리즈", "흑백 영화", "추억의 애니메이션",
            "가족과 함께 볼만한 애니", "어린이 애니메이션", "달달한 가족 영화", "힐링 로맨스 드라마", "설레는 첫사랑 영화",
            "무서운 범죄 스릴러", "전쟁 로맨스", "역사 로맨스 사극", "공포 코미디", "미스터리 스릴러 드라마",
            "", "   ", "영화 추천", "재밌는 영화", "최신 인기 드라마", "미션 임파서블", "스파이더맨", "어벤져스 시리즈 전체",
            "로맨스는 영화로", "친구와 함께 볼만한 코미디", "한국 드라마 정주행", "넷플릭스 오리지널 스릴러", "디즈니 플러스 가족 영화",
            "공포 영화 3편", "이터널 선샤인과 비슷한 영화", "라라랜드랑 비슷한 느낌의 영화"
    );

    @BeforeAll
    static void setUp() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("search-dictionary", new ClassPathResource("search-dictionary.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addFirst(s));
        SearchDictionary dict = Binder.get(env).bind("search.dictionary", Bindable.of(SearchDictionary.class)).get();
        analyzer = new QueryAnalyzer(dict);
        engine = new RankingEngine(analyzer, dict, new RankingProperties());
    }

    @Test
    void queryAnalysisMatchesLegacy() {
        for (String q : QUERIES) {
            String ctx = "query=[" + q + "]";
            assertCoreTerms(q, ctx);
            assertEquals(LegacyQueryAnalyzer.normalizeOttSpacing(q), analyzer.normalizeOttSpacing(q), ctx);
            assertEquals(LegacyQueryAnalyzer.isSeriesRequest(q), analyzer.isSeriesRequest(q), ctx);
            assertEquals(LegacyQueryAnalyzer.similarTargetTitle(q), analyzer.similarTargetTitle(q), ctx);
            assertEquals(LegacyQueryAnalyzer.wantsClassic(q), analyzer.wantsClassic(q), ctx);
            assertEquals(LegacyQueryAnalyzer.extractRequestedNetworks(q), analyzer.extractRequestedNetworks(q), ctx);
            if (!q.isBlank()) {
                assertEquals(LegacyQueryAnalyzer.seriesTitleQuery(q), analyzer.seriesTitleQuery(q), ctx);
            }
            for (MediaKind kind : MediaKind.values()) {
                assertEquals(LegacyQueryAnalyzer.extractRequestedGenres(q, kind.isTv()),
                        analyzer.extractRequestedGenres(q, kind), ctx + " kind=" + kind);
            }
            assertEquals(q != null && (q.contains("애니") || q.contains("만화")), analyzer.wantsAnimation(q), ctx);
            assertEquals(q.contains("가족") || q.contains("어린이"), analyzer.wantsFamily(q), ctx);
        }
        assertEquals(LegacyQueryAnalyzer.extractCoreTerms(null), analyzer.extractCoreTerms(null));
        assertEquals(LegacyQueryAnalyzer.extractRequestedGenres(null, true), analyzer.extractRequestedGenres(null, MediaKind.TV));
    }

    @Test
    void keywordAndMatchingRulesMatchLegacy() {
        for (MediaKind kind : MediaKind.values()) {
            boolean isTv = kind.isTv();
            for (String q : QUERIES) {
                Set<String> genres = LegacyQueryAnalyzer.extractRequestedGenres(q, isTv);
                Set<String> networks = LegacyQueryAnalyzer.extractRequestedNetworks(q);
                for (int i = 0; i < SAMPLES; i++) {
                    String ctx = "kind=" + kind + " query=[" + q + "] doc=" + i;
                    Document d = doc(i);
                    if (sameCoreTerms(q)) {
                        assertEquals(LegacyRankingEngine.keywordScore(d, q, isTv), engine.keywordScore(d, q, kind), ctx + " keywordScore");
                    }
                    assertEquals(LegacyRankingEngine.matchesRequestedGenres(d, genres, isTv),
                            engine.matchesRequestedGenres(d, genres, kind), ctx + " matchesRequestedGenres");
                    assertEquals(LegacyRankingEngine.hasConflictingGenre(d, q, genres, isTv),
                            engine.hasConflictingGenre(d, q, genres, kind), ctx + " hasConflictingGenre");
                    assertEquals(LegacyRankingEngine.matchesRequestedNetworks(d, networks),
                            engine.matchesRequestedNetworks(d, networks), ctx + " matchesRequestedNetworks");
                }
            }
        }
    }

    @Test
    void boostedScoreMatchesLegacy() {
        for (MediaKind kind : MediaKind.values()) {
            boolean isTv = kind.isTv();
            for (String q : QUERIES) {
                Set<String> genres = LegacyQueryAnalyzer.extractRequestedGenres(q, isTv);
                Set<String> networks = LegacyQueryAnalyzer.extractRequestedNetworks(q);
                for (int i = 0; i < SAMPLES; i++) {
                    // 기준 작품이 없는 경우와, 모든 샘플을 기준 작품으로 삼는 경우
                    assertScore(q, kind, genres, networks, doc(i), null, i, -1);
                    for (int r = 0; r < SAMPLES; r += 3) {
                        assertScore(q, kind, genres, networks, doc(i), doc(r), i, r);
                    }
                }
            }
        }
    }

    private static void assertScore(String q, MediaKind kind, Set<String> genres, Set<String> networks,
                                    Document candidate, Document ref, int i, int r) {
        // boostedScore 는 hybrid/vector 점수를 읽으므로 양쪽에 같은 값을 넣어 준다
        Document c1 = withScores(candidate, i);
        Document c2 = withScores(candidate, i);
        double expected = LegacyRankingEngine.boostedScore(c1, q, ref, genres, networks, kind.isTv());
        double actual = engine.boostedScore(c2, q, ref, genres, networks, kind);
        assertEquals(expected, actual, 1e-9, "kind=" + kind + " query=[" + q + "] doc=" + i + " ref=" + r);
    }

    @Test
    void mergeAndRankMatchesLegacy() {
        for (MediaKind kind : MediaKind.values()) {
            for (String q : QUERIES) {
                // 시리즈에서 OTT 를 요청한 질의는 고유명사 보너스에서 OTT 를 뺐으므로(의도된 변경) 레거시와 순서가 다를 수 있다
                if (kind.isTv() && !LegacyQueryAnalyzer.extractRequestedNetworks(q).isEmpty()) continue;
                // 장르·시대 조건어를 핵심어에서 뺀 질의(의도된 변경)는 제목 일치 점수가 달라서 순서 비교에서 뺀다
                if (!sameCoreTerms(q)) continue;
                List<Document> kwOld = keywordHits();
                List<Document> vecOld = vectorHits();
                List<Document> kwNew = keywordHits();
                List<Document> vecNew = vectorHits();
                for (boolean runKeyword : new boolean[]{true, false}) {
                    List<Document> expected = LegacyRankingEngine.mergeAndRank(kwOld, vecOld, q, runKeyword, 20, kind.isTv());
                    List<Document> actual = engine.mergeAndRank(kwNew, vecNew, q, runKeyword, 20, kind);
                    assertEquals(expected.stream().map(Document::getId).toList(),
                            actual.stream().map(Document::getId).toList(), "kind=" + kind + " query=[" + q + "] run=" + runKeyword);
                    for (int i = 0; i < expected.size(); i++) {
                        assertEquals(expected.get(i).getMetadata().get("hybrid_score"),
                                actual.get(i).getMetadata().get("hybrid_score"));
                    }
                }
            }
        }
    }

    /** 레거시와 핵심어가 같은 질의인지 (장르·시대 조건어를 뺀 질의는 의도적으로 달라진다) */
    private static boolean sameCoreTerms(String q) {
        return LegacyQueryAnalyzer.extractCoreTerms(q).equals(analyzer.extractCoreTerms(q));
    }

    /**
     * 핵심어는 레거시와 같거나, 장르·시대 조건어만 뺀 결과여야 한다
     * (새 핵심어는 레거시 핵심어의 부분집합이고, 조건어만 있던 질의는 빈 목록이다).
     */
    private static void assertCoreTerms(String q, String ctx) {
        List<String> legacy = LegacyQueryAnalyzer.extractCoreTerms(q);
        List<String> now = analyzer.extractCoreTerms(q);
        if (legacy.equals(now)) return;
        assertTrue(legacy.containsAll(now), ctx + " 새 핵심어 " + now + " 는 레거시 " + legacy + " 의 부분집합이어야 함");
        assertTrue(now.size() < legacy.size(), ctx + " 달라졌다면 조건어를 뺀 것이어야 함");
    }

    @Test
    void intentWordsAreNotSearchTerms() {
        assertEquals(List.of(), analyzer.extractCoreTerms("옛날 명작 영화"));
        assertEquals(List.of(), analyzer.extractCoreTerms("로맨스 영화"));
        assertEquals(List.of(), analyzer.extractCoreTerms("달달한 로맨스 영화 추천"));
        assertEquals(List.of("기생충"), analyzer.extractCoreTerms("기생충 로맨스"));
        // 실제 제목에 쓰이는 말은 그대로 검색어로 남는다
        assertEquals(List.of("응답하라"), analyzer.extractCoreTerms("응답하라 시리즈"));
        assertEquals(List.of("반지의제왕"), analyzer.extractCoreTerms("반지의제왕"));
    }

    // ───────────────────────── 샘플 데이터 ─────────────────────────

    private static final int SAMPLES = 24;

    private static List<Document> keywordHits() {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i += 2) docs.add(doc(i));
        return docs;
    }

    private static List<Document> vectorHits() {
        List<Document> docs = new ArrayList<>();
        for (int i = SAMPLES - 1; i >= 0; i -= 3) {
            Document d = doc(i);
            docs.add(Document.builder().id(d.getId()).text(d.getText()).metadata(new HashMap<>(d.getMetadata()))
                    .score(0.9 - i * 0.02).build());
        }
        return docs;
    }

    private static Document withScores(Document d, int i) {
        Map<String, Object> m = new HashMap<>(d.getMetadata());
        if (i % 2 == 0) m.put("hybrid_score", 0.02 + i * 0.001);
        if (i % 3 == 0) m.put("vector_score", 0.5 + i * 0.01);
        return Document.builder().id(d.getId()).text(d.getText()).metadata(m)
                .score(i % 4 == 1 ? 0.7 : null).build();
    }

    private static final String[][] CORPUS = {
            // title, originalTitle, genres, keywords, director/creator, cast, networks, year, rating, overview
            {"토이 스토리", "Toy Story", "애니메이션, 가족, 코미디", "장난감, 우정, 모험", "존 래시터", "톰 행크스, 팀 알렌", "", "1995", "8.0", "장난감들의 모험"},
            {"겨울왕국", "Frozen", "애니메이션, 가족, 판타지", "자매, 마법, 얼음", "크리스 벅", "이디나 멘젤", "", "2013", "7.2", "마법을 가진 자매 이야기"},
            {"기생충", "Parasite", "코미디, 스릴러, 드라마", "계급, 가족, 반지하", "봉준호", "송강호, 이선균", "", "2019", "8.5", "두 가족의 만남"},
            {"인터스텔라", "Interstellar", "SF, 드라마, 모험", "우주, 블랙홀, 시간", "크리스토퍼 놀란", "매튜 맥커너히", "", "2014", "8.4", "우주를 건너는 아버지"},
            {"라라랜드", "La La Land", "로맨스, 코미디, 드라마, 음악", "재즈, 사랑, 꿈", "데이미언 셔젤", "라이언 고슬링, 엠마 스톤", "", "2016", "7.9", "꿈과 사랑 이야기"},
            {"이터널 선샤인", "Eternal Sunshine of the Spotless Mind", "로맨스, SF, 드라마", "기억, 연애, 이별", "미셸 공드리", "짐 캐리", "", "2004", "8.1", "기억을 지우는 연인"},
            {"로즈", "Titanic", "드라마, 역사, 전쟁", "배, 전쟁, 사랑", "누구", "배우", "", "1997", "7.9", "역사 속 이야기"},
            {"올드보이", "Oldboy", "범죄, 스릴러, 미스터리", "복수, 감금", "박찬욱", "최민식, 유지태", "", "2003", "8.2", "15년 감금"},
            {"괴물", "The Host", "공포, SF, 드라마", "괴수, 한강", "봉준호", "송강호", "", "2006", "7.0", "한강의 괴물"},
            {"어벤져스", "The Avengers", "액션, SF, 모험", "히어로, 마블", "조스 웨던", "로버트 다우니 주니어", "", "2012", "7.7", "히어로 집합"},
            {"클래식", "The Classic", "로맨스, 드라마", "첫사랑, 편지", "곽재용", "손예진, 조승우", "", "2003", "7.5", "첫사랑 이야기"},
            {"다큐 지구", "Earth", "다큐멘터리", "자연", "앨러스테어", "나레이션", "", "2007", "7.6", "지구의 자연"},
            {"오징어 게임", "Squid Game", "액션 & 어드벤처, 미스터리, 드라마", "서바이벌, 게임", "황동혁", "이정재, 박해수", "Netflix", "2021", "8.0", "목숨을 건 게임"},
            {"사랑의 불시착", "Crash Landing on You", "로맨스, 코미디, 드라마", "북한, 사랑, 로맨틱", "이정효", "현빈, 손예진", "tvN, Netflix", "2019", "8.6", "북한에 불시착"},
            {"더 글로리", "The Glory", "드라마, 범죄, 미스터리", "복수", "안길호", "송혜교", "Netflix", "2022", "8.1", "복수극"},
            {"응답하라 1988", "Reply 1988", "드라마, 코미디, 가족", "추억, 가족, 달달", "신원호", "혜리, 박보검", "tvN", "2015", "8.9", "쌍문동 이웃"},
            {"무빙", "Moving", "SF & 판타지, 액션 & 어드벤처, 드라마", "초능력, scifi", "박인제", "류승룡, 한효주", "Disney+", "2023", "8.5", "초능력 가족"},
            {"서울의 봄", "Seoul Spring", "드라마, 역사, 전쟁", "군대, 쿠데타", "김성수", "황정민", "", "2023", "8.0", "그날의 이야기"},
            {"나 혼자 산다", "I Live Alone", "리얼리티, 예능, talk", "일상", "연출자", "전현무", "MBC, 웨이브", "2013", "7.0", "혼자 사는 사람들"},
            {"뿌리깊은 나무", "Tree With Deep Roots", "드라마, 역사, 사극", "세종", "장태유", "한석규", "SBS", "2011", "8.2", "세종 이야기"},
            {"해피투게더", "Happy Together", "로맨스, 드라마", "연애, 멜로", "왕가위", "장국영", "", "1997", "7.8", "연인의 여행"},
            {"조용한 가족", "The Quiet Family", "코미디, 공포, 가족", "산장, 잔잔", "김지운", "최민식", "", "1998", "5.5", "산장 이야기"},
            {"쿠팡 코미디", "Coupang Comedy", "코미디", "웃긴", "작가", "배우", "쿠팡 플레이", "2022", "0", "쿠팡플레이 오리지널"},
            {"무제", "Untitled", "", "", "", "", "", "0", "0", ""},
    };

    /** CORPUS 의 i 번째 문서. 짝수 번호는 본문의 "키워드:" 줄만 있고 메타데이터 keywords 는 없다 */
    private static Document doc(int i) {
        String[] c = CORPUS[i % CORPUS.length];
        Map<String, Object> m = new HashMap<>();
        m.put("title", c[0]);
        m.put("originalTitle", c[1]);
        m.put("genres", c[2]);
        m.put("director", c[4]);
        m.put("creator", c[4]);
        m.put("cast", c[5]);
        m.put("networks", c[6]);
        m.put("year", Integer.parseInt(c[7]));
        m.put("rating", Double.parseDouble(c[8]));
        m.put("overview", c[9]);
        String text = "제목: " + c[0] + "\n키워드: " + c[3] + "\n한줄 소개: " + c[0] + " 이야기\n줄거리: " + c[9];
        if (i % 2 == 1) m.put("keywords", c[3]);
        return new Document("doc-" + i, text, m);
    }
}
