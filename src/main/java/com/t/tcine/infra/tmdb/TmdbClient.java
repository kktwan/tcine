package com.t.tcine.infra.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * TMDB(The Movie Database) API 호출. 영화 목록과 한국어 줄거리를 가져온다.
 * 키는 두 종류 모두 지원한다: v3 API Key(짧은 16진 문자열)는 api_key 파라미터로, v4 Read Access Token(긴 JWT)은 Bearer 헤더로 보낸다.
 * 이 데이터를 쓰는 화면에는 TMDB 출처 표기(문구와 로고)를 해야 한다.
 */
@Component
public class TmdbClient {

    private static final Logger log = LoggerFactory.getLogger(TmdbClient.class);

    private final RestClient restClient;
    private final String apiKey;
    private final boolean bearer;

    /** 장르 id -> 한국어 이름 (한 번 받아서 재사용) */
    private volatile Map<Integer, String> genreNames;
    private volatile Map<Integer, String> tvGenreNames;

    public TmdbClient(RestClient.Builder builder, @Value("${tmdb.api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        // v4 토큰은 JWT(점이 들어 있고 매우 길다), v3 키는 32자 16진수
        this.bearer = this.apiKey.length() > 60 || this.apiKey.contains(".");
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = builder.baseUrl("https://api.themoviedb.org/3").requestFactory(factory).build();
    }

    public boolean isEnabled() {
        return !apiKey.isEmpty();
    }

    /**
     * 영화 목록 한 페이지(20편). category: top_rated(평점 높은 순), popular(인기), korean(한국 영화, 투표 많은 순),
     * trending_day/trending_week(트렌딩), now_playing(한국 현재 상영작).
     * 실패하면 빈 목록.
     */
    public List<TmdbMovie> list(String category, int page) {
        if (!isEnabled()) {
            return List.of();
        }
        try {
            ListResponse res = restClient.get()
                    .uri(u -> {
                        var b = u.path(pathOf(category));
                        b.queryParam("language", "ko-KR").queryParam("page", page);
                        if ("now_playing".equals(category) || "upcoming".equals(category)) {
                            b.queryParam("region", "KR"); // 한국 기준 상영작/개봉 예정작
                        }
                        if ("korean_now".equals(category)) {
                            // 한국어 원어 영화 중, 최근 두 달 안에 한국에서 극장(2=제한 개봉, 3=정식 개봉) 개봉한 것을 인기 순으로
                            LocalDate today = LocalDate.now();
                            b.queryParam("with_original_language", "ko")
                                    .queryParam("region", "KR")
                                    .queryParam("with_release_type", "2|3")
                                    .queryParam("release_date.gte", today.minusDays(60))
                                    .queryParam("release_date.lte", today.plusDays(7))
                                    .queryParam("sort_by", "popularity.desc");
                        }
                        if ("korean".equals(category)) {
                            b.queryParam("with_original_language", "ko")
                                    .queryParam("sort_by", "vote_count.desc")
                                    .queryParam("vote_count.gte", 50);
                        }
                        if (!bearer) {
                            b.queryParam("api_key", apiKey);
                        }
                        return b.build();
                    })
                    .headers(h -> {
                        if (bearer) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(ListResponse.class);
            return res == null || res.results() == null ? List.of() : res.results();
        } catch (RestClientException e) {
            log.warn("TMDB 호출 실패({} {}쪽): {}", category, page, e.getMessage());
            return List.of();
        }
    }

    public List<TmdbMovie> searchMovie(String title) {
        if (!isEnabled() || title == null || title.isBlank()) return List.of();
        try {
            ListResponse res = restClient.get().uri(u -> {
                var b = u.path("/search/movie").queryParam("query", title).queryParam("language", "ko-KR").queryParam("region", "KR");
                if (!bearer) b.queryParam("api_key", apiKey);
                return b.build();
            }).headers(h -> { if (bearer) h.setBearerAuth(apiKey); }).retrieve().body(ListResponse.class);
            return res == null || res.results() == null ? List.of() : res.results();
        } catch (RestClientException e) { return List.of(); }
    }

    /**
     * 영화 한 편의 상세 정보. 감독/출연/키워드를 한 번의 호출로 받는다(append_to_response).
     * 실패하면 빈 값 (목록에서 얻은 기본 정보만으로 색인을 계속한다).
     */
    public Optional<MovieDetail> detail(int movieId) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            DetailResponse res = restClient.get()
                    .uri(u -> {
                        var b = u.path("/movie/" + movieId)
                                .queryParam("language", "ko-KR")
                                .queryParam("append_to_response", "credits,keywords");
                        if (!bearer) {
                            b.queryParam("api_key", apiKey);
                        }
                        return b.build();
                    })
                    .headers(h -> {
                        if (bearer) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(DetailResponse.class);
            if (res == null) {
                return Optional.empty();
            }
            String director = res.credits() == null || res.credits().crew() == null ? "" : res.credits().crew().stream()
                    .filter(p -> "Director".equals(p.job()) && p.name() != null)
                    .map(Person::name).distinct().limit(2).collect(Collectors.joining(", "));
            List<String> cast = res.credits() == null || res.credits().cast() == null ? List.of() : res.credits().cast().stream()
                    .map(Person::name).filter(n -> n != null && !n.isBlank()).limit(6).toList();
            List<String> keywords = res.keywords() == null || res.keywords().keywords() == null ? List.of()
                    : res.keywords().keywords().stream().map(Keyword::name).filter(n -> n != null && !n.isBlank())
                    .limit(10).toList();
            return Optional.of(new MovieDetail(res.tagline(), res.runtime(), director, cast, keywords));
        } catch (RestClientException e) {
            log.warn("TMDB 영화 상세 호출 실패(id {}): {}", movieId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 목록 종류에 맞는 TMDB 경로: korean(발견), trending_day/trending_week(트렌딩), 그 밖에는 /movie/{종류} */
    private static String pathOf(String category) {
        return switch (category) {
            case "korean", "korean_now" -> "/discover/movie";
            case "trending_day" -> "/trending/movie/day";
            case "trending_week" -> "/trending/movie/week";
            default -> "/movie/" + category;
        };
    }

    /** 영화 한 편의 전체 정보 (상세 페이지용). 실패하면 빈 값 */
    public Optional<MovieFull> movie(int movieId) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            DetailResponse res = restClient.get()
                    .uri(u -> {
                        var b = u.path("/movie/" + movieId)
                                .queryParam("language", "ko-KR")
                                .queryParam("append_to_response", "credits,keywords");
                        if (!bearer) {
                            b.queryParam("api_key", apiKey);
                        }
                        return b.build();
                    })
                    .headers(h -> {
                        if (bearer) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(DetailResponse.class);
            if (res == null || res.title() == null) {
                return Optional.empty();
            }
            String director = res.credits() == null || res.credits().crew() == null ? "" : res.credits().crew().stream()
                    .filter(p -> "Director".equals(p.job()) && p.name() != null)
                    .map(Person::name).distinct().limit(2).collect(Collectors.joining(", "));
            List<String> cast = res.credits() == null || res.credits().cast() == null ? List.of() : res.credits().cast().stream()
                    .map(Person::name).filter(n -> n != null && !n.isBlank()).limit(10).toList();
            List<String> keywords = res.keywords() == null || res.keywords().keywords() == null ? List.of()
                    : res.keywords().keywords().stream().map(Keyword::name).filter(n -> n != null && !n.isBlank())
                    .limit(10).toList();
            String genres = res.genres() == null ? "" : res.genres().stream().map(Genre::name).collect(Collectors.joining(", "));
            Integer year = null;
            if (res.releaseDate() != null && res.releaseDate().length() >= 4) {
                try {
                    year = Integer.valueOf(res.releaseDate().substring(0, 4));
                } catch (NumberFormatException ignored) {
                    // 연도를 알 수 없으면 표시하지 않는다
                }
            }
            return Optional.of(new MovieFull(movieId, res.title(), res.originalTitle(), res.overview(), year,
                    res.runtime(), genres, res.voteAverage(), res.posterPath(), res.backdropPath(), res.tagline(), director, cast, keywords));
        } catch (RestClientException e) {
            log.warn("TMDB 영화 상세 페이지 호출 실패(id {}): {}", movieId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 장르 id -> 이름 (실패하면 빈 맵) */
    public Map<Integer, String> genres() {
        Map<Integer, String> cached = genreNames;
        if (cached != null) {
            return cached;
        }
        if (!isEnabled()) {
            return Map.of();
        }
        try {
            GenreResponse res = restClient.get()
                    .uri(u -> {
                        var b = u.path("/genre/movie/list").queryParam("language", "ko-KR");
                        if (!bearer) {
                            b.queryParam("api_key", apiKey);
                        }
                        return b.build();
                    })
                    .headers(h -> {
                        if (bearer) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(GenreResponse.class);
            Map<Integer, String> map = new HashMap<>();
            if (res != null && res.genres() != null) {
                res.genres().forEach(g -> map.put(g.id(), g.name()));
            }
            genreNames = map;
            return map;
        } catch (RestClientException e) {
            log.warn("TMDB 장르 목록 호출 실패: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * TV 시리즈(드라마·예능·애니) 목록 한 페이지(20편).
     * category: top_rated, popular, on_the_air, korean, korean_now, animation, trending_day, trending_week
     */
    public List<TmdbTv> listTv(String category, int page) {
        if (!isEnabled()) {
            return List.of();
        }
        try {
            TvListResponse res = restClient.get()
                    .uri(u -> {
                        var b = u.path(tvPathOf(category));
                        b.queryParam("language", "ko-KR").queryParam("page", page);
                        if ("korean_now".equals(category)) {
                            LocalDate today = LocalDate.now();
                            b.queryParam("with_original_language", "ko")
                                    .queryParam("first_air_date.gte", today.minusDays(120))
                                    .queryParam("first_air_date.lte", today.plusDays(14))
                                    .queryParam("sort_by", "popularity.desc");
                        } else if ("korean".equals(category)) {
                            b.queryParam("with_original_language", "ko")
                                    .queryParam("sort_by", "vote_count.desc")
                                    .queryParam("vote_count.gte", 15);
                        } else if ("animation".equals(category)) {
                            b.queryParam("with_genres", "16")
                                    .queryParam("sort_by", "vote_count.desc")
                                    .queryParam("vote_count.gte", 50);
                        }
                        if (!bearer) {
                            b.queryParam("api_key", apiKey);
                        }
                        return b.build();
                    })
                    .headers(h -> {
                        if (bearer) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(TvListResponse.class);
            return res == null || res.results() == null ? List.of() : res.results();
        } catch (RestClientException e) {
            log.warn("TMDB TV 호출 실패({} {}쪽): {}", category, page, e.getMessage());
            return List.of();
        }
    }

    private static String tvPathOf(String category) {
        return switch (category) {
            case "korean", "korean_now", "animation" -> "/discover/tv";
            case "trending_day" -> "/trending/tv/day";
            case "trending_week" -> "/trending/tv/week";
            default -> "/tv/" + category;
        };
    }

    /** TV 시리즈 한 편의 상세 정보 (색인용: 크리에이터/연출, 출연, 방송사/OTT, 시즌/회차, 키워드) */
    public Optional<TvDetail> detailTv(int tvId) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            TvDetailResponse res = restClient.get()
                    .uri(u -> {
                        var b = u.path("/tv/" + tvId)
                                .queryParam("language", "ko-KR")
                                .queryParam("append_to_response", "credits,keywords");
                        if (!bearer) {
                            b.queryParam("api_key", apiKey);
                        }
                        return b.build();
                    })
                    .headers(h -> {
                        if (bearer) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(TvDetailResponse.class);
            if (res == null) {
                return Optional.empty();
            }
            String creator = extractCreator(res);
            List<String> cast = res.credits() == null || res.credits().cast() == null ? List.of() : res.credits().cast().stream()
                    .map(Person::name).filter(n -> n != null && !n.isBlank()).limit(6).toList();
            List<String> keywords = res.keywords() == null || res.keywords().results() == null ? List.of()
                    : res.keywords().results().stream().map(Keyword::name).filter(n -> n != null && !n.isBlank())
                    .limit(10).toList();
            String networks = res.networks() == null ? "" : res.networks().stream()
                    .map(Network::name).filter(n -> n != null && !n.isBlank()).distinct().limit(3)
                    .collect(Collectors.joining(", "));
            String networkLogo = extractNetworkLogo(res);
            return Optional.of(new TvDetail(res.tagline(), res.numberOfSeasons(), res.numberOfEpisodes(),
                    creator, cast, networks, networkLogo, keywords));
        } catch (RestClientException e) {
            log.warn("TMDB TV 상세 호출 실패(id {}): {}", tvId, e.getMessage());
            return Optional.empty();
        }
    }

    /** TV 시리즈 한 편의 전체 정보 (상세 페이지용) */
    public Optional<TvFull> tv(int tvId) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            TvDetailResponse res = restClient.get()
                    .uri(u -> {
                        var b = u.path("/tv/" + tvId)
                                .queryParam("language", "ko-KR")
                                .queryParam("append_to_response", "credits,keywords");
                        if (!bearer) {
                            b.queryParam("api_key", apiKey);
                        }
                        return b.build();
                    })
                    .headers(h -> {
                        if (bearer) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(TvDetailResponse.class);
            if (res == null || res.name() == null) {
                return Optional.empty();
            }
            String creator = extractCreator(res);
            List<String> cast = res.credits() == null || res.credits().cast() == null ? List.of() : res.credits().cast().stream()
                    .map(Person::name).filter(n -> n != null && !n.isBlank()).limit(10).toList();
            List<String> keywords = res.keywords() == null || res.keywords().results() == null ? List.of()
                    : res.keywords().results().stream().map(Keyword::name).filter(n -> n != null && !n.isBlank())
                    .limit(10).toList();
            String genres = res.genres() == null ? "" : res.genres().stream().map(Genre::name).collect(Collectors.joining(", "));
            String networks = res.networks() == null ? "" : res.networks().stream()
                    .map(Network::name).filter(n -> n != null && !n.isBlank()).distinct().limit(4)
                    .collect(Collectors.joining(", "));
            String networkLogo = extractNetworkLogo(res);
            Integer year = null;
            if (res.firstAirDate() != null && res.firstAirDate().length() >= 4) {
                try {
                    year = Integer.valueOf(res.firstAirDate().substring(0, 4));
                } catch (NumberFormatException ignored) {
                }
            }
            return Optional.of(new TvFull(tvId, res.name(), res.originalName(), res.overview(), year,
                    res.numberOfSeasons(), res.numberOfEpisodes(), genres, networks, networkLogo, res.voteAverage(),
                    res.posterPath(), res.backdropPath(), res.tagline(), creator, cast, keywords));
        } catch (RestClientException e) {
            log.warn("TMDB TV 상세 페이지 호출 실패(id {}): {}", tvId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String extractNetworkLogo(TvDetailResponse res) {
        if (res.networks() == null) {
            return "";
        }
        return res.networks().stream()
                .map(Network::logoPath)
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String extractCreator(TvDetailResponse res) {
        if (res.createdBy() != null && !res.createdBy().isEmpty()) {
            String creators = res.createdBy().stream()
                    .map(Person::name).filter(n -> n != null && !n.isBlank()).distinct().limit(2)
                    .collect(Collectors.joining(", "));
            if (!creators.isBlank()) {
                return creators;
            }
        }
        if (res.credits() != null && res.credits().crew() != null) {
            return res.credits().crew().stream()
                    .filter(p -> ("Director".equals(p.job()) || "Series Director".equals(p.job()) || "Writer".equals(p.job())) && p.name() != null)
                    .map(Person::name).distinct().limit(2)
                    .collect(Collectors.joining(", "));
        }
        return "";
    }

    /** TV 장르 id -> 한국어 이름 */
    public Map<Integer, String> tvGenres() {
        Map<Integer, String> cached = tvGenreNames;
        if (cached != null) {
            return cached;
        }
        if (!isEnabled()) {
            return Map.of();
        }
        try {
            GenreResponse res = restClient.get()
                    .uri(u -> {
                        var b = u.path("/genre/tv/list").queryParam("language", "ko-KR");
                        if (!bearer) {
                            b.queryParam("api_key", apiKey);
                        }
                        return b.build();
                    })
                    .headers(h -> {
                        if (bearer) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(GenreResponse.class);
            Map<Integer, String> map = new HashMap<>();
            if (res != null && res.genres() != null) {
                res.genres().forEach(g -> map.put(g.id(), g.name()));
            }
            tvGenreNames = map;
            return map;
        } catch (RestClientException e) {
            log.warn("TMDB TV 장르 목록 호출 실패: {}", e.getMessage());
            return Map.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TmdbMovie(
            int id,
            String title,
            @JsonProperty("original_title") String originalTitle,
            String overview,
            @JsonProperty("release_date") String releaseDate,
            @JsonProperty("genre_ids") List<Integer> genreIds,
            @JsonProperty("vote_average") Double voteAverage,
            @JsonProperty("vote_count") Integer voteCount,
            @JsonProperty("poster_path") String posterPath) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TmdbTv(
            int id,
            String name,
            @JsonProperty("original_name") String originalName,
            String overview,
            @JsonProperty("first_air_date") String firstAirDate,
            @JsonProperty("genre_ids") List<Integer> genreIds,
            @JsonProperty("vote_average") Double voteAverage,
            @JsonProperty("vote_count") Integer voteCount,
            @JsonProperty("poster_path") String posterPath) {
    }

    /** 영화 상세 페이지용 전체 정보 (한 번의 호출로 감독/출연/키워드까지) */
    public record MovieFull(int id, String title, String originalTitle, String overview, Integer year, Integer runtime,
                            String genres, Double rating, String posterPath, String backdropPath, String tagline, String director,
                            List<String> cast, List<String> keywords) {
    }

    /** TV 시리즈 상세 페이지용 전체 정보 */
    public record TvFull(int id, String title, String originalTitle, String overview, Integer year,
                         Integer seasons, Integer episodes, String genres, String networks, String networkLogo, Double rating,
                         String posterPath, String backdropPath, String tagline, String creator,
                         List<String> cast, List<String> keywords) {
    }

    /** 영화 상세에서 쓰는 값: 한 줄 소개, 상영 시간(분), 감독, 출연(상위 6명), 키워드(상위 10개) */
    public record MovieDetail(String tagline, Integer runtime, String director, List<String> cast, List<String> keywords) {
    }

    /** TV 상세에서 쓰는 값: 한 줄 소개, 시즌 수, 회차 수, 제작/연출, 출연(상위 6명), 방송사/OTT, 로고 경로, 키워드(상위 10개) */
    public record TvDetail(String tagline, Integer seasons, Integer episodes, String creator,
                           List<String> cast, String networks, String networkLogo, List<String> keywords) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DetailResponse(String title, @JsonProperty("original_title") String originalTitle, String overview,
                                  @JsonProperty("release_date") String releaseDate, List<Genre> genres,
                                  @JsonProperty("vote_average") Double voteAverage,
                                  @JsonProperty("poster_path") String posterPath,
                                  @JsonProperty("backdrop_path") String backdropPath,
                                  String tagline, Integer runtime, Credits credits, Keywords keywords) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TvDetailResponse(String name, @JsonProperty("original_name") String originalName, String overview,
                                    @JsonProperty("first_air_date") String firstAirDate, List<Genre> genres,
                                    @JsonProperty("vote_average") Double voteAverage,
                                    @JsonProperty("poster_path") String posterPath,
                                    @JsonProperty("backdrop_path") String backdropPath,
                                    String tagline,
                                    @JsonProperty("number_of_seasons") Integer numberOfSeasons,
                                    @JsonProperty("number_of_episodes") Integer numberOfEpisodes,
                                    @JsonProperty("created_by") List<Person> createdBy,
                                    List<Network> networks,
                                    Credits credits, TvKeywords keywords) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Credits(List<Person> cast, List<Person> crew) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Person(String name, String job) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Keywords(List<Keyword> keywords) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TvKeywords(List<Keyword> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Keyword(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Network(String name, @JsonProperty("logo_path") String logoPath) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ListResponse(List<TmdbMovie> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TvListResponse(List<TmdbTv> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Genre(int id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GenreResponse(List<Genre> genres) {
    }
}
