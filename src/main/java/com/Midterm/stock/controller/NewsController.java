package com.Midterm.stock.controller;

import com.Midterm.stock.dto.AiPredictionDto;
import com.Midterm.stock.dto.NewsDto;
import com.Midterm.stock.repository.NewsDao;
import com.Midterm.stock.service.stock.StockAiService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/news")
public class NewsController {

    @Autowired
    private NewsDao newsDao;

    @Autowired
    private StockAiService stockAiService;

    // 뉴스 목록, 필터, 사이드바 분석 데이터를 조합해 뉴스 화면을 렌더링
    @GetMapping({"", "/"})
    public String newsList(@RequestParam(required = false) String sector,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "1") int page,
                           HttpSession session,
                           Model model) {

        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }

        Integer userNum = (Integer) session.getAttribute("loginNum");
        int uid = userNum != null ? userNum : 0;

        int totalCount = newsDao.getNewsCount(sector, keyword);
        int pageSize = 3;
        int visiblePages = 7;

        int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / pageSize));
        int currentPage = Math.max(1, Math.min(page, totalPages));

        int start = (currentPage - 1) * pageSize + 1;
        int end = currentPage * pageSize;

        int startPage = Math.max(1, currentPage - 3);
        int endPage = Math.min(totalPages, startPage + visiblePages - 1);

        if (endPage - startPage < visiblePages - 1) {
            startPage = Math.max(1, endPage - visiblePages + 1);
        }

        List<NewsDto> newsList = newsDao.getNewsList(sector, keyword, start, end, uid);
        enrichPredictionSignals(newsList);

        LinkedHashMap<String, List<String>> sectorMap = newsDao.getSidebarSectorMap();

        Map<String, Object> sidebarAnalysis = newsDao.getSidebarAnalysis(sector, keyword);
        int saTotal = (int) sidebarAnalysis.getOrDefault("totalCount", 0);
        double saType = (double) sidebarAnalysis.getOrDefault("avgTypeProb", 0.0);
        double saNoise = (double) sidebarAnalysis.getOrDefault("avgClickbaitProb", 0.0);
        int saPos = (int) sidebarAnalysis.getOrDefault("positiveCount", 0);
        int saNeg = (int) sidebarAnalysis.getOrDefault("negativeCount", 0);
        int saPosRatio = saTotal > 0 ? (int) Math.round((double) saPos / saTotal * 100) : 50;

        model.addAttribute("newsList", newsList);
        model.addAttribute("sectorMap", sectorMap);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("currentPageNum", currentPage);
        model.addAttribute("startPage", startPage);
        model.addAttribute("endPage", endPage);
        model.addAttribute("sector", sector);
        model.addAttribute("keyword", keyword);

        model.addAttribute("saTotal", saTotal);
        model.addAttribute("saType", String.format("%.1f", saType));
        model.addAttribute("saNoise", String.format("%.1f", saNoise));
        model.addAttribute("saPosRatio", saPosRatio);
        model.addAttribute("saNegRatio", saTotal > 0 ? (int) Math.round((double) saNeg / saTotal * 100) : 50);
        model.addAttribute("currentMenu", "news");

        return "news/list";
    }

    // 기사 목록에 영향도와 상승 예측 신호를 순서대로 보강
    private void enrichPredictionSignals(List<NewsDto> newsList) {
        if (newsList == null || newsList.isEmpty()) {
            return;
        }

        applyStoredImpactSignals(newsList);
        applyArticleImpactFallback(newsList);
        applyStockPredictions(newsList);
    }

    // 이미 저장된 기사 영향도와 연결 종목이 있으면 그 값을 먼저 사한다.
    // 이미 저장된 기사 영향도와 종목 매핑이 있으면 우선 적용
    private void applyStoredImpactSignals(List<NewsDto> newsList) {
        List<String> links = new ArrayList<>();
        for (NewsDto dto : newsList) {
            if (dto.getLink() != null && !dto.getLink().isBlank()) {
                links.add(dto.getLink());
            }
        }

        Map<String, Map<String, Object>> signalMap = newsDao.getNewsSignalMap(links);
        for (NewsDto dto : newsList) {
            Map<String, Object> signal = signalMap.get(dto.getLink());
            if (signal == null) {
                continue;
            }

            Object stockCode = signal.get("stockCode");
            if (stockCode != null) {
                dto.setStockCode(stockCode.toString());
            }

            Object impact30m = signal.get("impact30m");
            if (impact30m instanceof Number number) {
                dto.setStockImpactPercent(number.doubleValue());
                dto.setStockImpactSource("모델 추정값");
            }
        }
    }

    // 저장된 값이 없을 때만 기사 단위 모델을 돌리고, 계산 결과는 다시 저장해 다음 조회를 빠르게 만듦
    // 저장된 영향도가 없을 때만 기사 영향도 모델을 돌리고 결과를 캐시 테이블에 남김
    private void applyArticleImpactFallback(List<NewsDto> newsList) {
        for (NewsDto dto : newsList) {
            if (dto.hasStockImpactPrediction()) {
                continue;
            }

            String stockCode = dto.getStockCode();
            if (stockCode == null || stockCode.isBlank()) {
                continue;
            }

            Double modelImpact = stockAiService.predictArticleImpact(dto);
            if (modelImpact == null) {
                continue;
            }

            dto.setStockImpactPercent(modelImpact);
            dto.setStockImpactSource("기사 영향 추정 모델");
            newsDao.saveNewsImpact(dto.getLink(), stockCode, modelImpact);
        }
    }

    // 익일 상승 확률은 종목 단위 예측이므로 종목코드별로 한 번만 호출해서 재사용
    // 종목별 AI 예측 결과를 한 번만 계산해 같은 종목 기사들에 재사용
    private void applyStockPredictions(List<NewsDto> newsList) {
        Map<String, AiPredictionDto> stockPredictions = new HashMap<>();

        for (NewsDto dto : newsList) {
            String stockCode = dto.getStockCode();
            if (stockCode == null || stockCode.isBlank() || stockPredictions.containsKey(stockCode)) {
                continue;
            }

            try {
                stockPredictions.put(stockCode, stockAiService.predict(stockCode));
            } catch (Exception ignored) {
                stockPredictions.put(stockCode, null);
            }
        }

        for (NewsDto dto : newsList) {
            AiPredictionDto prediction = stockPredictions.get(dto.getStockCode());
            if (prediction == null || !prediction.isValid()) {
                continue;
            }

            dto.setRiseProbability(prediction.getProbability());
            dto.setRisePrediction(prediction.getPrediction());
            dto.setRiseConfidence(prediction.getConfidence());
        }
    }

    // 뉴스 좋아요 상태를 토글하고 최신 카운트를 반환
    @PostMapping("/like")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleLike(@RequestParam("link") String link, HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) {
            return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다."));
        }

        int newCount = newsDao.toggleLike(link, userNum);
        Map<String, Object> info = newsDao.getLikeInfo(link, userNum);
        Map<String, Object> resp = new HashMap<>();
        resp.put("count", newCount);
        resp.put("liked", info.get("liked"));
        return ResponseEntity.ok(resp);
    }

    // 특정 뉴스의 댓글 목록을 조회
    @GetMapping("/comments")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getComments(@RequestParam("link") String link, HttpSession session) {
        if (session.getAttribute("loginUser") == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(newsDao.getComments(link));
    }

    // 특정 뉴스에 새 댓글을 등록하고 갱신된 댓글 목록을 반환
    @PostMapping("/comments")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addComment(@RequestParam("link") String link,
                                                          @RequestParam("content") String content,
                                                          HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) {
            return ResponseEntity.status(401).body(Map.of("error", "로그인이 필요합니다."));
        }
        if (content == null || content.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "내용을 입력해주세요"));
        }

        int result = newsDao.insertComment(link, userNum, content.trim());
        if (result > 0) {
            return ResponseEntity.ok(Map.of("success", true, "comments", newsDao.getComments(link)));
        }
        return ResponseEntity.internalServerError().body(Map.of("error", "등록 실패"));
    }

    // 본인이 작성한 댓글을 삭제
    @DeleteMapping("/comments/{commentId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteComment(@PathVariable int commentId, HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(Map.of("success", newsDao.deleteComment(commentId, userNum) > 0));
    }
}
