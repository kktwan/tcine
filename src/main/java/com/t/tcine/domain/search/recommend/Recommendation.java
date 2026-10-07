package com.t.tcine.domain.search.recommend;

/** 추천 결과 한 건 (영화·시리즈 결과 DTO가 구현한다). message 는 사용자에게 알릴 안내이며 없으면 null */
public interface Recommendation<C extends RecommendCard> {

    boolean hasCards();

    String message();
}
