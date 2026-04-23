package com.Midterm.stock.controller;

import com.Midterm.stock.dto.AssetDto;
import com.Midterm.stock.dto.AssetPlannerAnalysisDto;
import com.Midterm.stock.repository.AssetDao;
import com.Midterm.stock.service.AssetPlannerAnalysisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class AssetController {

    @Autowired
    private AssetDao assetDao;

    @Autowired
    private AssetPlannerAnalysisService assetPlannerAnalysisService;

    private int getCurrentMonth() {
        return LocalDate.now().getMonthValue();
    }

    private int getCurrentYear() {
        return LocalDate.now().getYear();
    }

    // UserController에서 이미 session에 name, email 넣어둠
    private void addLoginUserAttributes(Model model, HttpSession session, Integer loginNum) {
        model.addAttribute("userName", (String) session.getAttribute("userName"));
        model.addAttribute("userEmail", (String) session.getAttribute("userEmail"));
        model.addAttribute("user_id", loginNum);
    }

    @GetMapping("/asset/dashboard")
    public String dashboard(
            @RequestParam(value = "month", required = false) Integer month,
            @RequestParam(value = "year", required = false) Integer year,
            Model model,
            HttpSession session
    ) throws JsonProcessingException {

        Integer loginNum = (Integer) session.getAttribute("loginNum");
        if (loginNum == null) {
            return "redirect:/login";
        }

        int currentRealMonth = getCurrentMonth();
        int currentRealYear = getCurrentYear();

        if (month == null) {
            month = currentRealMonth;
        }
        if (year == null) {
            year = currentRealYear;
        }

        // session도 같이 넘겨야 함
        addLoginUserAttributes(model, session, loginNum);

        List<AssetDto> transactions = assetDao.getRecentTransactionsByMonth(month, year, loginNum);
        int currentMonthSpending = assetDao.getMonthSpending(month, year, loginNum);

        Map<String, Integer> prevDate = assetDao.getNearestPrevDate(month, year, loginNum);
        int previousMonthSpending = 0;
        if (prevDate != null) {
            int prevMonth = prevDate.get("month");
            int prevYear = prevDate.get("year");
            model.addAttribute("hasPrevData", true);
            model.addAttribute("prevMonth", prevMonth);
            model.addAttribute("prevYear", prevYear);
            previousMonthSpending = assetDao.getMonthSpending(prevMonth, prevYear, loginNum);
        } else {
            model.addAttribute("hasPrevData", false);
        }

        Map<String, Integer> nextDate = assetDao.getNearestNextDate(month, year, loginNum);
        if (nextDate != null) {
            model.addAttribute("hasNextData", true);
            model.addAttribute("nextMonth", nextDate.get("month"));
            model.addAttribute("nextYear", nextDate.get("year"));
        } else {
            model.addAttribute("hasNextData", false);
        }

        double diffRate = 0;
        if (previousMonthSpending > 0) {
            diffRate = ((double) (currentMonthSpending - previousMonthSpending) / previousMonthSpending) * 100;
        }

        List<AssetPlannerAnalysisDto> history = assetDao.getAnalysisHistory(loginNum);
        AssetPlannerAnalysisDto latestAnalysis = new AssetPlannerAnalysisDto();

        if (history != null && !history.isEmpty()) {
            latestAnalysis = history.get(0);
        } else {
            latestAnalysis.setGoalAmount(0L);
            latestAnalysis.setCurrentAsset(0L);
            latestAnalysis.setRequiredMonthlySaving(0L);
            latestAnalysis.setMonthlyIncome(0L);
        }

        int currentMonthIncome = (int) latestAnalysis.getMonthlyIncome();

        double spendingRate = 0;
        if (currentMonthIncome > 0) {
            spendingRate = ((double) currentMonthSpending / currentMonthIncome) * 100;
        }

        double goalAchievementRate = 0;
        if (latestAnalysis.getGoalAmount() > 0) {
            goalAchievementRate = ((double) latestAnalysis.getCurrentAsset() / latestAnalysis.getGoalAmount()) * 100;
        }

        List<Map<String, Object>> trendData = assetDao.getLast5MonthsSpending(year, month, loginNum);

        int sum = trendData.stream()
                .mapToInt(item -> ((Number) item.get("total")).intValue())
                .sum();
        int avg = trendData.isEmpty() ? 0 : sum / trendData.size();

        int diff = 0;
        boolean isUp = false;
        if (trendData.size() >= 2) {
            int last = ((Number) trendData.get(trendData.size() - 1).get("total")).intValue();
            int prev = ((Number) trendData.get(trendData.size() - 2).get("total")).intValue();
            diff = last - prev;
            isUp = diff >= 0;
        }

        ObjectMapper mapper = new ObjectMapper();
        String trendJson = mapper.writeValueAsString(trendData);

        model.addAttribute("selectedMonth", month);
        model.addAttribute("selectedYear", year);
        model.addAttribute("currentRealMonth", currentRealMonth);
        model.addAttribute("currentRealYear", currentRealYear);

        model.addAttribute("recentTransactions", transactions);
        model.addAttribute("currentMonthSpending", currentMonthSpending);
        model.addAttribute("previousMonthSpending", previousMonthSpending);
        model.addAttribute("diffRate", Math.abs(Math.round(diffRate * 10) / 10.0));
        model.addAttribute("isIncreased", currentMonthSpending >= previousMonthSpending);

        model.addAttribute("latestAnalysis", latestAnalysis);
        model.addAttribute("spendingRate", Math.round(spendingRate));
        model.addAttribute("currentMonthIncome", currentMonthIncome);
        model.addAttribute("goalAchievementRate", Math.round(goalAchievementRate * 10) / 10.0);

        model.addAttribute("trendJson", trendJson);
        model.addAttribute("trendData", trendData);
        model.addAttribute("avgSpending", avg);
        model.addAttribute("diffAmount", Math.abs(diff));
        model.addAttribute("isUp", isUp);

        return "asset/dashboard";
    }

    @GetMapping("/asset/analytics")
    public String analytics(
            @RequestParam(value = "month", required = false) Integer month,
            @RequestParam(value = "year", required = false) Integer year,
            Model model,
            HttpSession session
    ) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        if (loginNum == null) {
            return "redirect:/login";
        }

        int currentRealMonth = getCurrentMonth();
        int currentRealYear = getCurrentYear();

        if (month == null) {
            month = currentRealMonth;
        }
        if (year == null) {
            year = currentRealYear;
        }

        model.addAttribute("currentRealMonth", currentRealMonth);
        model.addAttribute("currentRealYear", currentRealYear);

        // 세션 기반 사용자 정보 사용
        addLoginUserAttributes(model, session, loginNum);

        Map<String, Integer> needWant = assetDao.getNeedWantSpending(month, year, loginNum);
        int need = needWant.getOrDefault("need", 0);
        int want = needWant.getOrDefault("want", 0);
        int total = need + want;

        model.addAttribute("needAmount", need);
        model.addAttribute("wantAmount", want);
        model.addAttribute("needPercentage", total > 0 ? (need * 100 / total) : 0);
        model.addAttribute("wantPercentage", total > 0 ? (want * 100 / total) : 0);

        List<Map<String, Object>> categories = assetDao.getCategorySpending(month, year, loginNum);
        String[] colors = {"#15164D", "#00C6B8", "#FFA726", "#AB47BC", "#FF4B4B", "#66BB6A", "#8D6E63"};

        for (int i = 0; i < categories.size(); i++) {
            Map<String, Object> category = categories.get(i);
            category.put("color", colors[i % colors.length]);

            int value = ((Number) category.get("value")).intValue();
            category.put("percentage", total > 0 ? String.format("%.1f", (value * 100.0 / total)) : "0");
        }

        model.addAttribute("categories", categories);
        model.addAttribute("selectedMonth", month);
        model.addAttribute("selectedYear", year);

        Map<String, Integer> currentMap = assetDao.getCategoryMapByMonth(month, year, loginNum);
        Map<String, Integer> previousMap;

        Map<String, Integer> prevDate = assetDao.getNearestPrevDate(month, year, loginNum);
        if (prevDate != null) {
            int prevMonth = prevDate.get("month");
            int prevYear = prevDate.get("year");
            model.addAttribute("hasPrevData", true);
            model.addAttribute("prevMonth", prevMonth);
            model.addAttribute("prevYear", prevYear);
            previousMap = assetDao.getCategoryMapByMonth(prevMonth, prevYear, loginNum);
        } else {
            model.addAttribute("hasPrevData", false);
            previousMap = new HashMap<>();
        }

        Map<String, Integer> nextDate = assetDao.getNearestNextDate(month, year, loginNum);
        if (nextDate != null) {
            model.addAttribute("hasNextData", true);
            model.addAttribute("nextMonth", nextDate.get("month"));
            model.addAttribute("nextYear", nextDate.get("year"));
        } else {
            model.addAttribute("hasNextData", false);
        }

        List<Map<String, Object>> insights = new ArrayList<>();

        for (String category : currentMap.keySet()) {
            int currentAmount = currentMap.get(category);
            int previousAmount = previousMap.getOrDefault(category, 0);

            if (previousAmount > 0) {
                double rate = (double) currentAmount / previousAmount;
                boolean isIncreased = currentAmount > previousAmount;

                Map<String, Object> insight = new HashMap<>();
                insight.put("title", category + " 지출 " + (isIncreased ? "증가" : "감소"));
                insight.put("desc", "지난달보다 " + category + " 지출이 "
                        + Math.round(rate * 10) / 10.0 + "배 "
                        + (isIncreased ? "증가" : "감소") + "했습니다.");
                insight.put("isWarning", isIncreased);
                insight.put("absRate", Math.abs(rate));

                insights.add(insight);
            }
        }

        insights.sort((a, b) ->
                Double.compare(
                        ((Number) b.get("absRate")).doubleValue(),
                        ((Number) a.get("absRate")).doubleValue()
                )
        );

        model.addAttribute("insights", insights.stream().limit(3).toList());

        List<AssetDto> transactions = assetDao.getRecentTransactionsByMonth(month, year, loginNum);
        model.addAttribute("recentTransactions", transactions);

        return "asset/analytics";
    }

    @GetMapping("/asset/savings-planner")
    public String showPlanner(
            @RequestParam(value = "month", required = false) Integer month,
            @RequestParam(value = "year", required = false) Integer year,
            Model model,
            HttpSession session
    ) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        if (loginNum == null) {
            return "redirect:/login";
        }

        int currentRealMonth = getCurrentMonth();
        int currentRealYear = getCurrentYear();

        if (month == null) {
            month = currentRealMonth;
        }
        if (year == null) {
            year = currentRealYear;
        }

        // 세션 기반 사용자 정보
        addLoginUserAttributes(model, session, loginNum);

        List<AssetPlannerAnalysisDto> history = assetPlannerAnalysisService.getHistory(loginNum);
        AssetPlannerAnalysisDto lastData;

        if (history != null && !history.isEmpty()) {
            lastData = history.get(0);
        } else {
            lastData = new AssetPlannerAnalysisDto();
            lastData.setGoalAmount(0L);
            lastData.setGoalMonths(12);
            lastData.setAge(25);
        }

        model.addAttribute("assetDto", lastData);
        model.addAttribute("historyList", history);
        model.addAttribute("currentMonthSpending", assetDao.getMonthSpending(month, year, loginNum));
        model.addAttribute("selectedMonth", month);
        model.addAttribute("selectedYear", year);

        return "asset/savings-planner";
    }

    @PostMapping("/asset/savings-planner/analyze")
    public String assetAnalyze(
            @RequestParam(value = "month", required = false) Integer month,
            @RequestParam(value = "year", required = false) Integer year,
            @ModelAttribute("assetDto") AssetPlannerAnalysisDto assetDto,
            HttpSession session,
            Model model
    ) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        if (loginNum == null) {
            return "redirect:/login";
        }

        int currentRealMonth = getCurrentMonth();
        int currentRealYear = getCurrentYear();

        if (month == null) {
            month = currentRealMonth;
        }
        if (year == null) {
            year = currentRealYear;
        }

        // 분석 port 후 다시 화면으로 돌아올 수 있으니
        addLoginUserAttributes(model, session, loginNum);

        try {
            int currentMonthSpending = assetDao.getMonthSpending(month, year, loginNum);
            assetDto.setMonthlyExpense((long) currentMonthSpending);

            AssetPlannerAnalysisDto resultDto = assetPlannerAnalysisService.analyzeAndSave(assetDto, loginNum);

            model.addAttribute("selectedMonth", month);
            model.addAttribute("selectedYear", year);
            model.addAttribute("assetDto", resultDto);
            model.addAttribute("currentMonthSpending", currentMonthSpending);
            model.addAttribute("historyList", assetPlannerAnalysisService.getHistory(loginNum));

            return "asset/savings-planner";

        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("assetDto", assetDto);
            model.addAttribute("currentMonthSpending", assetDao.getMonthSpending(month, year, loginNum));
            model.addAttribute("historyList", assetPlannerAnalysisService.getHistory(loginNum));
            model.addAttribute("errorMessage", "자산 분석 중 오류가 발생했습니다. " + e.getMessage());
            return "asset/savings-planner";
        }
    }
}