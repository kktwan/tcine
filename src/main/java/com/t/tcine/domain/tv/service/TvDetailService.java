package com.t.tcine.domain.tv.service;

import com.t.tcine.infra.tmdb.TmdbClient;
import com.t.tcine.infra.tmdb.TmdbClient.TvFull;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** TV 시리즈 상세 페이지용 정보. TMDB에서 가져와 30분 캐시한다 */
@Service
public class TvDetailService {

    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int CACHE_MAX_ENTRIES = 300;

    private final TmdbClient tmdb;
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    public TvDetailService(TmdbClient tmdb) {
        this.tmdb = tmdb;
    }

    public Optional<TvFull> get(int id) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(id);
        if (cached != null && cached.expiresAt() > now) {
            return Optional.of(cached.tv());
        }
        Optional<TvFull> tv = tmdb.tv(id);
        tv.ifPresent(t -> {
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.clear();
            }
            cache.put(id, new Cached(t, now + CACHE_TTL_MILLIS));
        });
        return tv;
    }

    private record Cached(TvFull tv, long expiresAt) {
    }
}
