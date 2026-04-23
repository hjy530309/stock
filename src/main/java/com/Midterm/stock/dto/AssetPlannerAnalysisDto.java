package com.Midterm.stock.dto;

// 화면 입력 + DB 저장
public class AssetPlannerAnalysisDto {

    private int analysisId;

    private long currentAsset;
    private long monthlyIncome;
    private long monthlyExpense;
    private double monthlySaving;

    private long goalAmount;
    private int goalMonths;
    private double expectedReturn;

    private int age;
    private String jobType;
    private String riskPreference;

    private int prediction;
    private String predictionLabel;
    private String toneTitle;

    private int modelPrediction;
    private double modelProbability;

    private long requiredMonthlySaving;
    private long estimatedFinalAsset;
    private long goalGap;

    private String message;
    private String createdAt;
    private String modelPredictionLabel;

    public int getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(int analysisId) {
        this.analysisId = analysisId;
    }

    public long getCurrentAsset() {
        return currentAsset;
    }

    public void setCurrentAsset(long currentAsset) {
        this.currentAsset = currentAsset;
    }

    public long getMonthlyIncome() {
        return monthlyIncome;
    }

    public void setMonthlyIncome(long monthlyIncome) {
        this.monthlyIncome = monthlyIncome;
    }

    public long getMonthlyExpense() {
        return monthlyExpense;
    }

    public void setMonthlyExpense(long monthlyExpense) {
        this.monthlyExpense = monthlyExpense;
    }

    public double getMonthlySaving() {
        return monthlySaving;
    }

    public void setMonthlySaving(double monthlySaving) {
        this.monthlySaving = monthlySaving;
    }

    public long getGoalAmount() {
        return goalAmount;
    }

    public void setGoalAmount(long goalAmount) {
        this.goalAmount = goalAmount;
    }

    public int getGoalMonths() {
        return goalMonths;
    }

    public void setGoalMonths(int goalMonths) {
        this.goalMonths = goalMonths;
    }

    public double getExpectedReturn() {
        return expectedReturn;
    }

    public void setExpectedReturn(double expectedReturn) {
        this.expectedReturn = expectedReturn;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getRiskPreference() {
        return riskPreference;
    }

    public void setRiskPreference(String riskPreference) {
        this.riskPreference = riskPreference;
    }

    public int getPrediction() {
        return prediction;
    }

    public void setPrediction(int prediction) {
        this.prediction = prediction;
    }

    public String getPredictionLabel() {
        return predictionLabel;
    }

    public void setPredictionLabel(String predictionLabel) {
        this.predictionLabel = predictionLabel;
    }

    public String getToneTitle() {
        return toneTitle;
    }

    public void setToneTitle(String toneTitle) {
        this.toneTitle = toneTitle;
    }

    public int getModelPrediction() {
        return modelPrediction;
    }

    public void setModelPrediction(int modelPrediction) {
        this.modelPrediction = modelPrediction;
    }

    public double getModelProbability() {
        return modelProbability;
    }

    public void setModelProbability(double modelProbability) {
        this.modelProbability = modelProbability;
    }

    public long getRequiredMonthlySaving() {
        return requiredMonthlySaving;
    }

    public void setRequiredMonthlySaving(long requiredMonthlySaving) {
        this.requiredMonthlySaving = requiredMonthlySaving;
    }

    public long getEstimatedFinalAsset() {
        return estimatedFinalAsset;
    }

    public void setEstimatedFinalAsset(long estimatedFinalAsset) {
        this.estimatedFinalAsset = estimatedFinalAsset;
    }

    public long getGoalGap() {
        return goalGap;
    }

    public void setGoalGap(long goalGap) {
        this.goalGap = goalGap;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getModelPredictionLabel() {
        return modelPredictionLabel;
    }

    public void setModelPredictionLabel(String modelPredictionLabel) {
        this.modelPredictionLabel = modelPredictionLabel;
    }
}