package com.t.tcine.domain.search.legacy;

// 리팩터링 전 구현을 그대로 보관한 기준(reference) 구현. 새 구현과 결과가 같은지 비교하는 차등 테스트에서만 쓴다.

import org.springframework.ai.document.Document;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class LegacyRankingEngine {

    public static int keywordScore(Document document, String query, boolean isTv) {
        Map<String, Object> metadata = document.getMetadata();
        List<String> terms = LegacyQueryAnalyzer.extractCoreTerms(query);
        Set<String> requestedNetworks = isTv ? LegacyQueryAnalyzer.extractRequestedNetworks(query) : Set.of();
        
        if (terms.isEmpty() && requestedNetworks.isEmpty()) return 0;
        
        String fullCore = String.join("", terms);
        String title = LegacyQueryAnalyzer.compact(str(metadata.get("title")));
        String originalTitle = LegacyQueryAnalyzer.compact(str(metadata.get("originalTitle")));
        String cast = LegacyQueryAnalyzer.compact(str(metadata.get("cast")));
        String keywords = LegacyQueryAnalyzer.compact(str(metadata.getOrDefault("keywords", extractFieldFromContent(document.getText(), "키워드:"))));
        String genres = LegacyQueryAnalyzer.compact(str(metadata.get("genres")));
        String overview = LegacyQueryAnalyzer.compact(str(metadata.get("overview")));
        
        String creatorOrDirector = LegacyQueryAnalyzer.compact(str(metadata.get(isTv ? "creator" : "director")));
        String networks = isTv ? LegacyQueryAnalyzer.compact(str(metadata.get("networks"))) : "";

        int totalScore = 0;

        if (isTv && !requestedNetworks.isEmpty() && matchesRequestedNetworks(networks, requestedNetworks)) {
            totalScore = Math.max(totalScore, 85);
        }

        if (fullCore.length() >= 2) {
            if (title.equals(fullCore) || originalTitle.equals(fullCore)) totalScore = Math.max(totalScore, 100);
            else if (title.startsWith(fullCore) || originalTitle.startsWith(fullCore)) totalScore = Math.max(totalScore, 85);
            else if (title.contains(fullCore) || originalTitle.contains(fullCore)) totalScore = Math.max(totalScore, 75);
            else if (creatorOrDirector.contains(fullCore)) totalScore = Math.max(totalScore, 80);
            else if (cast.contains(fullCore) || (isTv && networkMatches(networks, fullCore))) totalScore = Math.max(totalScore, isTv ? 75 : 70);
            else if (keywords.contains(fullCore)) totalScore = Math.max(totalScore, 55);
        }

        int termSum = 0;
        int matchedTerms = 0;
        for (String term : terms) {
            if (term.length() < 2) continue;
            int termScore = 0;
            if (title.equals(term) || originalTitle.equals(term)) termScore = 90;
            else if (title.startsWith(term) || originalTitle.startsWith(term)) termScore = 80;
            else if (creatorOrDirector.contains(term)) termScore = 80;
            else if (cast.contains(term) || (isTv && networkMatches(networks, term))) termScore = isTv ? 75 : 70;
            else if (title.contains(term) || originalTitle.contains(term)) termScore = 65;
            else if (keywords.contains(term)) termScore = 45;
            else if (genres.contains(term)) termScore = 35;
            else if (overview.contains(term)) termScore = 15;
            
            if (termScore > 0) {
                matchedTerms++;
                termSum += termScore;
            }
        }

        if (terms.size() >= 2 && matchedTerms == terms.size()) termSum += 25;
        return Math.max(totalScore, termSum);
    }

    public static double boostedScore(Document d, String query, Document refDoc, Set<String> requestedGenres, Set<String> requestedNetworks, boolean isTv) {
        Map<String, Object> m = d.getMetadata();
        double rrfPart = m.containsKey("hybrid_score") ? doubleOf(m.get("hybrid_score")) * 25.0 : 0.0;
        double vecPart = m.containsKey("vector_score") ? doubleOf(m.get("vector_score")) * 0.65 : (d.getScore() != null ? d.getScore() * 0.65 : 0.0);
        double similarity = rrfPart + vecPart;
        
        int year = intOf(m.get("year"));
        int thisYear = LocalDate.now().getYear();
        boolean wantsClassic = LegacyQueryAnalyzer.wantsClassic(query);

        double recencyBonus = 0.0;
        int recencyBaseYear = isTv ? 2005 : 1998;
        double recencyWeight = 0.18;
        
        if (!wantsClassic && year > 0) {
            double normalizedRecency = Math.max(0, Math.min(1.0, (year - recencyBaseYear) / (double) Math.max(1, thisYear - recencyBaseYear)));
            recencyBonus = normalizedRecency * recencyWeight;
            if (isTv) {
                if (year < 2000) recencyBonus -= 0.25;
                else if (year < 2008) recencyBonus -= 0.10;
            } else {
                if (year < 1995) recencyBonus -= 0.28;
                else if (year < 2001) recencyBonus -= 0.14;
            }
        }

        double rating = doubleOf(m.get("rating"));
        double ratingBonus = 0.0;
        double ratingWeight = 0.09;
        if (rating > 0) {
            if (rating < 5.8) ratingBonus = -0.18;
            else ratingBonus = Math.max(0, Math.min(1.0, (rating - 6.0) / 2.8)) * ratingWeight;
        }

        double alignmentBonus = 0.0;
        String candGenres = str(m.get("genres"));
        boolean candIsAnimation = candGenres.contains("애니메이션");
        boolean queryWantsAnimation = query != null && (query.contains("애니") || query.contains("만화"));

        if (refDoc != null) {
            Map<String, Object> rm = refDoc.getMetadata();
            String refGenres = str(rm.get("genres"));
            boolean refIsAnimation = refGenres.contains("애니메이션");

            if (!refIsAnimation && candIsAnimation && !queryWantsAnimation) alignmentBonus -= 0.42;
            else if (refIsAnimation && candIsAnimation) alignmentBonus += 0.18;

            Set<String> refGenreSet = splitTokens(refGenres);
            Set<String> candGenreSet = splitTokens(candGenres);
            if (!refGenreSet.isEmpty() && !candGenreSet.isEmpty()) {
                long sharedGenres = candGenreSet.stream().filter(refGenreSet::contains).count();
                if (sharedGenres == 0) alignmentBonus -= 0.22;
                else alignmentBonus += Math.min(0.24, sharedGenres * 0.09);
            }

            Set<String> refKwSet = splitTokens(str(rm.get("keywords")));
            Set<String> candKwSet = splitTokens(str(m.get("keywords")));
            if (!refKwSet.isEmpty() && !candKwSet.isEmpty()) {
                long sharedKw = candKwSet.stream().filter(refKwSet::contains).count();
                alignmentBonus += Math.min(0.20, sharedKw * 0.07);
            }
        } else {
            if (!queryWantsAnimation && candIsAnimation && query != null && !query.contains("가족") && !query.contains("어린이")) {
                alignmentBonus -= 0.08;
            }
            if (isTv && requestedNetworks != null && !requestedNetworks.isEmpty()) {
                String networksCompact = LegacyQueryAnalyzer.compact(str(m.get("networks")));
                if (matchesRequestedNetworks(networksCompact, requestedNetworks)) alignmentBonus += 0.65;
                else alignmentBonus -= 0.85;
            }
            if (!requestedGenres.isEmpty()) {
                if (matchesRequestedGenres(d, requestedGenres, isTv)) alignmentBonus += 0.45;
                else alignmentBonus -= 0.65;
            }
            if (hasConflictingGenre(d, query, requestedGenres, isTv)) {
                alignmentBonus -= 0.75;
            }
        }

        return similarity + recencyBonus + ratingBonus + alignmentBonus;
    }

    public static boolean matchesRequestedGenres(Document doc, Set<String> requestedGenres, boolean isTv) {
        if (requestedGenres.isEmpty()) return true;
        Map<String, Object> m = doc.getMetadata();
        String candGenres = LegacyQueryAnalyzer.compact(str(m.get("genres")));
        String candKeywords = LegacyQueryAnalyzer.compact(str(m.getOrDefault("keywords", extractFieldFromContent(doc.getText(), "키워드:"))));
        for (String req : requestedGenres) {
            if (candGenres.contains(req)) return true;
            if (isTv && candKeywords.contains(req)) return true;
            if ("로맨스".equals(req) && (candKeywords.contains("사랑") || candKeywords.contains("연애") || candKeywords.contains("멜로") || (isTv && (candKeywords.contains("로맨틱") || candKeywords.contains("달달"))))) return true;
        }
        return false;
    }

    public static boolean hasConflictingGenre(Document doc, String query, Set<String> requestedGenres, boolean isTv) {
        if (query == null || query.isBlank()) return false;
        String q = LegacyQueryAnalyzer.compact(query);
        String candGenres = LegacyQueryAnalyzer.compact(str(doc.getMetadata().get("genres")));
        boolean wantsLightOrRomantic = requestedGenres.contains("로맨스") || requestedGenres.contains("가족")
                || q.contains("달달") || q.contains("설레") || q.contains("힐링") || q.contains("따뜻") || (!isTv && q.contains("잔잔"));
        if (wantsLightOrRomantic) {
            if (isTv) {
                if (candGenres.contains("war") || candGenres.contains("전쟁")) return true;
                if (!requestedGenres.contains("범죄") && !requestedGenres.contains("미스터리") && candGenres.contains("범죄")) return true;
            } else {
                if (!requestedGenres.contains("전쟁") && candGenres.contains("전쟁")) return true;
                if (!requestedGenres.contains("공포") && candGenres.contains("공포")) return true;
                if (!requestedGenres.contains("범죄") && !requestedGenres.contains("스릴러")
                        && (candGenres.contains("범죄") || (candGenres.contains("역사") && !candGenres.contains("로맨스")))) return true;
            }
        }
        return false;
    }

    public static boolean matchesRequestedNetworks(String networksCompact, Set<String> requestedNetworks) {
        if (requestedNetworks == null || requestedNetworks.isEmpty()) return true;
        if (networksCompact.isEmpty()) return false;
        for (String net : requestedNetworks) {
            if (networkMatches(networksCompact, net)) return true;
        }
        return false;
    }
    
    public static boolean matchesRequestedNetworks(Document doc, Set<String> requestedNetworks) {
        return matchesRequestedNetworks(LegacyQueryAnalyzer.compact(str(doc.getMetadata().get("networks"))), requestedNetworks);
    }

    private static boolean networkMatches(String networksCompact, String term) {
        if (networksCompact.isEmpty() || term.isEmpty()) return false;
        if (networksCompact.contains(term)) return true;
        if (term.contains("넷플") || term.contains("netflix")) return networksCompact.contains("netflix") || networksCompact.contains("넷플릭스");
        if (term.contains("디즈니") || term.contains("disney")) return networksCompact.contains("disney") || networksCompact.contains("디즈니");
        if (term.contains("애플") || term.contains("apple")) return networksCompact.contains("apple") || networksCompact.contains("애플");
        if (term.contains("티빙") || term.contains("tving")) return networksCompact.contains("tving") || networksCompact.contains("티빙");
        if (term.contains("웨이브") || term.contains("wavve")) return networksCompact.contains("wavve") || networksCompact.contains("웨이브");
        if (term.contains("쿠팡") || term.contains("coupang")) return networksCompact.contains("coupang") || networksCompact.contains("쿠팡");
        if (term.contains("왓챠") || term.contains("watcha")) return networksCompact.contains("watcha") || networksCompact.contains("왓챠");
        if (term.contains("티비엔") || term.contains("tvn")) return networksCompact.contains("tvn");
        if (term.contains("제이티비씨") || term.contains("jtbc")) return networksCompact.contains("jtbc");
        return false;
    }

    private static Set<String> splitTokens(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public static String extractFieldFromContent(String content, String prefix) {
        if (content == null || content.isEmpty()) return "";
        for (String line : content.split("\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    private static String str(Object value) { return value == null ? "" : value.toString(); }
    private static int intOf(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private static double doubleOf(Object value) { return value instanceof Number n ? n.doubleValue() : 0; }

    public static List<Document> mergeAndRank(List<Document> keywordDocs, List<Document> vectorDocs, String searchText, boolean runKeywordSearch, int limit, boolean isTv) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> docMap = new LinkedHashMap<>();
        int rrfK = 60;

        for (int i = 0; i < keywordDocs.size(); i++) {
            Document doc = keywordDocs.get(i);
            int kwScore = keywordScore(doc, searchText, isTv);
            double entityBonus = kwScore >= 65 ? (kwScore / 100.0) : (kwScore >= 45 ? 0.25 : 0.0);
            rrfScores.put(doc.getId(), rrfScores.getOrDefault(doc.getId(), 0.0) + (1.0 / (rrfK + i + 1)) + entityBonus);
            docMap.put(doc.getId(), doc);
        }
        for (int i = 0; i < vectorDocs.size(); i++) {
            Document doc = vectorDocs.get(i);
            enrichMetadataFromContent(doc);
            if (doc.getScore() != null) {
                doc.getMetadata().put("vector_score", doc.getScore());
            }
            double entityBonus = 0.0;
            if (runKeywordSearch && !docMap.containsKey(doc.getId())) {
                int kwScore = keywordScore(doc, searchText, isTv);
                if (kwScore >= 65) {
                    entityBonus = kwScore / 100.0;
                } else if (kwScore >= 45) {
                    entityBonus = 0.25;
                }
            }
            rrfScores.put(doc.getId(), rrfScores.getOrDefault(doc.getId(), 0.0) + (1.0 / (rrfK + i + 1)) + entityBonus);
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
