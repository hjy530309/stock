package com.Midterm.stock.dto;

import java.util.Locale;
import java.util.Map;

public class NewsDto {

    private static final Map<String, String> CATEGORY_TO_STOCK_CODE = Map.ofEntries(
        Map.entry("반도체/AI", "005930"),
        Map.entry("IT/반도체", "005930"),
        Map.entry("삼성전자 (IT/반도체)", "005930"),
        Map.entry("2차전지", "373220"),
        Map.entry("LG에너지솔루션 (2차전지)", "373220"),
        Map.entry("바이오", "207940"),
        Map.entry("제약/바이오", "207940"),
        Map.entry("삼성바이오로직스 (제약/바이오)", "207940"),
        Map.entry("IT/플랫폼", "035420"),
        Map.entry("네이버 (IT/플랫폼)", "035420"),
        Map.entry("자동차/모빌리티", "005380"),
        Map.entry("현대차 (자동차/모빌리티)", "005380"),
        Map.entry("방산", "012450"),
        Map.entry("방산/우주항공", "012450"),
        Map.entry("한화에어로스페이스 (방산/우주항공)", "012450"),
        Map.entry("엔터/미디어", "352820"),
        Map.entry("하이브 (엔터/미디어)", "352820"),
        Map.entry("금융/밸류업", "105560"),
        Map.entry("KB금융 (금융/밸류업)", "105560")
    );

    private String link;
    private String category;
    private String title;
    private String summary;
    private String sentiment;
    private String pubDate;
    private double clickbaitProb;
    private String articleType;
    private double typeProb;

    private String companyName;
    private String sectorName;
    private String stockCode;

    private int likeCount;
    private int commentCount;
    private boolean liked;

    private Double stockImpactPercent;
    private String stockImpactSource;
    private Double riseProbability;
    private String risePrediction;
    private String riseConfidence;

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
        parseCategory(category);
        if (isBlank(this.stockCode)) {
            this.stockCode = resolveStockCode(category);
        }
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
    }

    public String getPubDate() {
        return pubDate;
    }

    public void setPubDate(String pubDate) {
        this.pubDate = pubDate;
    }

    public double getClickbaitProb() {
        return clickbaitProb;
    }

    public void setClickbaitProb(double clickbaitProb) {
        this.clickbaitProb = clickbaitProb;
    }

    public String getArticleType() {
        return articleType;
    }

    public void setArticleType(String articleType) {
        this.articleType = articleType;
    }

    public double getTypeProb() {
        return typeProb;
    }

    public void setTypeProb(double typeProb) {
        this.typeProb = typeProb;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getSectorName() {
        return sectorName;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = normalizeStockCode(stockCode);
    }

    public int getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(int likeCount) {
        this.likeCount = likeCount;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(int commentCount) {
        this.commentCount = commentCount;
    }

    public boolean isLiked() {
        return liked;
    }

    public void setLiked(boolean liked) {
        this.liked = liked;
    }

    public Double getStockImpactPercent() {
        return stockImpactPercent;
    }

    public void setStockImpactPercent(Double stockImpactPercent) {
        this.stockImpactPercent = stockImpactPercent;
    }

    public String getStockImpactSource() {
        return stockImpactSource;
    }

    public void setStockImpactSource(String stockImpactSource) {
        this.stockImpactSource = stockImpactSource;
    }

    public Double getRiseProbability() {
        return riseProbability;
    }

    public void setRiseProbability(Double riseProbability) {
        this.riseProbability = riseProbability;
    }

    public String getRisePrediction() {
        return risePrediction;
    }

    public void setRisePrediction(String risePrediction) {
        this.risePrediction = risePrediction;
    }

    public String getRiseConfidence() {
        return riseConfidence;
    }

    public void setRiseConfidence(String riseConfidence) {
        this.riseConfidence = normalizeConfidenceLabel(riseConfidence);
    }

    public String getClickbaitProbStr() {
        return String.format(Locale.KOREA, "%.1f", clickbaitProb);
    }

    public String getTypeProbStr() {
        return String.format(Locale.KOREA, "%.1f", typeProb);
    }

    public boolean hasStockImpactPrediction() {
        return stockImpactPercent != null;
    }

    public boolean hasRisePrediction() {
        return riseProbability != null;
    }

    public String getImpactToneClass() {
        return "tone-" + getImpactTone();
    }

    public String getRiseToneClass() {
        return "tone-" + getRiseTone();
    }

    public String getImpactPillValue() {
        if (hasStockImpactPrediction()) {
            return formatSignedPercent(normalizeImpactPercent(stockImpactPercent));
        }
        return formatSignedPercent(getHeuristicImpactScore());
    }

    public String getImpactHoverTitle() {
        return "주가 영향 추정치";
    }

    public String getImpactSourceText() {
        if (hasStockImpactPrediction()) {
            return isBlank(stockImpactSource) ? "기사 영향 추정 모델" : stockImpactSource;
        }
        return "휴리스틱";
    }

    public String getImpactHoverBody() {
        if (hasStockImpactPrediction()) {
            double value = normalizeImpactPercent(stockImpactPercent);
            if (value > 0.0) {
                return "기사 공개 직후 단기적으로 상승 압력으로 이어질 가능성을 추정합니다.";
            }
            if (value < 0.0) {
                return "기사 공개 직후 단기적으로 하락 압력으로 이어질 가능성을 추정합니다.";
            }
            return "기사 공개 직후 가격 반응이 크지 않을 가능성을 추정합니다.";
        }
        return "기사 영향 추정 모델값이 없어 감성·신뢰도·기사유형 기반의 추정값을 표시합니다.";
    }

    public String getRiseProbabilityDisplay() {
        return getRiseProbabilityPercent() + "%";
    }

    public int getRiseProbabilityPercent() {
        return (int) Math.round(effectiveRiseProbability() * 100.0);
    }

    public String getRiseHoverBody() {
        if (!hasRisePrediction()) {
            return "모델 예측값이 없어 감성/신뢰도 기반의 휴리스틱 확률을 표시합니다.";
        }

        String direction = isRisePositive()
            ? "상승 우세"
            : isRiseNegative() ? "하락 우세" : "중립";

        if (!isBlank(riseConfidence)) {
            direction += " (" + riseConfidence + ")";
        }
        return "현재 종목 컨텍스트를 기준으로 다음 거래일 상승 확률을 계산한 값입니다. 방향 해석은 "
            + direction + "입니다.";
    }

    public int getHeuristicImpactScore() {
        int sign = getSentimentSign();
        if (sign == 0) {
            return 0;
        }

        double reliability = (typeProb * 0.65) + ((100.0 - clickbaitProb) * 0.35);
        double adjusted = reliability * getArticleTypeWeight();
        int score = (int) Math.round(clamp(adjusted, 0, 100));
        return sign * score;
    }

    public int getImpactScore() {
        if (hasStockImpactPrediction()) {
            return (int) Math.round(normalizeImpactPercent(stockImpactPercent));
        }
        return getHeuristicImpactScore();
    }

    public int getImpactMagnitude() {
        if (hasStockImpactPrediction()) {
            return (int) Math.round(Math.abs(normalizeImpactPercent(stockImpactPercent)));
        }
        return Math.abs(getHeuristicImpactScore());
    }

    public String getImpactScoreText() {
        int score = getHeuristicImpactScore();
        if (score > 0) {
            return "+" + score;
        }
        return String.valueOf(score);
    }

    public String getImpactSummary() {
        if (hasStockImpactPrediction()) {
            double value = normalizeImpactPercent(stockImpactPercent);
            if (value > 0.0) {
                return "상승 영향 " + formatSignedPercent(value);
            }
            if (value < 0.0) {
                return "하락 영향 " + formatSignedPercent(value);
            }
            return "영향 중립";
        }

        int score = getHeuristicImpactScore();
        if (score > 0) {
            return "상승 영향 " + getImpactStrengthLabel();
        }
        if (score < 0) {
            return "하락 영향 " + getImpactStrengthLabel();
        }
        return "영향 중립";
    }

    public String getImpactStrengthLabel() {
        int magnitude = getImpactMagnitude();
        if (magnitude >= 85) return "매우 강함";
        if (magnitude >= 70) return "강함";
        if (magnitude >= 50) return "보통";
        if (magnitude >= 30) return "약함";
        return "미미함";
    }

    public boolean isPositiveImpact() {
        return "positive".equals(getImpactTone());
    }

    public boolean isNegativeImpact() {
        return "negative".equals(getImpactTone());
    }

    public String getRiseSummaryText() {
        if (!hasRisePrediction()) {
            return "휴리스틱";
        }
        if (!isBlank(riseConfidence)) {
            return riseConfidence;
        }
        return isRisePositive() ? "상승 우세" : isRiseNegative() ? "하락 우세" : "중립";
    }

    private String getImpactTone() {
        if (hasStockImpactPrediction()) {
            double value = normalizeImpactPercent(stockImpactPercent);
            if (value > 0.05) {
                return "positive";
            }
            if (value < -0.05) {
                return "negative";
            }
            return "neutral";
        }

        int heuristicScore = getHeuristicImpactScore();
        if (heuristicScore > 0) {
            return "positive";
        }
        if (heuristicScore < 0) {
            return "negative";
        }
        return "neutral";
    }

    private String getRiseTone() {
        double probability = effectiveRiseProbability();
        if (probability >= 0.55) {
            return "positive";
        }
        if (probability <= 0.45) {
            return "negative";
        }
        return "neutral";
    }

    private boolean isRisePositive() {
        return "positive".equals(getRiseTone());
    }

    private boolean isRiseNegative() {
        return "negative".equals(getRiseTone());
    }

    private String getHeuristicImpactShortText() {
        int score = getHeuristicImpactScore();
        if (score > 0) {
            return "상승 추정";
        }
        if (score < 0) {
            return "하락 추정";
        }
        return "중립";
    }

    private int getSentimentSign() {
        if ("호재".equals(sentiment)) {
            return 1;
        }
        if ("악재".equals(sentiment)) {
            return -1;
        }
        return 0;
    }

    private double getArticleTypeWeight() {
        if (articleType == null) {
            return 0.85;
        }

        return switch (articleType.trim()) {
            case "사실형" -> 1.00;
            case "추론형" -> 0.92;
            case "예측형" -> 0.84;
            case "스피닝형" -> 0.78;
            default -> 0.85;
        };
    }

    private void parseCategory(String category) {
        if (category == null || category.isBlank()) {
            this.companyName = "";
            this.sectorName = "";
            return;
        }

        int openIdx = category.lastIndexOf('(');
        int closeIdx = category.lastIndexOf(')');
        if (openIdx > 0 && closeIdx > openIdx) {
            this.companyName = category.substring(0, openIdx).trim();
            this.sectorName = category.substring(openIdx + 1, closeIdx).trim();
        } else {
            this.sectorName = category.trim();
        }
    }

    private String resolveStockCode(String category) {
        if (category == null) {
            return null;
        }
        return normalizeStockCode(CATEGORY_TO_STOCK_CODE.get(category.trim()));
    }

    private String normalizeStockCode(String value) {
        if (isBlank(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.endsWith(".0")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        StringBuilder digits = new StringBuilder();
        for (char ch : trimmed.toCharArray()) {
            if (Character.isDigit(ch)) {
                digits.append(ch);
            }
        }
        if (digits.length() == 0) {
            return trimmed;
        }
        try {
            return String.format(Locale.KOREA, "%06d", Integer.parseInt(digits.toString()));
        } catch (NumberFormatException ex) {
            return digits.toString();
        }
    }

    private String formatSignedPercent(double value) {
        return String.format(Locale.KOREA, "%+.2f%%", value);
    }

    private double normalizeImpactPercent(Double rawValue) {
        if (rawValue == null) {
            return 0.0;
        }
        double value = rawValue;
        if (Math.abs(value) <= 1.0) {
            value = value * 100.0;
        }
        return clamp(value, -100.0, 100.0);
    }

    private double normalizeRiseProbability(Double rawValue) {
        if (rawValue == null) {
            return 0.0;
        }
        double value = rawValue;
        if (value > 1.0 && value <= 100.0) {
            value = value / 100.0;
        }
        return clamp(value, 0.0, 1.0);
    }

    private double effectiveRiseProbability() {
        if (hasRisePrediction()) {
            return normalizeRiseProbability(riseProbability);
        }

        int sentimentSign = getSentimentSign();
        double reliability = clamp((typeProb * 0.65) + ((100.0 - clickbaitProb) * 0.35), 0.0, 100.0);
        double reliabilityScore = (reliability - 50.0) / 50.0;

        double heuristic = 0.5;
        heuristic += sentimentSign * 0.14;
        heuristic += reliabilityScore * 0.10;
        return clamp(heuristic, 0.05, 0.95);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalizeConfidenceLabel(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\uFFFD', ' ').trim();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.contains("높")) {
            return "높음";
        }
        if (normalized.contains("중")) {
            return "중간";
        }
        if (normalized.contains("낮")) {
            return "낮음";
        }
        return null;
    }
}
