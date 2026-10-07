package com.t.tcine.domain.movie.dto;

/** 영화 색인 진행 상태 (관리자 화면에서 주기적으로 조회) */
public record IndexStatus(boolean running, int done, String message) {

    public static IndexStatus idle() {
        return new IndexStatus(false, 0, "");
    }
}
