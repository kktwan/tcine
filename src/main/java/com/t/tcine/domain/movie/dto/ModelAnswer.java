package com.t.tcine.domain.movie.dto;

import java.util.List;

/**
 * AI가 돌려주는 구조화 응답 (Spring AI가 JSON 형식을 안내하고 이 타입으로 변환한다).
 * summary = 한두 문장 소개, picks = 추천 장소(코스면 방문 순서대로)
 */
public record ModelAnswer(String summary, List<ModelPick> picks) {

    /** id는 반드시 searchPlaces 도구 결과에 나온 장소 id여야 한다 (서버가 검증) */
    public record ModelPick(String id, String reason) {
    }
}
