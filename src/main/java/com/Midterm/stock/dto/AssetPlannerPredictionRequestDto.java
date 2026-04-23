package com.Midterm.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// FastAPI 요청용
public class AssetPlannerPredictionRequestDto {

    @JsonProperty("current_asset")
    private long current_asset;

    @JsonProperty("monthly_income")
    private long monthly_income;

    @JsonProperty("monthly_expense")
    private long monthly_expense;

    @JsonProperty("goal_amount")
    private long goal_amount;

    @JsonProperty("goal_months")
    private int goal_months;

    @JsonProperty("expected_return")
    private double expected_return;

    @JsonProperty("age")
    private int age;

    @JsonProperty("job_type")
    private String job_type;

    @JsonProperty("risk_preference")
    private String risk_preference;

    public long getCurrent_asset() {
        return current_asset;
    }

    public void setCurrent_asset(long current_asset) {
        this.current_asset = current_asset;
    }

    public long getMonthly_income() {
        return monthly_income;
    }

    public void setMonthly_income(long monthly_income) {
        this.monthly_income = monthly_income;
    }

    public long getMonthly_expense() {
        return monthly_expense;
    }

    public void setMonthly_expense(long monthly_expense) {
        this.monthly_expense = monthly_expense;
    }

    public long getGoal_amount() {
        return goal_amount;
    }

    public void setGoal_amount(long goal_amount) {
        this.goal_amount = goal_amount;
    }

    public int getGoal_months() {
        return goal_months;
    }

    public void setGoal_months(int goal_months) {
        this.goal_months = goal_months;
    }

    public double getExpected_return() {
        return expected_return;
    }

    public void setExpected_return(double expected_return) {
        this.expected_return = expected_return;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getJob_type() {
        return job_type;
    }

    public void setJob_type(String job_type) {
        this.job_type = job_type;
    }

    public String getRisk_preference() {
        return risk_preference;
    }

    public void setRisk_preference(String risk_preference) {
        this.risk_preference = risk_preference;
    }
}