package com.t.tcine.domain.movie.service;

import com.t.tcine.domain.movie.dto.HomeMovie;
import com.t.tcine.infra.tmdb.TmdbClient;
import com.t.tcine.infra.tmdb.TmdbClient.TmdbMovie;
import com.t.tcine.infra.kobis.KobisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 영화 화면 첫머리의 "오늘의 인기 영화", "지금 상영 중", "박스오피스", "한국 신작" 목록.
 * TMDB/KOBIS에서 가져와 30분 캐시하며, 서버 기동 직후 및 주기적으로 백그라운드에서 미리 갱신해 초기 로딩 지연을 없앤다.
 */
@Service
public class MovieHomeService {

    private static final Logger log = LoggerFactory.getLogger(MovieHomeService.class);
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_ITEMS = 12;
    private static final String POSTER_BASE = "https://image.tmdb.org/t/p/w342";

    private final TmdbClient tmdb;
    private final KobisClient kobis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final ExecutorService homePool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "movie-home-loader");
        t.setDaemon(true);
        return t;
    });

    public MovieHomeService(TmdbClient tmdb, KobisClient kobis) {
        this.tmdb = tmdb;
        this.kobis = kobis;
    }

    /** 서버 시작 직후 백그라운드에서 홈 화면 목록을 미리 적재한다 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        CompletableFuture.runAsync(this::refreshAll, homePool);
    }

    /** 캐시 만료 전에 25분마다 백그라운드에서 미리 갱신한다 */
    @Scheduled(fixedDelay = 25 * 60 * 1000L, initialDelay = 25 * 60 * 1000L)
    public void scheduledRefresh() {
        refreshAll();
    }

    private void refreshAll() {
        try {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(() -> refreshCategory("trending_day"), homePool),
                    CompletableFuture.runAsync(() -> refreshCategory("now_playing"), homePool),
                    CompletableFuture.runAsync(() -> refreshCategory("korean_now"), homePool),
                    CompletableFuture.runAsync(this::refreshBoxOffice, homePool)
            ).join();
            log.debug("영화 홈 캐시 백그라운드 갱신 완료");
        } catch (Exception e) {
            log.warn("영화 홈 캐시 갱신 중 오류: {}", e.getMessage());
        }
    }

    /** 오늘의 인기(트렌딩) 영화 */
    public List<HomeMovie> trending() {
        return load("trending_day");
    }

    /** 최근 한국에서 개봉한 한국 영화 */
    public List<HomeMovie> koreanNow() {
        return load("korean_now");
    }

    /** 한국에서 지금 상영 중인 영화 */
    public List<HomeMovie> nowPlaying() {
        return load("now_playing");
    }

    /** 박스오피스 순위 (30분 캐시 + 병렬 TMDB 매칭) */
    public List<HomeMovie> boxOffice() {
        long now = System.currentTimeMillis();
        Cached cached = cache.get("box_office");
        if (cached != null && cached.expiresAt() > now) {
            return cached.items();
        }
        if (cached != null) {
            CompletableFuture.runAsync(this::refreshBoxOffice, homePool);
            return cached.items();
        }
        return refreshBoxOffice();
    }

    private List<HomeMovie> refreshBoxOffice() {
        long now = System.currentTimeMillis();
        List<CompletableFuture<HomeMovie>> futures = kobis.dailyMovies().stream()
                .map(item -> CompletableFuture.supplyAsync(() ->
                        tmdb.searchMovie(item.title()).stream().findFirst()
                                .map(m -> toHome(m, item.audienceCount()))
                                .orElse(null), homePool))
                .toList();
        List<HomeMovie> items = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
        if (!items.isEmpty()) {
            cache.put("box_office", new Cached(items, now + CACHE_TTL_MILLIS));
        }
        return items;
    }

    private List<HomeMovie> load(String category) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(category);
        if (cached != null && cached.expiresAt() > now) {
            return cached.items();
        }
        if (cached != null) {
            CompletableFuture.runAsync(() -> refreshCategory(category), homePool);
            return cached.items();
        }
        return refreshCategory(category);
    }

    private List<HomeMovie> refreshCategory(String category) {
        long now = System.currentTimeMillis();
        List<HomeMovie> items = tmdb.list(category, 1).stream()
                .filter(m -> m.posterPath() != null && !m.posterPath().isBlank())
                .limit(MAX_ITEMS)
                .map(MovieHomeService::toHome)
                .toList();
        if (!items.isEmpty()) {
            cache.put(category, new Cached(items, now + CACHE_TTL_MILLIS));
        }
        return items;
    }

    private static HomeMovie toHome(TmdbMovie m) {
        String title = m.title() == null ? "" : m.title();
        Integer year = null;
        if (m.releaseDate() != null && m.releaseDate().length() >= 4) {
            try {
                year = Integer.valueOf(m.releaseDate().substring(0, 4));
            } catch (NumberFormatException ignored) {
                // 연도를 알 수 없으면 표시하지 않는다
            }
        }
        Double rating = m.voteAverage() != null && m.voteAverage() > 0 ? m.voteAverage() : null;
        return new HomeMovie(m.id(), title, year, rating, null, POSTER_BASE + m.posterPath(), title + "와 비슷한 영화");
    }

    private static HomeMovie toHome(TmdbMovie m, long audience) {
        HomeMovie base = toHome(m);
        return new HomeMovie(base.id(), base.title(), base.year(), base.rating(), audience, base.posterUrl(), base.query());
    }

    private record Cached(List<HomeMovie> items, long expiresAt) {
    }
}
