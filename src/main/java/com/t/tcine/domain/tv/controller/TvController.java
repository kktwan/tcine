package com.t.tcine.domain.tv.controller;

import com.t.tcine.domain.movie.dto.IndexStatus;
import com.t.tcine.domain.tv.dto.TvResult;
import com.t.tcine.domain.tv.service.TvDetailService;
import com.t.tcine.domain.tv.service.TvHomeService;
import com.t.tcine.domain.tv.service.TvIndexService;
import com.t.tcine.domain.tv.service.TvRecommendService;
import com.t.tcine.infra.tmdb.TmdbClient.TvFull;
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
public class TvController {

    private final TvRecommendService recommendService;
    private final TvIndexService indexService;
    private final TvHomeService homeService;
    private final TvDetailService detailService;
    private final String admin;

    public TvController(TvRecommendService recommendService, TvIndexService indexService,
                        TvHomeService homeService, TvDetailService detailService,
                        @Value("${movie.admin:}") String admin) {
        this.recommendService = recommendService;
        this.indexService = indexService;
        this.homeService = homeService;
        this.detailService = detailService;
        this.admin = admin == null ? "" : admin.trim();
    }

    /** 시리즈(드라마·예능·애니) 추천 화면 */
    @GetMapping("/tv")
    public String tv(@RequestParam(required = false) String q,
                     @RequestParam(required = false) Integer similar,
                     Principal principal, Model model) {
        boolean isAdmin = isAdmin(principal);
        boolean searching = similar != null || StringUtils.hasText(q);
        String shownQuery = similar == null ? q
                : detailService.get(similar).map(t -> t.title() + "와 비슷한 시리즈").orElse(q);
        model.addAttribute("q", shownQuery);
        model.addAttribute("aiEnabled", recommendService.isEnabled());
        model.addAttribute("isAdmin", isAdmin);
        TvResult result = null;
        if (similar != null) {
            result = recommendService.recommendSimilar(principal.getName(), similar);
        } else if (StringUtils.hasText(q)) {
            result = recommendService.recommend(principal.getName(), q);
        }
        model.addAttribute("result", result);
        if (!searching) {
            model.addAttribute("koreanNow", homeService.koreanNow());
            model.addAttribute("trending", homeService.trending());
            model.addAttribute("koreanPopular", homeService.koreanPopular());
        }
        if (isAdmin) {
            model.addAttribute("indexedCount", indexService.count());
            model.addAttribute("indexStatus", indexService.status());
        }
        return "tv";
    }

    /** 시리즈 상세 페이지 */
    @GetMapping("/tv/{id:\\d+}")
    public String detail(@PathVariable int id, Model model) {
        TvFull tv = detailService.get(id).orElse(null);
        model.addAttribute("tv", tv);
        return "tv-detail";
    }

    /** 관리자: TMDB에서 TV 시리즈를 가져와 Qdrant에 색인 시작 */
    @PostMapping("/tv/index")
    public String startIndex(@RequestParam(defaultValue = "10") int pages, Principal principal, RedirectAttributes ra) {
        if (!isAdmin(principal)) {
            return "redirect:/tv";
        }
        ra.addFlashAttribute("message", indexService.start(pages)
                ? "시리즈 색인을 시작했어요. 몇 분 걸릴 수 있어요." : "이미 시리즈 색인이 진행 중이에요.");
        return "redirect:/tv";
    }

    /** 관리자: 시리즈 색인 진행 상태 조회 */
    @GetMapping("/tv/index-status")
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
