package com.t.tcine.infra.kobis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class KobisClient {
    private final RestClient client;
    private final String key;
    public KobisClient(RestClient.Builder builder, @Value("${kobis.api-key:}") String key) {
        this.client = builder.baseUrl("https://www.kobis.or.kr").build();
        this.key = key == null ? "" : key.trim();
    }
    public List<BoxOfficeMovie> dailyMovies() {
        if (key.isBlank()) return List.of();
        String date = LocalDate.now().minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            Response response = client.get().uri(u -> u.path("/kobisopenapi/webservice/rest/boxoffice/searchDailyBoxOfficeList.json")
                    .queryParam("key", key).queryParam("targetDt", date).build()).retrieve().body(Response.class);
            return response == null || response.boxOfficeResult() == null || response.boxOfficeResult().dailyBoxOfficeList() == null
                    ? List.of() : response.boxOfficeResult().dailyBoxOfficeList().stream()
                    .map(i -> new BoxOfficeMovie(i.movieNm(), parseLong(i.audiAcc()))).toList();
        } catch (RestClientException e) { return List.of(); }
    }
    private static long parseLong(String value) { try { return Long.parseLong(value); } catch (Exception e) { return 0; } }
    public record BoxOfficeMovie(String title, long audienceCount) {}
    @JsonIgnoreProperties(ignoreUnknown = true) private record Response(BoxOfficeResult boxOfficeResult) {}
    @JsonIgnoreProperties(ignoreUnknown = true) private record BoxOfficeResult(List<Item> dailyBoxOfficeList) {}
    @JsonIgnoreProperties(ignoreUnknown = true) private record Item(String movieNm, String audiAcc) {}
}
