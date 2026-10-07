package com.t.tcine.domain.tv.dto;

import java.util.List;

/**
 * 시리즈(드라마·예능·애니) 추천 결과 (화면용).
 * summary = AI의 한두 문장 소개, ai = AI가 골랐는지(false면 검색 순서 그대로), message = 안내 문구
 */
public record TvResult(String summary, List<TvCard> cards, boolean ai, String message) {

    public static TvResult empty(String message) {
        return new TvResult(null, List.of(), false, message);
    }

    public boolean hasCards() {
        return cards != null && !cards.isEmpty();
    }

    public record TvCard(int id, String title, String originalTitle, Integer year, String genres,
                         Double rating, String overview, String posterUrl, String tmdbUrl, String reason,
                         String creator, String cast, String networks, Integer seasons, Integer episodes) {
    }
}
