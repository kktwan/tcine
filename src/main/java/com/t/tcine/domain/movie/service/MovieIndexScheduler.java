package com.t.tcine.domain.movie.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 영화 데이터를 매일 새벽에 자동으로 보충한다 (신작과 인기작만, 이미 있는 영화는 건너뜀).
 * movie.auto-index=true (환경변수 MOVIE_AUTO_INDEX) 일 때만 동작한다. 기본값은 꺼짐.
 */
@Component
public class MovieIndexScheduler {

    private static final Logger log = LoggerFactory.getLogger(MovieIndexScheduler.class);

    private final MovieIndexService indexService;
    private final boolean enabled;

    public MovieIndexScheduler(MovieIndexService indexService, @Value("${movie.auto-index:false}") boolean enabled) {
        this.indexService = indexService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${movie.auto-index-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        if (!enabled) {
            return;
        }
        boolean started = indexService.startAuto();
        log.info("영화 자동 색인: {}", started ? "시작" : "이미 색인이 진행 중이라 건너뜀");
    }
}
