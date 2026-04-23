package com.Midterm.stock.controller;

import com.Midterm.stock.dto.AiPredictionDto;
import com.Midterm.stock.dto.StockChartDto;
import com.Midterm.stock.dto.StockResponseDto;
import com.Midterm.stock.service.stock.ExchangeService;
import com.Midterm.stock.service.stock.StockAiService;
import com.Midterm.stock.service.stock.StockPriceService;
import com.Midterm.stock.service.stock.StockSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class StockController {

    private final StockPriceService stockPriceService;
    private final StockSearchService stockSearchService;
    private final ExchangeService exchangeService;
    private final StockAiService stockAiService;

    // 특정 종목의 현재 시세를 반환한다.
    @GetMapping("/api/stock/{code}")
    @ResponseBody
    public StockResponseDto getStockApi(@PathVariable String code) {
        return stockPriceService.getCurrentPrice(code);
    }

    // 특정 종목의 일봉 차트 데이터를 반환한다.
    @GetMapping("/api/stock/{code}/chart")
    @ResponseBody
    public StockChartDto getChartApi(@PathVariable String code) {
        return stockPriceService.getDailyPrice(code);
    }

    // 장중에는 시간봉, 장외에는 일봉으로 대체해 반환한다.
    @GetMapping("/api/stock/{code}/time")
    @ResponseBody
    public StockChartDto getTimeChart(@PathVariable String code) {
        if (!isMarketOpen()) {
            return stockPriceService.getDailyPrice(code);
        }
        return stockPriceService.getTimePrice(code);
    }

    // 장중에는 분봉, 장외에는 일봉으로 대체해 반환한다.
    @GetMapping("/api/stock/{code}/minute")
    @ResponseBody
    public StockChartDto getMinuteChart(@PathVariable String code) {
        if (!isMarketOpen()) {
            return stockPriceService.getDailyPrice(code);
        }
        return stockPriceService.getMinutePrice(code);
    }

    // 종목명이나 코드로 검색 결과를 반환한다.
    @GetMapping("/api/stock/search")
    @ResponseBody
    public List<Map<String, String>> searchStock(@RequestParam String keyword) {
        try {
            return stockSearchService.searchStock(keyword);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // 등락률 상위 종목 목록을 반환한다.
    @GetMapping("/api/stock/top-fluctuation")
    @ResponseBody
    public List<StockResponseDto> getTopFluctuation() {
        return stockPriceService.getTopFluctuation();
    }

    // 거래대금 상위 종목 목록을 반환하고 실패 시 등락률 목록으로 대체한다.
    @GetMapping("/api/stock/top-trade")
    @ResponseBody
    public List<StockResponseDto> getTopTrade() {
        List<StockResponseDto> result = stockPriceService.getTopByTradeAmount();
        if (result == null || result.isEmpty()) {
            return stockPriceService.getTopFluctuation();
        }
        return result;
    }

    // 전체 등락률 상위 목록을 시장 화면 전용 포맷으로 반환한다.
    @GetMapping("/api/stock/top-fluctuation-full")
    @ResponseBody
    public List<StockResponseDto> getTopFluctuationFull() {
        return stockPriceService.getTopFluctuation();
    }

    // 종목 마스터 데이터를 외부 소스로 다시 받아 DB에 갱신한다.
    @GetMapping("/api/stock/refresh")
    @ResponseBody
    public String refreshStocks() {
        try {
            stockSearchService.refreshStockListToDB();
            return "DB 갱신 완료";
        } catch (Exception e) {
            return "오류: " + e.getMessage();
        }
    }

    // 특정 종목의 AI 상승/하락 예측 결과를 반환한다.
    @GetMapping("/api/stock/{code}/ai")
    @ResponseBody
    public AiPredictionDto getAiPrediction(@PathVariable String code) {
        return stockAiService.predict(code);
    }

    // KOSPI 현재 지수를 반환한다.
    @GetMapping("/api/kospi")
    @ResponseBody
    public StockResponseDto getKospiApi() {
        return stockPriceService.getKospiIndex();
    }

    // KOSPI 일봉 차트를 반환한다.
    @GetMapping("/api/kospi/chart")
    @ResponseBody
    public StockChartDto getKospiChartApi() {
        return stockPriceService.getKospiChart();
    }

    // KOSPI 시간봉 차트를 반환하고 장외에는 일봉으로 대체한다.
    @GetMapping("/api/kospi/time")
    @ResponseBody
    public StockChartDto getKospiTimeChartApi() {
        if (!isMarketOpen()) {
            return stockPriceService.getKospiChart();
        }
        return stockPriceService.getKospiTimeChart();
    }

    // KOSPI 분봉 차트를 반환하고 장외에는 일봉으로 대체한다.
    @GetMapping("/api/kospi/minute")
    @ResponseBody
    public StockChartDto getKospiMinuteChartApi() {
        if (!isMarketOpen()) {
            return stockPriceService.getKospiChart();
        }
        return stockPriceService.getKospiMinuteChart();
    }

    // KOSDAQ 현재 지수를 반환한다.
    @GetMapping("/api/kosdaq")
    @ResponseBody
    public StockResponseDto getKosdaqApi() {
        return stockPriceService.getKosdaqIndex();
    }

    // KOSDAQ 일봉 차트를 반환한다.
    @GetMapping("/api/kosdaq/chart")
    @ResponseBody
    public StockChartDto getKosdaqChartApi() {
        return stockPriceService.getKosdaqChart();
    }

    // KOSDAQ 시간봉 차트를 반환하고 장외에는 일봉으로 대체한다.
    @GetMapping("/api/kosdaq/time")
    @ResponseBody
    public StockChartDto getKosdaqTimeChartApi() {
        if (!isMarketOpen()) {
            return stockPriceService.getKosdaqChart();
        }
        return stockPriceService.getKosdaqTimeChart();
    }

    // KOSDAQ 분봉 차트를 반환하고 장외에는 일봉으로 대체한다.
    @GetMapping("/api/kosdaq/minute")
    @ResponseBody
    public StockChartDto getKosdaqMinuteChartApi() {
        if (!isMarketOpen()) {
            return stockPriceService.getKosdaqChart();
        }
        return stockPriceService.getKosdaqMinuteChart();
    }

    // 선택한 통화의 현재 환율을 반환한다.
    @GetMapping("/api/exchange")
    @ResponseBody
    public StockResponseDto getExchangeApi(@RequestParam(defaultValue = "USD") String currency) {
        return exchangeService.getExchangeRate(currency);
    }

    // 선택한 통화의 환율 차트 데이터를 반환한다.
    @GetMapping("/api/exchange/chart")
    @ResponseBody
    public StockChartDto getExchangeChartApi(@RequestParam(defaultValue = "USD") String currency) {
        return exchangeService.getExchangeChart(currency);
    }

    // 현재 시간이 국내 주식 정규장 시간인지 판단한다.
    private boolean isMarketOpen() {
        LocalDateTime now = LocalDateTime.now();
        int day = now.getDayOfWeek().getValue();
        if (day >= 6) {
            return false;
        }

        int time = now.getHour() * 100 + now.getMinute();
        return time >= 900 && time <= 1530;
    }
}
