// ════════════ Stock.java ════════════════════════════════

package com.Midterm.stock.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 종목 목록 엔티티
 * - KRX(한국거래소) 또는 FinanceDataReader에서 수집한 전체 종목 저장
 * - 테이블명: STOCK
 */
@Entity
@Table(name = "STOCK")
@Getter
@Setter
@NoArgsConstructor
public class Stock {

    /** 자동 증가 PK */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 종목코드 (6자리, 중복 불허) ex) 005930 */
    @Column(unique = true)
    private String stockCode;

    /** 종목 약어명 ex) 삼성전자 */
    private String stockName;

    /** 종목 전체명 ex) 삼성전자보통주 */
    private String fullName;

    /** 시장 구분 ex) KOSPI, KOSDAQ */
    private String market;

    /** 마지막 저장/수정 시각 (자동 설정) */
    private LocalDateTime updatedAt;

    /** 저장/수정 시 updatedAt 자동 갱신 */
    @PrePersist
    @PreUpdate
    public void setUpdatedAt() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 검색 결과 반환용 Map 변환
     * - StockService.searchStock() 반환 타입(List<Map<String,String>>)과 호환
     */
    public Map<String, String> toMap() {
        return Map.of(
                "code",     stockCode != null ? stockCode : "",
                "name",     stockName != null ? stockName : "",
                "fullName", fullName  != null ? fullName  : "",
                "market",   market    != null ? market    : ""
        );
    }
}
