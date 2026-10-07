package com.t.tcine.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled 작업(영화 자동 색인)을 켠다 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
