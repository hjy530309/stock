package com.Midterm.stock.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "STOCK_ALERT")
public class StockAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "alert_seq")
    @SequenceGenerator(name = "alert_seq", sequenceName = "STOCK_ALERT_SEQ", allocationSize = 1)
    private Long id;

    @Column(name = "USER_NUM", nullable = false)
    private Integer userNum;

    @Column(name = "STOCK_CODE", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "STOCK_NAME", length = 100)
    private String stockName;

    /** 전일 대비 등락률 (예: "3.52", "-4.11") */
    @Column(name = "CHANGE_RATE", length = 20)
    private String changeRate;

    /** 상승 or 하락 */
    @Column(name = "ALERT_TYPE", length = 10)
    private String alertType;

    @Column(name = "PRICE", length = 20)
    private String price;

    /** false=미읽음, true=읽음 */
    @Column(name = "ALERT_READ")
    private boolean alertRead = false;

    @Column(name = "CREATED_AT")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
