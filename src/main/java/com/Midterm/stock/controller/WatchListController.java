package com.Midterm.stock.controller;

import com.Midterm.stock.service.WatchListService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 관심종목 + 알림 REST API
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class WatchListController {

    private final WatchListService watchListService;

    // ── 관심종목 ─────────────────────────────────────────────

    /** 관심종목 추가 */
    @PostMapping("/watchlist")
    public Map<String, Object> addWatch(@RequestBody Map<String, String> body,
                                        HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) return Map.of("ok", false, "msg", "로그인 필요");

        boolean added = watchListService.addWatch(
                userNum, body.get("stockCode"), body.get("stockName"));
        return Map.of("ok", added, "msg", added ? "추가됨" : "이미 등록된 종목");
    }

    /** 관심종목 제거 */
    @DeleteMapping("/watchlist/{code}")
    public Map<String, Object> removeWatch(@PathVariable String code,
                                           HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) return Map.of("ok", false, "msg", "로그인 필요");

        watchListService.removeWatch(userNum, code);
        return Map.of("ok", true);
    }

    /** 관심종목 목록 조회 */
    @GetMapping("/watchlist")
    public List<Map<String, String>> getWatchList(HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) return List.of();

        return watchListService.getWatchList(userNum).stream().map(w -> Map.of(
                "stockCode", w.getStockCode(),
                "stockName", w.getStockName()
        )).collect(Collectors.toList());
    }

    /** 특정 종목 관심 여부 확인 */
    @GetMapping("/watchlist/check/{code}")
    public Map<String, Object> checkWatch(@PathVariable String code,
                                          HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) return Map.of("watching", false);
        return Map.of("watching", watchListService.isWatching(userNum, code));
    }

    // ── 알림 ─────────────────────────────────────────────────

    /** 알림 목록 + 미읽음 수 조회 */
    @GetMapping("/alerts")
    public Map<String, Object> getAlerts(HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) return Map.of("unreadCount", 0, "alerts", List.of());
        return watchListService.buildAlertPanel(userNum);
    }

    /** 전체 읽음 처리 */
    @PostMapping("/alerts/read")
    public Map<String, Object> markRead(HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) return Map.of("ok", false);
        watchListService.markAllRead(userNum);
        return Map.of("ok", true);
    }




    @PostMapping("/user/settings/notification")
    public Map<String, Object> updateNotifySetting(@RequestBody Map<String, Object> body,
                                                   HttpSession session) {
        Integer userNum = (Integer) session.getAttribute("loginNum");
        if (userNum == null) return Map.of("ok", false);

        String type = (String) body.get("type"); // "stock" 또는 "comment"
        int status = (int) body.get("status");   // 0 또는 1

        watchListService.updateNotifySetting(userNum, type, status);

        return Map.of("ok", true);
    }
}
