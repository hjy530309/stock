package com.Midterm.stock.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "NEWS_STOCK_MAP")
public class NewsStockMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "LINK", nullable = false)
    private String link;

    @Column(name = "STOCK_CODE", nullable = false)
    private String stockCode;

    @Column(name = "STOCK_NAME")
    private String stockName;

    @Column(name = "MATCH_SCORE")
    private Double matchScore;
}