package com.t.tcine.domain.search;

import com.t.tcine.domain.movie.service.MovieRecommendService;
import com.t.tcine.domain.tv.service.TvRecommendService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 실제 검색(임베딩 + Qdrant + Gemini)을 돌려 평가 세트(search-eval/cases.yml)의 통과율을 보여 주는 도구.
 * OpenAI/Gemini 를 실제로 호출하므로 평소 빌드에서는 건너뛰고, RUN_SEARCH_EVAL=true 일 때만 실행한다.
 *
 * <pre>
 * RUN_SEARCH_EVAL=true ./gradlew.bat test --tests "*SearchEvalTest*" --no-daemon -i
 * </pre>
 *
 * 결과는 콘솔과 build/search-eval-report.txt 에 남는다. 케이스가 하나라도 실패하면 테스트를 실패시키려면
 * EVAL_STRICT=true 를 함께 준다.
 */
@SpringBootTest(properties = "movie.daily-limit=100000")
@EnabledIfEnvironmentVariable(named = "RUN_SEARCH_EVAL", matches = "true")
class SearchEvalTest {

    @Autowired MovieRecommendService movieService;
    @Autowired TvRecommendService tvService;

    @Test
    @SuppressWarnings("unchecked")
    void runEvalCases() throws Exception {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource("search-eval/cases.yml").getInputStream()) {
            root = new Yaml().load(in);
        }
        List<Map<String, Object>> cases = (List<Map<String, Object>>) root.get("cases");

        List<String> lines = new ArrayList<>();
        int passed = 0;
        int skipped = 0;
        int n = 0;
        for (Map<String, Object> c : cases) {
            String id = (String) c.get("id");
            String kind = (String) c.get("kind");
            String query = (String) c.get("query");
            if ("prod".equals(c.get("scope")) && !"prod".equals(System.getenv("EVAL_ENV"))) {
                skipped++;
                lines.add("SKIP " + id + "  [" + kind + "] " + query + "  (운영 색인 전용 케이스, EVAL_ENV=prod 로 실행)");
                continue;
            }
            List<Map<String, String>> cards = search(kind, query, "eval-" + (n++));
            List<String> failures = check((Map<String, Object>) c.getOrDefault("checks", Map.of()), cards);

            boolean ok = failures.isEmpty();
            if (ok) passed++;
            lines.add((ok ? "PASS " : "FAIL ") + id + "  [" + kind + "] " + query + "  → " + cards.size() + "편");
            lines.add("      점수: " + probe(kind, query));
            if (Boolean.TRUE.equals(c.get("explain"))) lines.addAll(explain(kind, query));
            lines.add("      결과: " + cards.stream().limit(8).map(m -> m.get("title") + "(" + m.get("year") + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("(없음)"));
            failures.forEach(f -> lines.add("      ✗ " + f));
        }
        lines.add(0, "통과 " + passed + " / " + (cases.size() - skipped) + (skipped > 0 ? "  (건너뜀 " + skipped + ")" : ""));
        lines.add(1, "");

        String report = String.join("\n", lines);
        System.out.println("\n===== 검색 평가 결과 =====\n" + report + "\n==========================\n");
        Files.writeString(Path.of("build", "search-eval-report.txt"), report, StandardCharsets.UTF_8);

        if ("true".equals(System.getenv("EVAL_STRICT")) && passed < cases.size() - skipped) {
            fail("검색 평가 실패 케이스가 있어요 (" + passed + "/" + (cases.size() - skipped) + ")");
        }
    }

    /** 후보별 점수 내역 표 (yml 에서 explain: true 로 켠 케이스만) */
    private List<String> explain(String kind, String query) {
        List<String> out = new ArrayList<>();
        try {
            var rows = "tv".equals(kind) ? tvService.explain(query, 15) : movieService.explain(query, 15);
            out.add("      후보 점수 (의미=RRF·벡터, 가점=장르·최신작·평점 등):");
            int rank = 1;
            for (var r : rows) {
                out.add(String.format("        %2d. 합계 %.3f = 의미 %.3f + 가점 %+.3f | 벡터 %.3f | %s(%d) [%s]",
                        rank++, r.total(), r.similarity(), r.bonus(), r.vector(), r.title(), r.year(), r.genres()));
            }
        } catch (Exception e) {
            out.add("      후보 점수 진단 실패: " + e.getMessage());
        }
        return out;
    }

    /** 검색 단계의 점수 분포 한 줄 요약 (관련도 하한을 정할 때 본다) */
    private String probe(String kind, String query) {
        try {
            var p = "tv".equals(kind) ? tvService.probe(query) : movieService.probe(query);
            List<Double> v = p.vectorScores();
            String vec = v.isEmpty() ? "벡터 없음" : String.format("벡터 1위 %.3f / 5위 %.3f / 20위 %.3f / 최하 %.3f",
                    v.get(0), v.get(Math.min(4, v.size() - 1)), v.get(Math.min(19, v.size() - 1)), v.get(v.size() - 1));
            return vec + " | 키워드 일치 " + p.keywordMatches() + "편(최고 " + p.topKeywordScore() + "점)"
                    + (p.referenceFound() ? " | 기준작품 찾음" : "");
        } catch (Exception e) {
            return "진단 실패: " + e.getMessage();
        }
    }

    private List<Map<String, String>> search(String kind, String query, String user) throws Exception {
        Object result = "tv".equals(kind) ? tvService.recommend(user, query) : movieService.recommend(user, query);
        Object cards = result.getClass().getMethod("cards").invoke(result);
        List<Map<String, String>> out = new ArrayList<>();
        if (cards instanceof List<?> list) {
            for (Object card : list) out.add(toMap(card));
        }
        return out;
    }

    /** 카드(record)의 모든 값을 문자열 맵으로 */
    private static Map<String, String> toMap(Object card) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        StringBuilder all = new StringBuilder();
        for (RecordComponent rc : card.getClass().getRecordComponents()) {
            Object v = rc.getAccessor().invoke(card);
            String s = v == null ? "" : v.toString();
            m.put(rc.getName(), s);
            all.append(s).append(' ');
        }
        m.put("text", all.toString());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<String> check(Map<String, Object> checks, List<Map<String, String>> cards) {
        List<String> failures = new ArrayList<>();

        if (checks.get("minCards") instanceof Number min && cards.size() < min.intValue()) {
            failures.add("결과가 " + min + "편 이상이어야 하는데 " + cards.size() + "편");
        }
        if (Boolean.TRUE.equals(checks.get("expectNoCards")) && !cards.isEmpty()) {
            failures.add("결과가 없어야 하는데 " + cards.size() + "편 나옴");
        }
        if (checks.get("first") instanceof Map<?, ?> first) {
            String field = (String) first.get("field");
            String contains = (String) first.get("contains");
            if (cards.isEmpty() || !has(cards.get(0), field, contains)) {
                failures.add("첫 번째 결과의 " + field + " 에 '" + contains + "' 가 있어야 함");
            }
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) checks.getOrDefault("require", List.of())) {
            String field = (String) r.get("field");
            List<String> needles = needles(r);
            if (cards.stream().noneMatch(card -> hasAny(card, field, needles))) {
                failures.add("결과 중 " + field + " 에 " + needles + " 가 있는 작품이 없음");
            }
        }
        for (Map<String, String> f : (List<Map<String, String>>) checks.getOrDefault("forbid", List.of())) {
            List<String> hit = cards.stream().filter(card -> has(card, f.get("field"), f.get("contains")))
                    .map(card -> card.get("title")).toList();
            if (!hit.isEmpty()) failures.add(f.get("field") + " 에 '" + f.get("contains") + "' 가 있으면 안 되는데 " + hit);
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) checks.getOrDefault("ratios", List.of())) {
            List<String> any = ((List<Object>) r.get("containsAny")).stream().map(String::valueOf).toList();
            double min = ((Number) r.get("min")).doubleValue();
            String field = (String) r.get("field");
            long ok = cards.stream().filter(card -> hasAny(card, field, any)).count();
            double ratio = cards.isEmpty() ? 0 : (double) ok / cards.size();
            if (ratio + 1e-9 < min) {
                failures.add(String.format("%s 에 %s 가 있는 비율 %.0f%% (기준 %.0f%% 이상)", field, any, ratio * 100, min * 100));
            }
        }
        return failures;
    }

    /** contains 또는 containsAny 로 적은 찾을 단어들 */
    @SuppressWarnings("unchecked")
    private static List<String> needles(Map<String, Object> rule) {
        List<String> out = new ArrayList<>();
        if (rule.get("contains") != null) out.add(String.valueOf(rule.get("contains")));
        if (rule.get("containsAny") instanceof List<?> list) list.forEach(o -> out.add(String.valueOf(o)));
        return out;
    }

    private static boolean has(Map<String, String> card, String field, String needle) {
        return card.getOrDefault(field, "").toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean hasAny(Map<String, String> card, String field, List<String> needles) {
        return needles.stream().anyMatch(n -> !n.isEmpty() && has(card, field, n));
    }
}
