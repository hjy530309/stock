// ════════════ StockRepository.java ══════════════════════

package com.Midterm.stock.repository;

import com.Midterm.stock.entity.Stock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 종목 목록 JPA 레포지토리
 * - 기본 CRUD: JpaRepository 상속으로 자동 제공
 * - 커스텀: 종목명/코드 키워드 검색
 */
@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    /**
     * 종목명, 전체명, 코드로 키워드 검색 (부분 일치)
     *
     * @param keyword  검색 키워드
     * @param pageable 페이지 정보 (결과 수 제한용)
     */
    @Query("SELECT s FROM Stock s WHERE " +
            "s.stockName LIKE %:keyword% OR " +
            "s.fullName  LIKE %:keyword% OR " +
            "s.stockCode LIKE %:keyword%")
    List<Stock> findByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT s.fullName FROM Stock s WHERE s.stockCode = :stockCode")
    String findByStockCode(@Param("stockCode") String stockCode);

    @Query("SELECT COALESCE(s.stockName, s.fullName, s.stockCode) FROM Stock s WHERE s.stockCode = :stockCode")
    String findDisplayNameByStockCode(@Param("stockCode") String stockCode);
}
