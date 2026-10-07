package com.t.tcine.domain.search.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 검색 점수 가중치. 기본값은 기존에 코드에 박혀 있던 값과 같다.
 * 조정하려면 application.yml 의 search.ranking.* 로 덮어쓴다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "search.ranking")
public class RankingProperties {

    /** RRF(Reciprocal Rank Fusion) 상수 */
    private int rrfK = 60;
    /** 하이브리드(RRF) 점수 반영 배율 */
    private double rrfWeight = 25.0;
    /** 벡터 유사도 반영 배율 */
    private double vectorWeight = 0.65;

    private Keyword keyword = new Keyword();
    private Recency movie = Recency.of(1998, new Penalty(1995, -0.28), new Penalty(2001, -0.14));
    private Recency tv = Recency.of(2005, new Penalty(2000, -0.25), new Penalty(2008, -0.10));
    private Rating rating = new Rating();
    private Alignment alignment = new Alignment();

    public Recency recency(boolean isTv) {
        return isTv ? tv : movie;
    }

    /** 키워드(제목·인물·OTT 등) 일치 점수 */
    @Getter
    @Setter
    public static class Keyword {
        private int networkMatch = 85;
        private int titleEquals = 100;
        private int titleStartsWith = 85;
        private int titleContains = 75;
        private int creator = 80;
        private int castMovie = 70;
        private int castTv = 75;
        private int keywords = 55;
        private int termTitleEquals = 90;
        private int termTitleStartsWith = 80;
        private int termCreator = 80;
        private int termTitleContains = 65;
        private int termKeywords = 45;
        private int termGenres = 35;
        private int termOverview = 15;
        /** 핵심어가 2개 이상일 때 모두 일치하면 더하는 보너스 */
        private int allTermsBonus = 25;
        /** 이 점수 이상이면 고유명사(작품·인물) 일치로 보고 RRF 에 보너스를 준다 */
        private int entityStrong = 65;
        private int entityWeak = 45;
        private double entityWeakBonus = 0.25;
        private int minTermLength = 2;

        public int cast(boolean isTv) {
            return isTv ? castTv : castMovie;
        }
    }

    /** 최신작 가점과 오래된 작품 감점 */
    @Getter
    @Setter
    public static class Recency {
        private int baseYear;
        private double weight = 0.18;
        /** 앞에서부터 처음 맞는 하나만 적용한다 */
        private List<Penalty> penalties = List.of();

        static Recency of(int baseYear, Penalty... penalties) {
            Recency r = new Recency();
            r.baseYear = baseYear;
            r.penalties = List.of(penalties);
            return r;
        }
    }

    /** beforeYear 보다 오래된 작품에 penalty(음수)를 더한다 */
    @Getter
    @Setter
    public static class Penalty {
        private int beforeYear;
        private double penalty;

        public Penalty() {}

        public Penalty(int beforeYear, double penalty) {
            this.beforeYear = beforeYear;
            this.penalty = penalty;
        }
    }

    @Getter
    @Setter
    public static class Rating {
        private double lowThreshold = 5.8;
        private double lowPenalty = -0.18;
        private double base = 6.0;
        private double span = 2.8;
        private double weight = 0.09;
    }

    /** 기준 작품·요청 장르·OTT 와 얼마나 맞는지에 따른 가감점 */
    @Getter
    @Setter
    public static class Alignment {
        // 기준 작품이 있을 때(비슷한 작품 찾기)
        private double animationMismatch = -0.42;
        private double animationBoth = 0.18;
        private double noSharedGenres = -0.22;
        private double sharedGenreEach = 0.09;
        private double sharedGenreCap = 0.24;
        private double sharedKeywordEach = 0.07;
        private double sharedKeywordCap = 0.20;
        // 기준 작품이 없을 때
        private double unrelatedAnimation = -0.08;
        private double networkHit = 0.65;
        private double networkMiss = -0.85;
        private double genreHit = 0.45;
        private double genreMiss = -0.65;
        private double conflictingGenre = -0.75;
    }
}
