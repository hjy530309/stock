package com.Midterm.stock.controller;

import com.Midterm.stock.dto.AiPredictionDto;
import com.Midterm.stock.dto.StockResponseDto;
import com.Midterm.stock.dto.UserDto;
import com.Midterm.stock.repository.NewsDao;
import com.Midterm.stock.repository.UserDao;
import com.Midterm.stock.service.WatchListService;
import com.Midterm.stock.service.stock.ExchangeService;
import com.Midterm.stock.service.stock.StockAiService;
import com.Midterm.stock.service.stock.StockPriceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class PageController {

    @Autowired
    private StockPriceService stockPriceService;

    @Autowired
    private ExchangeService exchangeService;

    @Autowired
    private StockAiService stockAiService;

    @Autowired
    private UserDao userDao;

    @Autowired
    private NewsDao newsDao;

    @Autowired
    private WatchListService watchListService;

    private static final List<String[]> FIXED_SECTORS = Arrays.asList(
            new String[]{"IT/반도체", "IT·반도체"},
            new String[]{"2차전지", "2차전지"},
            new String[]{"제약/바이오", "제약·바이오"},
            new String[]{"자동차/모빌리티", "자동차·모빌리티"},
            new String[]{"IT/플랫폼", "IT·플랫폼"},
            new String[]{"금융/밸류업", "금융·밸류업"},
            new String[]{"방산/우주항공", "방산·우주항공"},
            new String[]{"엔터/미디어", "엔터·미디어"}
    );

    private static final Map<String, String> SECTOR_ICON = new HashMap<>();

    static {
        SECTOR_ICON.put("IT/반도체", "💻");
        SECTOR_ICON.put("2차전지", "🔋");
        SECTOR_ICON.put("제약/바이오", "💊");
        SECTOR_ICON.put("자동차/모빌리티", "🚗");
        SECTOR_ICON.put("IT/플랫폼", "🌐");
        SECTOR_ICON.put("금융/밸류업", "💰");
        SECTOR_ICON.put("방산/우주항공", "🚀");
        SECTOR_ICON.put("엔터/미디어", "🎬");
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "register";
    }

    @GetMapping("/findEmail")
    public String findEmailPage() {
        return "findEmail";
    }

    @GetMapping("/findPassword")
    public String findPasswordPage() {
        return "findPassword";
    }

    // 주식 메인 화면에 필요한 시세, 환율, 뉴스 AI 데이터를 한 번에 구성
    @GetMapping("/stock")
    public String stockPage(@RequestParam(defaultValue = "005930") String code,
                            HttpSession session,
                            Model model) {
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null) {
            return "redirect:/login";
        }

        model.addAttribute("kospiInfo", stockPriceService.getKospiIndex());
        model.addAttribute("kosdaqInfo", stockPriceService.getKosdaqIndex());
        model.addAttribute("exchangeInfo", exchangeService.getExchangeRate("USD"));
        model.addAttribute("stockCode", code);

        Map<String, Object> analysis = newsDao.getTodayAnalysis();
        int totalCount = (int) analysis.getOrDefault("totalCount", 0);
        double typeProb = (double) analysis.getOrDefault("avgTypeProb", 0.0);
        double noiseProb = (double) analysis.getOrDefault("avgClickbaitProb", 0.0);
        int posCount = (int) analysis.getOrDefault("positiveCount", 0);
        int negCount = (int) analysis.getOrDefault("negativeCount", 0);

        double sentimentScore = 50.0;
        String sentimentLabel = "중립";
        String statusBadge = "중립";
        String analysisDesc = "시장은 중립적인 흐름입니다. 종목별 선택적 접근이 유효합니다.";

        if (totalCount > 0) {
            sentimentScore = Math.round((double) posCount / totalCount * 100.0 * 10) / 10.0;
            double negScore = Math.round((double) negCount / totalCount * 100.0 * 10) / 10.0;

            if (sentimentScore >= 60) {
                sentimentLabel = "긍정";
                statusBadge = "강세";
                analysisDesc = "현재 시장은 전반적으로 긍정적입니다. 호재성 뉴스가 우세하여 투자 심리가 활발합니다.";
            } else if (negScore >= 60) {
                sentimentLabel = "부정";
                statusBadge = "약세";
                analysisDesc = "현재 시장은 부정적인 흐름입니다. 악재성 뉴스가 많아 신중한 접근이 필요합니다.";
            } else {
                analysisDesc = "현재 시장은 중립적인 상태입니다. 시장의 방향성이 결정될 때까지 신중한 접근이 필요합니다.";
            }

            if (noiseProb >= 50) {
                statusBadge = "주의";
                analysisDesc = "정보 노이즈(낚시성 기사)가 높게 감지됩니다. 투자 정보를 신중히 선별하세요.";
            }
        }

        LinkedHashMap<String, Map<String, Object>> dbSectorMap = newsDao.getSectorLevelMap();
        List<Map<String, Object>> sectorCards = new ArrayList<>();

        for (String[] sector : FIXED_SECTORS) {
            String dbKey = sector[0];
            String displayName = sector[1];

            Map<String, Object> dbData = findSectorData(dbSectorMap, dbKey);
            int articleCount = dbData != null ? (int) dbData.get("articleCount") : 0;
            int pos = dbData != null ? (int) dbData.get("positiveCount") : 0;
            int neg = dbData != null ? (int) dbData.get("negativeCount") : 0;
            int neu = dbData != null ? (int) dbData.get("neutralCount") : 0;
            double avgTypeProb = dbData != null ? (double) dbData.get("avgTypeProb") : 0.0;
            double avgClickbaitProb = dbData != null ? (double) dbData.get("avgClickbaitProb") : 0.0;

            String dominant = articleCount == 0 ? "없음"
                    : (pos >= neg && pos >= neu) ? "호재"
                    : (neg >= pos && neg >= neu) ? "악재" : "중립";

            int posRatio = articleCount > 0 ? (int) Math.round((double) pos / articleCount * 100) : 0;
            int trendScore = calculateSectorTrendScore(articleCount, pos, neg, neu, avgTypeProb, avgClickbaitProb);

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("sectorKey", dbKey);
            card.put("sectorName", displayName);
            card.put("icon", SECTOR_ICON.getOrDefault(dbKey, "📈"));
            card.put("articleCount", articleCount);
            card.put("positiveCount", pos);
            card.put("negativeCount", neg);
            card.put("neutralCount", neu);
            card.put("dominant", dominant);
            card.put("posRatio", posRatio);
            card.put("avgTypeProb", String.format("%.1f", avgTypeProb));
            card.put("avgClickbaitProb", String.format("%.1f", avgClickbaitProb));
            card.put("trendScore", trendScore);
            card.put("trendDirection", resolveTrendDirection(trendScore));
            card.put("trendLabel", resolveTrendLabel(trendScore));
            sectorCards.add(card);
        }

        model.addAttribute("totalCount", totalCount);
        model.addAttribute("typeProb", String.format("%.1f", typeProb));
        model.addAttribute("noiseProb", String.format("%.1f", noiseProb));
        model.addAttribute("sentimentScore", sentimentScore);
        model.addAttribute("sentimentLabel", sentimentLabel);
        model.addAttribute("statusBadge", statusBadge);
        model.addAttribute("analysisDesc", analysisDesc);
        model.addAttribute("tickerCompanies", newsDao.getTodayTickerCompanies());
        model.addAttribute("sectorCards", sectorCards);
        model.addAttribute("currentPage", "stock");

        AiPredictionDto cachedAiPrediction = stockAiService.getCachedPrediction(code);
        model.addAttribute("aiPrediction", cachedAiPrediction);
        StockResponseDto stockInfo = stockPriceService.getCurrentPrice(code);
        model.addAttribute("stockInfo", stockInfo);

        return "stock";
    }

    @GetMapping("/market")
    public String marketPage(HttpSession session, Model model) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }

        model.addAttribute("currentPage", "market");
        return "market";
    }

    // 선택한 종목의 시장 상세 화면에 종목 정보와 관심종목 상태를 내려준다.
    @GetMapping("/market/{code}")
    public String marketDetailPage(@PathVariable String code,
                                   HttpSession session,
                                   Model model) {
        if (session.getAttribute("loginUser") == null) {
            return "redirect:/login";
        }

        Integer loginNum = (Integer) session.getAttribute("loginNum");
        StockResponseDto stockInfo = stockPriceService.getCurrentPrice(code);
        boolean watching = loginNum != null && watchListService.isWatching(loginNum, code);

        model.addAttribute("stockInfo", stockInfo);
        model.addAttribute("stockCode", code);
        model.addAttribute("watching", watching);
        model.addAttribute("currentPage", "market");
        return "marketDetail";
    }

    // 로그인 사용자의 마이페이지에 필요한 회원 정보를 조회한다.
    @GetMapping("/mypage")
    public String mypage(HttpSession session, Model model) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        if (loginNum == null) {
            return "redirect:/login";
        }

        UserDto user = userDao.getUserInfo(loginNum);
        if (user == null) {
            return "redirect:/login";
        }

        model.addAttribute("user", user);
        model.addAttribute("currentPage", "mypage");
        return "mypage";
    }

    // DB 섹터명과 화면 고정 섹터명을 느슨하게 매칭해 카드 데이터를 찾는다.
    private Map<String, Object> findSectorData(LinkedHashMap<String, Map<String, Object>> dbMap, String key) {
        if (dbMap.containsKey(key)) {
            return dbMap.get(key);
        }

        String normalizedKey = key.replace("/", "").replace(" ", "").toLowerCase();
        for (Map.Entry<String, Map<String, Object>> entry : dbMap.entrySet()) {
            String normalizedDbKey = entry.getKey().replace("/", "").replace(" ", "").toLowerCase();
            if (normalizedDbKey.contains(normalizedKey) || normalizedKey.contains(normalizedDbKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // 기사 수, 감성 비율, 신뢰도를 합쳐 섹터 상승 점수를 계산한다.
    private int calculateSectorTrendScore(int articleCount, int pos, int neg, int neu,
                                          double avgTypeProb, double avgClickbaitProb) {
        if (articleCount <= 0) {
            return 50;
        }

        double sentimentBias = (double) (pos - neg) / articleCount;
        double articleSupport = Math.min(1.0, Math.log1p(articleCount) / Math.log(12));
        double reliability = ((avgTypeProb / 100.0) * 0.7)
                + ((1.0 - (avgClickbaitProb / 100.0)) * 0.3);
        double neutralPenalty = 1.0 - (((double) neu / articleCount) * 0.35);

        double score = 50.0 + (38.0 * sentimentBias * articleSupport * reliability * neutralPenalty);
        return (int) Math.round(Math.max(0, Math.min(100, score)));
    }

    // 계산된 점수를 차트/배지에서 쓰는 방향 값으로 변환한다.
    private String resolveTrendDirection(int trendScore) {
        if (trendScore >= 58) {
            return "up";
        }
        if (trendScore <= 42) {
            return "down";
        }
        return "neutral";
    }

    // 계산된 점수를 사용자에게 보여줄 추세 라벨로 변환한다.
    private String resolveTrendLabel(int trendScore) {
        if (trendScore >= 72) {
            return "강한 상승";
        }
        if (trendScore >= 58) {
            return "상승 우세";
        }
        if (trendScore <= 28) {
            return "강한 하락";
        }
        if (trendScore <= 42) {
            return "하락 우세";
        }
        return "중립";
    }
}
