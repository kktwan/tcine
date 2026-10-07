package com.t.tcine.domain.movie.service;

import com.t.tcine.infra.tmdb.TmdbClient;
import com.t.tcine.infra.tmdb.TmdbClient.MovieFull;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 영화 상세 페이지용 정보. TMDB에서 가져와 30분 캐시한다 (실패는 캐시하지 않는다) */
@Service
public class MovieDetailService {

    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int CACHE_MAX_ENTRIES = 300;

    private final TmdbClient tmdb;
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();

    public MovieDetailService(TmdbClient tmdb) {
        this.tmdb = tmdb;
    }

    public Optional<MovieFull> get(int id) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(id);
        if (cached != null && cached.expiresAt() > now) {
            return Optional.of(cached.movie());
        }
        Optional<MovieFull> movie = tmdb.movie(id);
        movie.ifPresent(m -> {
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.clear(); // 단순하게 가득 차면 비운다 (영화 상세는 다시 받으면 된다)
            }
            cache.put(id, new Cached(m, now + CACHE_TTL_MILLIS));
        });
        return movie;
    }

    private record Cached(MovieFull movie, long expiresAt) {
    }
}
