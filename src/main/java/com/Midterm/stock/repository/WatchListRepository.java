package com.Midterm.stock.repository;

import com.Midterm.stock.entity.WatchList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchListRepository extends JpaRepository<WatchList, Long> {

    List<WatchList> findByUserNumOrderByCreatedAtDesc(Integer userNum);

    List<WatchList> findByStockCode(String stockCode);

    Optional<WatchList> findByUserNumAndStockCode(Integer userNum, String stockCode);

    boolean existsByUserNumAndStockCode(Integer userNum, String stockCode);

    void deleteByUserNumAndStockCode(Integer userNum, String stockCode);

    /** 스케줄러용: 중복 없이 전체 관심 종목 코드+이름 조회 */
    @Query("SELECT DISTINCT w.stockCode, w.stockName FROM WatchList w")
    List<Object[]> findDistinctStocks();
}
