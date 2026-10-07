package com.t.tcine.domain.movie.dto;

/** 영화 화면 첫머리에 보여줄 인기/상영작 한 편. query = 누르면 검색창에 넣을 "비슷한 영화" 문장 */
public record HomeMovie(int id, String title, Integer year, Double rating, Long audienceCount, String posterUrl, String query) {
}
