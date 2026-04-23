package com.Midterm.stock.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "WATCH_LIST",
       uniqueConstraints = @UniqueConstraint(columnNames = {"USER_NUM", "STOCK_CODE"}))
public class WatchList {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "watch_seq")
    @SequenceGenerator(name = "watch_seq", sequenceName = "WATCH_LIST_SEQ", allocationSize = 1)
    private Long id;

    @Column(name = "USER_NUM", nullable = false)
    private Integer userNum;

    @Column(name = "STOCK_CODE", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "STOCK_NAME", length = 100)
    private String stockName;

    @Column(name = "CREATED_AT")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
