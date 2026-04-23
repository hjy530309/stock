package com.Midterm.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// FastAPI 응답 매핑용
public class AssetPlannerPredictionResponseDto {

    private boolean success;

    @JsonProperty("model_prediction")
    private int model_prediction;

    @JsonProperty("model_prediction_label")
    private String model_prediction_label;

    @JsonProperty("model_probability")
    private double model_probability;

    private int prediction;

    @JsonProperty("prediction_label")
    private String prediction_label;

    @JsonProperty("tone_title")
    private String tone_title;

    @JsonProperty("input_summary")
    private InputSummary input_summary;

    private Analysis analysis;

    public static class InputSummary {
        @JsonProperty("current_asset")
        private long current_asset;

        @JsonProperty("monthly_income")
        private long monthly_income;

        @JsonProperty("monthly_expense")
        private long monthly_expense;

        @JsonProperty("monthly_cashflow")
        private double monthly_cashflow;

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

        public double getMonthly_cashflow() {
            return monthly_cashflow;
        }

        public void setMonthly_cashflow(double monthly_cashflow) {
            this.monthly_cashflow = monthly_cashflow;
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

    public static class Analysis {
        @JsonProperty("required_monthly_cashflow")
        private long required_monthly_cashflow;

        @JsonProperty("estimated_final_asset")
        private long estimated_final_asset;

        @JsonProperty("goal_gap")
        private long goal_gap;

        @JsonProperty("future_value_current")
        private long future_value_current;

        @JsonProperty("remain_needed")
        private long remain_needed;

        @JsonProperty("surplus_amount")
        private long surplus_amount;

        @JsonProperty("is_achieved_by_current_asset_growth")
        private boolean is_achieved_by_current_asset_growth;

        private String message;

        public long getRequired_monthly_cashflow() {
            return required_monthly_cashflow;
        }

        public void setRequired_monthly_cashflow(long required_monthly_cashflow) {
            this.required_monthly_cashflow = required_monthly_cashflow;
        }

        public long getEstimated_final_asset() {
            return estimated_final_asset;
        }

        public void setEstimated_final_asset(long estimated_final_asset) {
            this.estimated_final_asset = estimated_final_asset;
        }

        public long getGoal_gap() {
            return goal_gap;
        }

        public void setGoal_gap(long goal_gap) {
            this.goal_gap = goal_gap;
        }

        public long getFuture_value_current() {
            return future_value_current;
        }

        public void setFuture_value_current(long future_value_current) {
            this.future_value_current = future_value_current;
        }

        public long getRemain_needed() {
            return remain_needed;
        }

        public void setRemain_needed(long remain_needed) {
            this.remain_needed = remain_needed;
        }

        public long getSurplus_amount() {
            return surplus_amount;
        }

        public void setSurplus_amount(long surplus_amount) {
            this.surplus_amount = surplus_amount;
        }

        public boolean isIs_achieved_by_current_asset_growth() {
            return is_achieved_by_current_asset_growth;
        }

        public void setIs_achieved_by_current_asset_growth(boolean is_achieved_by_current_asset_growth) {
            this.is_achieved_by_current_asset_growth = is_achieved_by_current_asset_growth;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getModel_prediction() {
        return model_prediction;
    }

    public void setModel_prediction(int model_prediction) {
        this.model_prediction = model_prediction;
    }

    public String getModel_prediction_label() {
        return model_prediction_label;
    }

    public void setModel_prediction_label(String model_prediction_label) {
        this.model_prediction_label = model_prediction_label;
    }

    public double getModel_probability() {
        return model_probability;
    }

    public void setModel_probability(double model_probability) {
        this.model_probability = model_probability;
    }

    public int getPrediction() {
        return prediction;
    }

    public void setPrediction(int prediction) {
        this.prediction = prediction;
    }

    public String getPrediction_label() {
        return prediction_label;
    }

    public void setPrediction_label(String prediction_label) {
        this.prediction_label = prediction_label;
    }

    public String getTone_title() {
        return tone_title;
    }

    public void setTone_title(String tone_title) {
        this.tone_title = tone_title;
    }

    public InputSummary getInput_summary() {
        return input_summary;
    }

    public void setInput_summary(InputSummary input_summary) {
        this.input_summary = input_summary;
    }

    public Analysis getAnalysis() {
        return analysis;
    }

    public void setAnalysis(Analysis analysis) {
        this.analysis = analysis;
    }
}