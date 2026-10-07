package com.t.tcine.domain.movie.controller;

import com.t.tcine.domain.movie.dto.IndexStatus;
import com.t.tcine.domain.movie.dto.MovieResult;
import com.t.tcine.domain.movie.service.MovieDetailService;
import com.t.tcine.domain.movie.service.MovieHomeService;
import com.t.tcine.infra.tmdb.TmdbClient.MovieFull;
import com.t.tcine.domain.movie.service.MovieIndexService;
import com.t.tcine.domain.movie.service.MovieRecommendService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
public class MovieController {

    private final MovieRecommendService recommendService;
    private final MovieIndexService indexService;
    private final MovieHomeService homeService;
    private final MovieDetailService detailService;
    /** 영화 데이터를 색인할 수 있는 사용자 아이디 (비어 있으면 아무도 못 함) */
    private final String admin;

    public MovieController(MovieRecommendService recommendService, MovieIndexService indexService,
                           MovieHomeService homeService, MovieDetailService detailService,
                           @Value("${movie.admin:}") String admin) {
        this.recommendService = recommendService;
        this.indexService = indexService;
        this.homeService = homeService;
        this.detailService = detailService;
        this.admin = admin == null ? "" : admin.trim();
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/movies";
    }

    /** 영화 추천: 문장(q)을 받으면 의미가 비슷한 영화를 찾아 AI가 골라 준다 */
    @GetMapping("/movies")
    public String movies(@RequestParam(required = false) String q,
                         @RequestParam(required = false) Integer similar,
                         @RequestParam(defaultValue = "ai") String mode,
                         Principal principal, Model model) {
        boolean isAdmin = isAdmin(principal);
        boolean fastSearch = false;
        boolean searching = similar != null || StringUtils.hasText(q);
        // similar = 특정 영화의 번호: 그 영화의 내용(장르·키워드·줄거리)과 비슷한 영화를 찾는다
        String shownQuery = similar == null ? q
                : detailService.get(similar).map(m -> m.title() + "와 비슷한 영화").orElse(q);
        model.addAttribute("q", shownQuery);
        model.addAttribute("mode", "ai");
        model.addAttribute("aiEnabled", recommendService.isEnabled());
        model.addAttribute("isAdmin", isAdmin);
        MovieResult result = null;
        if (similar != null) {
            result = recommendService.recommendSimilar(principal.getName(), similar);
        } else if (StringUtils.hasText(q)) {
            result = recommendService.recommend(principal.getName(), q);
        }
        model.addAttribute("result", result);
        if (!searching) {
            // 검색 전에는 지금 인기 있는 영화와 상영작을 보여준다 (TMDB, 30분 캐시)
            model.addAttribute("trending", homeService.trending());
            model.addAttribute("nowPlaying", homeService.nowPlaying());
            model.addAttribute("boxOffice", homeService.boxOffice());
            model.addAttribute("koreanNow", homeService.koreanNow());
        }
        if (isAdmin) {
            model.addAttribute("indexedCount", indexService.count());
            model.addAttribute("indexStatus", indexService.status());
        }
        return "movies";
    }

    /** 영화 상세: 포스터, 감독/출연, 줄거리 전체 (TMDB, 30분 캐시) */
    @GetMapping("/movies/{id:\\d+}")
    public String detail(@PathVariable int id, Model model) {
        MovieFull movie = detailService.get(id).orElse(null);
        model.addAttribute("movie", movie);
        model.addAttribute("similarQuery", movie == null ? null : movie.title() + "와 비슷한 영화");
        return "movie-detail";
    }

    /** 관리자: TMDB에서 영화를 가져와 색인 시작 (백그라운드) */
    @PostMapping("/movies/index")
    public String startIndex(@RequestParam(defaultValue = "10") int pages, Principal principal, RedirectAttributes ra) {
        if (!isAdmin(principal)) {
            return "redirect:/movies";
        }
        ra.addFlashAttribute("message", indexService.start(pages)
                ? "영화 색인을 시작했어요. 몇 분 걸릴 수 있어요." : "이미 색인이 진행 중이에요.");
        return "redirect:/movies";
    }

    /** 관리자: 색인 진행 상태 (화면이 주기적으로 조회) */
    @GetMapping("/movies/index-status")
    @ResponseBody
    public ResponseEntity<IndexStatus> indexStatus(Principal principal) {
        if (!isAdmin(principal)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(indexService.status());
    }

    private boolean isAdmin(Principal principal) {
        return principal != null && !admin.isEmpty() && admin.equalsIgnoreCase(principal.getName());
    }
}
