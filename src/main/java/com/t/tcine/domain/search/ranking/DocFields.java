package com.t.tcine.domain.search.ranking;

import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Qdrant 문서의 메타데이터·본문에서 값을 꺼내는 작은 도우미 모음 */
public final class DocFields {

    private DocFields() {}

    public static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    public static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    public static double doubleOf(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /** "a, b, c" 형태의 값을 집합으로 */
    public static Set<String> splitTokens(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** 본문에서 "접두어: 값" 한 줄의 값을 꺼낸다 */
    public static String extractFieldFromContent(String content, String prefix) {
        if (content == null || content.isEmpty()) return "";
        for (String line : content.split("\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    /** 메타데이터에 없는 키워드·한줄소개를 본문에서 찾아 채운다 */
    public static void enrichMetadataFromContent(Document doc) {
        if (doc == null || doc.getText() == null) return;
        Map<String, Object> m = doc.getMetadata();
        if (!m.containsKey("keywords")) {
            String kw = extractFieldFromContent(doc.getText(), "키워드:");
            if (!kw.isEmpty()) m.put("keywords", kw);
        }
        if (!m.containsKey("tagline")) {
            String tl = extractFieldFromContent(doc.getText(), "한줄 소개:");
            if (!tl.isEmpty()) m.put("tagline", tl);
        }
    }
}
