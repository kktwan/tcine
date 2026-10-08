package com.t.tcine.domain.movie.service;

import com.t.tcine.domain.movie.dto.MovieResult;
import com.t.tcine.domain.movie.dto.MovieResult.MovieCard;
import com.t.tcine.domain.search.MediaKind;
import com.t.tcine.domain.search.query.QueryAnalyzer;
import com.t.tcine.domain.search.ranking.RankingEngine;
import com.t.tcine.domain.search.recommend.AbstractRecommendService;
import com.t.tcine.domain.search.recommend.RecommendMessages;
import com.t.tcine.infra.tmdb.TmdbClient.MovieFull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.t.tcine.domain.search.ranking.DocFields.*;

/** 영화 AI 추천. 검색·재순위·Gemini 흐름은 {@link AbstractRecommendService}에 있고, 여기엔 영화에만 해당하는 부분만 둔다. */
@Service
public class MovieRecommendService extends AbstractRecommendService<MovieResult, MovieCard> {

    private static final RecommendMessages MESSAGES = new RecommendMessages(
            "OpenAI 키가 아직 설정되지 않았어요. (영화 검색의 임베딩에 필요해요)",
            "일치하는 영화가 없어요. 제목·배우·장르를 확인해 주세요.",
            "아직 영화 데이터가 준비되지 않았어요. 잠시 후 다시 이용해 주세요.",
            "영화 검색을 사용할 수 없어요. 잠시 후 다시 시도해 주세요.",
            "색인된 영화가 없어요. 관리자가 영화 데이터를 먼저 쌓아야 해요.",
            "영화 데이터베이스(Qdrant)에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",
            "아직 색인된 영화가 없어요. 관리자가 영화 데이터를 먼저 쌓아야 해요.",
            "AI 응답이 늦어서, 의미가 비슷한 영화를 그대로 보여드려요.",
            "AI 설명을 만들지 못해서, 의미가 비슷한 영화를 그대로 보여드려요.",
            "AI가 고르지 못해서, 의미가 비슷한 영화를 그대로 보여드려요.",
            "영화 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.",
            "이 영화는 비교할 정보가 부족해요.",
            "\"%s\"와 세계관·분위기·장르·서사 결이 비슷한 다른 영화 (기준 영화 및 동일 시리즈 제외).\n[기준 영화 정보]\n%s",
            "\"%s\"와 세계관·분위기·하위 장르·서사 구조가 비슷한 다른 영화 (기준 영화 및 같은 시리즈는 반드시 제외).\n[기준 영화 정보]\n%s",
            "[후보 영화]",
            "검색어와 관련 있는 영화를 찾지 못했어요. 작품명·배우·분위기를 바꿔서 다시 검색해 보세요.");

    private final MovieDetailService detailService;
    private final QueryAnalyzer analyzer;

    public MovieRecommendService(VectorStore vectorStore, MovieCorpusManager corpusManager, MovieIndexService indexService,
                                 MovieDetailService detailService, ChatClient.Builder builder,
                                 QueryAnalyzer analyzer, RankingEngine ranking,
                                 @Value("classpath:prompts/movie-curator.st") Resource promptResource,
                                 @Value("${ai.api-key:}") String apiKey,
                                 @Value("${spring.ai.openai.api-key:}") String openAiKey,
                                 @Value("${movie.daily-limit:30}") int dailyLimit) {
        super(MediaKind.MOVIE, "영화", "movie-recommend", MESSAGES, vectorStore, corpusManager, indexService::count,
                builder.build(), promptResource, analyzer, ranking, apiKey, openAiKey, dailyLimit);
        this.detailService = detailService;
        this.analyzer = analyzer;
    }

    public MovieResult recommendSimilar(String username, int movieId) {
        Optional<MovieFull> found = detailService.get(movieId);
        if (found.isEmpty()) return emptyResult(detailMissingMessage());
        MovieFull m = found.get();
        return recommendSimilarTo(username, "similar:" + movieId, movieId, m.title(), m.genres(), m.keywords(), m.tagline(), m.overview());
    }

    /** "해리포터 시리즈 전체" 같은 요청은 AI 없이 제목으로 찾아 모두 보여 준다 */
    @Override
    protected MovieResult handleBeforeRun(String q) {
        if (analyzer.isSeriesRequest(q)) {
            String titleQuery = analyzer.seriesTitleQuery(q);
            if (!titleQuery.isBlank()) return searchFast(titleQuery);
        }
        return null;
    }

    /** 영화는 "제목: 부제", "제목 - 부제", "제목 2" 에서 앞의 제목만 시리즈 줄기로 본다 */
    @Override
    protected String seriesStem(String title) {
        if (title == null || title.isBlank()) return "";
        String stem = title.split("[:\\-–—]")[0].replaceAll("\\s+[0-9]+$", "").trim();
        String c = QueryAnalyzer.compact(stem);
        return c.length() >= 2 ? c : "";
    }

    @Override
    protected MovieResult newResult(String summary, List<MovieCard> cards, boolean ai, String message) {
        return new MovieResult(summary, cards, ai, message);
    }

    @Override
    protected String candidateLine(int id, Document doc) {
        Map<String, Object> m = doc.getMetadata();
        String keywords = keywordsOf(doc);
        StringBuilder sb = new StringBuilder();
        sb.append(id).append('|').append(str(m.get("title")))
                .append('|').append(intOf(m.get("year"))).append('|').append(str(m.get("director")))
                .append('|').append(shorten(str(m.get("cast")), 28)).append('|').append(str(m.get("genres")));
        if (!keywords.isBlank()) sb.append('|').append(shorten(keywords, 40));
        sb.append('|').append(String.format("%.1f", doubleOf(m.get("rating"))))
                .append('|').append(shorten(str(m.get("overview")), 75));
        return sb.toString();
    }

    @Override
    protected MovieCard card(int id, Document doc, String reason) {
        Map<String, Object> m = doc.getMetadata();
        String poster = str(m.get("poster"));
        int year = intOf(m.get("year"));
        return new MovieCard(id, str(m.get("title")), str(m.get("originalTitle")),
                year > 0 ? year : null, str(m.get("genres")), doubleOf(m.get("rating")),
                str(m.get("overview")), poster.isBlank() ? null : POSTER_BASE + poster,
                "https://www.themoviedb.org/movie/" + id, reason,
                str(m.get("director")), str(m.get("cast")), intOf(m.get("runtime")) > 0 ? intOf(m.get("runtime")) : null);
    }
}
