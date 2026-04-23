package com.Midterm.stock.repository;

import com.Midterm.stock.entity.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {

    /** 사용자 알림 목록 (최신순, 7일 이내) */
    List<StockAlert> findByUserNumAndCreatedAtAfterOrderByCreatedAtDesc(
            Integer userNum, LocalDateTime after);

    /** 미읽음 개수 */
    long countByUserNumAndAlertReadFalse(Integer userNum);

    /** 오늘 같은 종목+방향 알림 중복 방지 */
    @Query("SELECT COUNT(a) > 0 FROM StockAlert a " +
           "WHERE a.userNum = :userNum AND a.stockCode = :code " +
           "AND a.alertType = :type AND a.createdAt >= :startOfDay")
    boolean existsTodayAlert(@Param("userNum") Integer userNum,
                             @Param("code") String code,
                             @Param("type") String type,
                             @Param("startOfDay") LocalDateTime startOfDay);

    /** 전체 읽음 처리 */
    @Modifying
    @Query("UPDATE StockAlert a SET a.alertRead = true WHERE a.userNum = :userNum")
    void markAllRead(@Param("userNum") Integer userNum);

    /** 7일 지난 알림 자동 삭제 */
    @Modifying
    @Query("DELETE FROM StockAlert a WHERE a.createdAt < :cutoff")
    void deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
