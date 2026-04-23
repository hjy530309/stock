package com.Midterm.stock.scheduler;

import com.Midterm.stock.repository.StockRepository;
import com.Midterm.stock.service.stock.StockSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 종목 목록 자동 갱신 스케줄러
 * - 서버 시작 시 DB가 비어있으면 자동 로드
 * - 평일 새벽 6시마다 KRX 최신 데이터로 갱신
 */
@Component
@RequiredArgsConstructor
public class StockScheduler {

    private final StockSearchService stockSearchService;
    private final StockRepository stockRepository;

    /**
     * 서버 완전히 기동된 후 실행 (ApplicationReadyEvent)
     * - DB에 종목 데이터 없으면 Python 스크립트로 최초 로드
     * - 있으면 기존 데이터 그대로 사용 (불필요한 Python 실행 방지)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (stockRepository.count() == 0) {
            System.out.println("서버 시작 - 종목 목록 초기 로드");
            stockSearchService.refreshStockListToDB();
        } else {
            System.out.println("서버 시작 - DB 종목 있음: " + stockRepository.count() + "개");
        }
    }

    /**
     * 평일 새벽 6시 자동 갱신 (장 시작 전 최신화)
     * cron: 초 분 시 일 월 요일
     */
    @Scheduled(cron = "0 0 6 * * MON-FRI")
    public void refreshStockList() {
        System.out.println("종목 목록 자동 갱신 시작...");
        stockSearchService.refreshStockListToDB();
    }
}