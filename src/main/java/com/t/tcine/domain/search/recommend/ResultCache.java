package com.t.tcine.domain.search.recommend;

import java.util.LinkedHashMap;
import java.util.Map;

/** 같은 질의의 AI 추천을 잠시 재사용하는 작은 LRU 캐시 (항목마다 만료 시각이 있다) */
public class ResultCache<R> {

    private final long ttlMillis;
    private final Map<String, Entry<R>> entries;

    public ResultCache(int maxEntries, long ttlMillis) {
        this.ttlMillis = ttlMillis;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry<R>> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** 만료되지 않은 값이 있으면 돌려주고, 없으면 null */
    public R get(String key, long nowMillis) {
        synchronized (entries) {
            Entry<R> cached = entries.get(key);
            return cached != null && cached.expiresAt() > nowMillis ? cached.value() : null;
        }
    }

    public void put(String key, R value, long nowMillis) {
        synchronized (entries) {
            entries.put(key, new Entry<>(value, nowMillis + ttlMillis));
        }
    }

    private record Entry<R>(R value, long expiresAt) {}
}
