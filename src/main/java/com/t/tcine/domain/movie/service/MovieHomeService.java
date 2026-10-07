package com.t.tcine.domain.movie.service;

import com.t.tcine.domain.movie.dto.HomeMovie;
import com.t.tcine.infra.tmdb.TmdbClient;
import com.t.tcine.infra.tmdb.TmdbClient.TmdbMovie;
import com.t.tcine.infra.kobis.KobisClient;
import com.t.tcine.infra.kobis.KobisClient.BoxOfficeMovie;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 영화 화면 첫머리의 "오늘의 인기 영화"와 "지금 상영 중" 목록. TMDB에서 가져와 30분 캐시한다.
 * AI/Qdrant를 쓰지 않아 비용이 없고, TMDB 키가 없거나 호출이 실패하면 빈 목록(섹션이 안 보임).
 */
@Service
public class MovieHomeService {

    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_ITEMS = 12;
    private static final String POSTER_BASE = "https://image.tmdb.org/t/p/w342";

    private final TmdbClient tmdb;
    private final KobisClient kobis;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public MovieHomeService(TmdbClient tmdb, KobisClient kobis) {
        this.tmdb = tmdb;
        this.kobis = kobis;
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

    public List<HomeMovie> boxOffice() {
        return kobis.dailyMovies().stream().map(item -> tmdb.searchMovie(item.title()).stream().findFirst()
                .map(m -> toHome(m, item.audienceCount())).orElse(null))
                .filter(java.util.Objects::nonNull).toList();
    }

    private List<HomeMovie> load(String category) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(category);
        if (cached != null && cached.expiresAt() > now) {
            return cached.items();
        }
        List<HomeMovie> items = tmdb.list(category, 1).stream()
                .filter(m -> m.posterPath() != null && !m.posterPath().isBlank())
                .limit(MAX_ITEMS)
                .map(MovieHomeService::toHome)
                .toList();
        if (!items.isEmpty()) {
            cache.put(category, new Cached(items, now + CACHE_TTL_MILLIS)); // 실패(빈 목록)는 캐시하지 않는다
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
