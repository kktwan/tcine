package com.t.tcine.domain.search.recommend;

/**
 * 영화·시리즈 추천 화면에 보여 주는 안내 문구 모음.
 * similarBy*Request 는 (기준 작품 제목, 기준 작품 정보) 두 값을 %s 로 받는다.
 */
public record RecommendMessages(
        String embeddingNeeded,
        String noKeywordMatch,
        String keywordNotReady,
        String keywordUnavailable,
        String indexEmpty,
        String qdrantUnavailable,
        String noIndexedAtAll,
        String aiTimeout,
        String aiFailed,
        String aiPickedNothing,
        String detailMissing,
        String insufficientInfo,
        String similarByIdRequest,
        String similarByTitleRequest,
        String candidateHeader,
        String noRelevantResult) {

    public static final String AI_NOT_CONFIGURED = "AI가 아직 설정되지 않았어요. (서버에 GEMINI_API_KEY가 필요해요)";
    public static final String LIMIT_REACHED = "오늘 사용 횟수를 모두 썼어요. 내일 다시 이용해 주세요.";
    public static final String INTERRUPTED = "요청이 중단됐어요.";
}
