package com.t.tcine.domain.movie.dto;

import com.t.tcine.domain.search.recommend.RecommendCard;
import com.t.tcine.domain.search.recommend.Recommendation;

import java.util.List;

/**
 * 영화 추천 결과 (화면용). summary = AI의 한두 문장 소개, ai = AI가 골랐는지(false면 의미 검색 순서 그대로),
 * message = 사용자에게 알릴 안내(없으면 null)
 */
public record MovieResult(String summary, List<MovieCard> cards, boolean ai, String message)
        implements Recommendation<MovieResult.MovieCard> {

    public static MovieResult empty(String message) {
        return new MovieResult(null, List.of(), false, message);
    }

    @Override
    public boolean hasCards() {
        return cards != null && !cards.isEmpty();
    }

    public record MovieCard(int id, String title, String originalTitle, Integer year, String genres,
                            Double rating, String overview, String posterUrl, String tmdbUrl, String reason,
                            String director, String cast, Integer runtime) implements RecommendCard {
    }
}
