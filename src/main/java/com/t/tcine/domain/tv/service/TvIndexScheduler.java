package com.t.tcine.domain.tv.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시리즈 데이터를 매일 새벽에 자동으로 보충한다 (방영 중·한국 신작·인기작만, 이미 있는 시리즈는 건너뜀).
 * tv.auto-index=true (환경변수 TV_AUTO_INDEX) 일 때만 동작한다. 기본값은 꺼짐.
 * 영화 자동 색인(새벽 4시)과 TMDB·OpenAI 호출이 겹치지 않도록 기본 시각을 30분 뒤로 둔다.
 */
@Component
public class TvIndexScheduler {

    private static final Logger log = LoggerFactory.getLogger(TvIndexScheduler.class);

    private final TvIndexService indexService;
    private final boolean enabled;

    public TvIndexScheduler(TvIndexService indexService, @Value("${tv.auto-index:false}") boolean enabled) {
        this.indexService = indexService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${tv.auto-index-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        if (!enabled) {
            return;
        }
        boolean started = indexService.startAuto();
        log.info("시리즈 자동 색인: {}", started ? "시작" : "이미 색인이 진행 중이라 건너뜀");
    }
}
