package com.Midterm.stock.dto;

import java.time.LocalDate;

public class AssetDto {
    private int spend_id;
    private int user_id;
    private int month;  // 월
    private int year;
    private LocalDate date;       // 일자
    private int amount;           // 금액
    private String vendor;        // 구매처
    private String category;      // 최종카테고리


    public AssetDto() {}

    public AssetDto(int spend_id, int user_id, int month, int year, LocalDate date, int amount, String vendor, String category) {
        this.spend_id = spend_id;
        this.user_id = user_id;
        this.month = month;
        this.year = year;
        this.date = date;
        this.amount = amount;
        this.vendor = vendor;
        this.category = category;
    }

    public int getMonth() {
        return month;
    }

    public void setMonth(int month) {
        this.month = month;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getSpend_id() {
        return spend_id;
    }

    public void setSpend_id(int spend_id) {
        this.spend_id = spend_id;
    }

    public int getUser_id() {
        return user_id;
    }

    public void setUser_id(int user_id) {
        this.user_id = user_id;
    }
}