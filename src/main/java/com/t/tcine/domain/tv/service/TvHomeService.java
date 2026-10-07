package com.t.tcine.domain.tv.service;

import com.t.tcine.domain.tv.dto.HomeTv;
import com.t.tcine.infra.tmdb.TmdbClient;
import com.t.tcine.infra.tmdb.TmdbClient.TmdbTv;
import com.t.tcine.infra.tmdb.TmdbClient.TvDetail;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 시리즈 화면 첫머리의 인기/한국 신작 목록. TMDB에서 가져와 방송사/OTT 이름과 함께 30분 캐시한다.
 */
@Service
public class TvHomeService {

    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_ITEMS = 12;
    private static final String POSTER_BASE = "https://image.tmdb.org/t/p/w342";

    private final TmdbClient tmdb;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final ExecutorService detailPool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "tv-home-detail");
        t.setDaemon(true);
        return t;
    });

    public TvHomeService(TmdbClient tmdb) {
        this.tmdb = tmdb;
    }

    /** 오늘의 인기 시리즈 */
    public List<HomeTv> trending() {
        return load("trending_day");
    }

    /** 최근 한국 드라마·예능 신작 */
    public List<HomeTv> koreanNow() {
        return load("korean_now");
    }

    /** 한국 대표 인기 시리즈 */
    public List<HomeTv> koreanPopular() {
        return load("korean");
    }

    private List<HomeTv> load(String category) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(category);
        if (cached != null && cached.expiresAt() > now) {
            return cached.items();
        }
        List<TmdbTv> rawList = tmdb.listTv(category, 1).stream()
                .filter(s -> s.posterPath() != null && !s.posterPath().isBlank())
                .limit(MAX_ITEMS)
                .toList();
        if (rawList.isEmpty()) {
            return List.of();
        }
        // 12편의 방송사/OTT(networks) 정보를 병렬로 빠르게 조회한다
        List<CompletableFuture<HomeTv>> futures = rawList.stream()
                .map(s -> CompletableFuture.supplyAsync(() -> {
                    String network = tmdb.detailTv(s.id())
                            .map(TvDetail::networks)
                            .map(TvHomeService::formatPrimaryNetwork)
                            .orElse("");
                    return toHome(s, network);
                }, detailPool))
                .toList();
        List<HomeTv> items = futures.stream().map(CompletableFuture::join).toList();
        cache.put(category, new Cached(items, now + CACHE_TTL_MILLIS));
        return items;
    }

    private static HomeTv toHome(TmdbTv s, String network) {
        String title = s.name() == null ? "" : s.name();
        Integer year = null;
        if (s.firstAirDate() != null && s.firstAirDate().length() >= 4) {
            try {
                year = Integer.valueOf(s.firstAirDate().substring(0, 4));
            } catch (NumberFormatException ignored) {
            }
        }
        Double rating = s.voteAverage() != null && s.voteAverage() > 0 ? s.voteAverage() : null;
        return new HomeTv(s.id(), title, year, rating, network, POSTER_BASE + s.posterPath(), title + "와 비슷한 시리즈");
    }

    /** 첫 번째 대표 방송사/OTT 이름을 짧고 직관적인 표기로 정리한다 */
    private static String formatPrimaryNetwork(String networks) {
        if (networks == null || networks.isBlank()) {
            return "";
        }
        String first = networks.split(",")[0].trim();
        if (first.isEmpty()) {
            return "";
        }
        String lower = first.toLowerCase(Locale.ROOT);
        if (lower.contains("netflix")) return "Netflix";
        if (lower.contains("coupang")) return "Coupang Play";
        if (lower.contains("disney")) return "Disney+";
        if (lower.contains("wavve")) return "Wavve";
        if (lower.contains("tving")) return "TVING";
        if (lower.contains("apple")) return "Apple TV+";
        if (lower.contains("prime video") || lower.contains("amazon")) return "Prime Video";
        if (lower.contains("watcha")) return "Watcha";
        if (lower.equals("tvn")) return "tvN";
        if (lower.equals("kbs2") || lower.equals("kbs 2tv")) return "KBS2";
        if (lower.equals("kbs1") || lower.equals("kbs 1tv")) return "KBS1";
        return first;
    }

    private record Cached(List<HomeTv> items, long expiresAt) {
    }
}
