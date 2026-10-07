package com.t.tcine.domain.tv.service;

import com.t.tcine.domain.search.MediaKind;
import com.t.tcine.domain.search.query.QueryAnalyzer;
import com.t.tcine.domain.search.ranking.RankingEngine;
import com.t.tcine.domain.search.recommend.AbstractRecommendService;
import com.t.tcine.domain.search.recommend.RecommendMessages;
import com.t.tcine.domain.tv.dto.TvResult;
import com.t.tcine.domain.tv.dto.TvResult.TvCard;
import com.t.tcine.infra.tmdb.TmdbClient.TvFull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.t.tcine.domain.search.ranking.DocFields.*;

/** 시리즈(드라마·예능·애니) AI 추천. 검색·재순위·Gemini 흐름은 {@link AbstractRecommendService}에 있고, 여기엔 시리즈에만 해당하는 부분만 둔다. */
@Service
public class TvRecommendService extends AbstractRecommendService<TvResult, TvCard> {

    private static final RecommendMessages MESSAGES = new RecommendMessages(
            "OpenAI 키가 아직 설정되지 않았어요. (시리즈 검색의 임베딩에 필요해요)",
            "일치하는 시리즈가 없어요. 제목·배우·제작진·OTT를 확인해 주세요.",
            "아직 색인된 시리즈 데이터가 없어요. 관리자가 시리즈 데이터를 먼저 쌓아야 해요.",
            "시리즈 검색을 사용할 수 없어요. 잠시 후 다시 시도해 주세요.",
            "색인된 시리즈가 없어요. 관리자가 시리즈 데이터를 먼저 쌓아야 해요.",
            "시리즈 데이터베이스(Qdrant)에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",
            "아직 색인된 시리즈가 없어요. 관리자가 시리즈 데이터를 먼저 쌓아야 해요.",
            "AI 응답이 늦어서, 의미가 비슷한 시리즈를 그대로 보여드려요.",
            "AI 설명을 만들지 못해서, 의미가 비슷한 시리즈를 그대로 보여드려요.",
            "AI가 고르지 못해서, 의미가 비슷한 시리즈를 그대로 보여드려요.",
            "시리즈 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.",
            "이 작품은 비교할 정보가 부족해요.",
            "\"%s\"와 세계관·분위기·장르·서사 결이 비슷한 다른 시리즈 (기준 작품 제외).\n[기준 작품 정보]\n%s",
            "\"%s\"와 세계관·분위기·하위 장르·서사 구조가 비슷한 다른 시리즈 (기준 작품은 반드시 제외).\n[기준 작품 정보]\n%s",
            "[후보 시리즈]");

    private final TvDetailService detailService;

    public TvRecommendService(TvCorpusManager corpusManager, TvIndexService indexService,
                              TvDetailService detailService, ChatClient.Builder builder,
                              QueryAnalyzer analyzer, RankingEngine ranking,
                              @Value("classpath:prompts/tv-curator.st") Resource promptResource,
                              @Value("${ai.api-key:}") String apiKey,
                              @Value("${spring.ai.openai.api-key:}") String openAiKey,
                              @Value("${movie.daily-limit:30}") int dailyLimit) {
        super(MediaKind.TV, "시리즈", "tv-recommend", MESSAGES, indexService.vectorStore(), corpusManager, indexService::count,
                builder.build(), promptResource, analyzer, ranking, apiKey, openAiKey, dailyLimit);
        this.detailService = detailService;
    }

    public TvResult recommendSimilar(String username, int tvId) {
        Optional<TvFull> found = detailService.get(tvId);
        if (found.isEmpty()) return emptyResult(detailMissingMessage());
        TvFull t = found.get();
        return recommendSimilarTo(username, "similar-tv:" + tvId, tvId, t.title(), t.genres(), t.keywords(), t.tagline(), t.overview());
    }

    /** 시리즈는 제목 전체를 기준으로 같은 작품을 걸러 낸다 */
    @Override
    protected String seriesStem(String title) {
        return QueryAnalyzer.compact(title);
    }

    @Override
    protected TvResult newResult(String summary, List<TvCard> cards, boolean ai, String message) {
        return new TvResult(summary, cards, ai, message);
    }

    @Override
    protected String candidateLine(int id, Document doc) {
        Map<String, Object> m = doc.getMetadata();
        String keywords = keywordsOf(doc);
        StringBuilder sb = new StringBuilder();
        sb.append(id).append('|').append(str(m.get("title")))
                .append('|').append(intOf(m.get("year"))).append('|').append(str(m.get("networks")))
                .append('|').append(str(m.get("creator"))).append('|').append(shorten(str(m.get("cast")), 28))
                .append('|').append(str(m.get("genres")));
        if (!keywords.isBlank()) sb.append('|').append(shorten(keywords, 40));
        sb.append('|').append(String.format("%.1f", doubleOf(m.get("rating"))))
                .append('|').append(shorten(str(m.get("overview")), 75));
        return sb.toString();
    }

    @Override
    protected TvCard card(int id, Document doc, String reason) {
        Map<String, Object> m = doc.getMetadata();
        String poster = str(m.get("poster"));
        int year = intOf(m.get("year"));
        int seasons = intOf(m.get("seasons"));
        int episodes = intOf(m.get("episodes"));
        return new TvCard(id, str(m.get("title")), str(m.get("originalTitle")),
                year > 0 ? year : null, str(m.get("genres")), doubleOf(m.get("rating")),
                str(m.get("overview")), poster.isBlank() ? null : POSTER_BASE + poster,
                "https://www.themoviedb.org/tv/" + id, reason,
                str(m.get("creator")), str(m.get("cast")), str(m.get("networks")),
                seasons > 0 ? seasons : null, episodes > 0 ? episodes : null);
    }
}
