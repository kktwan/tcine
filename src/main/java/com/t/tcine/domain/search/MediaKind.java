package com.t.tcine.domain.search;

import java.util.Locale;

/** 검색·순위 규칙이 영화와 시리즈 중 어느 쪽 데이터를 다루는지 나타낸다. */
public enum MediaKind {
    MOVIE, TV;

    public boolean isTv() {
        return this == TV;
    }

    /** 사전 yml 에서 영화·시리즈별 설정을 찾을 때 쓰는 키 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
