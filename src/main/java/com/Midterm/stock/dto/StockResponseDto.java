package com.Midterm.stock.dto;

import lombok.Data;

/**
 * 주식/지수/환율 응답 공통 DTO
 * - 주식: stockName, currentPrice, openPrice, highPrice, lowPrice, volume
 * - 지수/환율: currentPrice, priceChange, changeRate
 */
@Data
public class StockResponseDto {
    // 종목
    private String stockCode;    // 종목코드
    private String stockName;   // 종목명
    private String currentPrice; // 현재가
    private String openPrice;    // 시가
    private String highPrice;    // 고가
    private String lowPrice;     // 저가
    private String volume;       // 거래량

    // 코스피용 추가
    private String priceChange;   // 전일 대비
    private String changeRate;    // 등락률
    private String tradeAmount;   // 누적 거래대금 (원 단위 문자열)
}

