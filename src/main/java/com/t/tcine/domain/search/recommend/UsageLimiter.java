package com.t.tcine.domain.search.recommend;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** 사용자별 하루 AI 추천 횟수 제한 (날짜가 바뀌면 초기화) */
public class UsageLimiter {

    private final int dailyLimit;
    private final Map<String, Integer> usage = new HashMap<>();
    private LocalDate usageDay = LocalDate.now();

    public UsageLimiter(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    /** 한도 안이면 횟수를 1 올리고 true */
    public synchronized boolean tryConsume(String username) {
        LocalDate today = LocalDate.now();
        if (!today.equals(usageDay)) { usage.clear(); usageDay = today; }
        int used = usage.getOrDefault(username, 0);
        if (used >= dailyLimit) return false;
        usage.put(username, used + 1);
        return true;
    }
}
