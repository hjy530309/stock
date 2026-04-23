package com.Midterm.stock.dto;

import lombok.Data;
import java.util.List;

/**
 * 차트 데이터 응답 DTO
 * - Chart.js 에 바로 전달되는 구조
 */
@Data
public class StockChartDto {

    /** X축 레이블 목록 (날짜: YYYYMMDD, 시각: HH:mm) */
    private List<String> labels;

    /** 시가 (Open) */
    private List<String> openPrices;

    /** 고가 (High) */
    private List<String> highPrices;

    /** 저가 (Low) */
    private List<String> lowPrices;

    /** 종가/현재가 (Close) */
    private List<String> closePrices;

    /** 거래량 (Volume) */
    private List<String> volumes;
}