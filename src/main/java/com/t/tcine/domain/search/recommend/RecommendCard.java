package com.t.tcine.domain.search.recommend;

/** 추천 결과 카드에서 정렬·검증에 쓰는 공통 값 (영화·시리즈 카드가 구현한다) */
public interface RecommendCard {

    int id();

    Integer year();

    Double rating();
}
