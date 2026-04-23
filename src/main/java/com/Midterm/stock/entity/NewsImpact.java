package com.Midterm.stock.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "NEWS_IMPACT")
public class NewsImpact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "LINK", nullable = false)
    private String link;

    @Column(name = "STOCK_CODE", nullable = false)
    private String stockCode;

    @Column(name = "BASE_PRICE")
    private Double basePrice;

    @Column(name = "PRICE_30M")
    private Double price30m;

    @Column(name = "IMPACT_30M")
    private Double impact30m;

    @Column(name = "CREATED_AT")
    private LocalDateTime createdAt;
}