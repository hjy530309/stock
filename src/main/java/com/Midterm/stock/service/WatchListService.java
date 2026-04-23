package com.Midterm.stock.service;

import com.Midterm.stock.entity.StockAlert;
import com.Midterm.stock.entity.WatchList;
import com.Midterm.stock.repository.StockAlertRepository;
import com.Midterm.stock.repository.StockRepository;
import com.Midterm.stock.repository.UserDao;
import com.Midterm.stock.repository.WatchListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WatchListService {

    private static final String ALERT_TYPE_COMMENT = "\uB313\uAE00";
    private static final String ALERT_TYPE_RISE = "\uC0C1\uC2B9";
    private static final String ALERT_TYPE_FALL = "\uD558\uB77D";
    private static final String ALERT_TYPE_PRICE_SURGE = "\uAC00\uACA9\uAE09\uB4F1";
    private static final String ALERT_TYPE_PRICE_DROP = "\uAC00\uACA9\uAE09\uB77D";
    private static final String ALERT_TYPE_VOLUME_SURGE = "\uAC70\uB798\uB7C9\uAE09\uB4F1";
    private static final String ALERT_TYPE_PATTERN_ANOMALY = "\uD328\uD134\uC774\uC0C1";
    private static final String ALERT_TYPE_REALTIME_SURGE = "\uC2E4\uC2DC\uAC04_\uAE09\uB4F1";
    private static final String ALERT_TYPE_REALTIME_CAUTION = "\uC2E4\uC2DC\uAC04_\uC8FC\uC758";
    private static final String ALERT_TYPE_ANOMALY_CAUTION = "\uC774\uC0C1\uAC70\uB798_\uC8FC\uC758";
    private static final String ALERT_TYPE_ANOMALY_SEVERE = "\uC774\uC0C1\uAC70\uB798_\uC2EC\uAC01";
    private static final String ALERT_TYPE_LEGACY_SURGE = "\uAE09\uB4F1";
    private static final String ALERT_TYPE_LEGACY_DROP = "\uAE09\uB77D";
    private static final String ALERT_TYPE_LEGACY_ANOMALY = "\uC774\uC0C1";
    private static final String ALERT_TYPE_LEGACY_CAUTION = "\uC8FC\uC758";
    private static final String ALERT_TYPE_LEGACY_WARNING = "\uACBD\uACE0";

    private static final String ALERT_VARIANT_COMMENT = "comment";
    private static final String ALERT_VARIANT_RISE = "rise";
    private static final String ALERT_VARIANT_FALL = "fall";
    private static final String ALERT_VARIANT_SURGE = "surge";
    private static final String ALERT_VARIANT_ANOMALY = "anomaly";

    private static final String COMMENT_TITLE = "\uB0B4 \uAC8C\uC2DC\uAE00\uC5D0 \uC0C8 \uB313\uAE00";
    private static final String COMMENT_MESSAGE_SUFFIX = "\uAC8C\uC2DC\uAE00\uC5D0 \uB313\uAE00\uC744 \uB0A8\uACBC\uC2B5\uB2C8\uB2E4.";

    private final WatchListRepository watchListRepository;
    private final StockAlertRepository stockAlertRepository;
    private final StockRepository stockRepository;
    private final UserDao userDao;
    private final Map<String, String> stockDisplayNameCache = new ConcurrentHashMap<>();

    @Transactional
    public boolean addWatch(Integer userNum, String stockCode, String stockName) {
        if (watchListRepository.existsByUserNumAndStockCode(userNum, stockCode)) {
            return false;
        }

        WatchList watch = new WatchList();
        watch.setUserNum(userNum);
        watch.setStockCode(stockCode);
        watch.setStockName(stockName);
        watchListRepository.save(watch);
        return true;
    }

    @Transactional
    public void removeWatch(Integer userNum, String stockCode) {
        watchListRepository.deleteByUserNumAndStockCode(userNum, stockCode);
    }

    public boolean isWatching(Integer userNum, String stockCode) {
        return watchListRepository.existsByUserNumAndStockCode(userNum, stockCode);
    }

    public List<WatchList> getWatchList(Integer userNum) {
        return watchListRepository.findByUserNumOrderByCreatedAtDesc(userNum);
    }

    public List<StockAlert> getAlerts(Integer userNum) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        return stockAlertRepository.findByUserNumAndCreatedAtAfterOrderByCreatedAtDesc(userNum, since);
    }

    public long getUnreadCount(Integer userNum) {
        return stockAlertRepository.countByUserNumAndAlertReadFalse(userNum);
    }

    @Transactional
    public void markAllRead(Integer userNum) {
        stockAlertRepository.markAllRead(userNum);
    }

    public Map<String, Object> buildAlertPanel(Integer userNum) {
        List<StockAlert> alerts = getAlerts(userNum);
        long unreadCount = getUnreadCount(userNum);

        List<Map<String, Object>> items = alerts.stream()
            .map(this::toAlertItem)
            .collect(Collectors.toList());

        // 알림창 기능
        Map<String, Boolean> notifySettings = new HashMap<>();
        notifySettings.put("stock", isNotifyEnabled(userNum));
        notifySettings.put("comment", isCommentNotifyEnabled(userNum));

        Map<String, Object> result = new HashMap<>();
        result.put("unreadCount", unreadCount);
        result.put("alerts", items);
        result.put("notifySettings", notifySettings);
        return result;
    }

    public boolean isNotifyEnabled(Integer userNum) {
        Integer status = userDao.findNotifyStockStatusByNum(userNum);
        return status != null && status == 1;
    }

    public boolean isCommentNotifyEnabled(Integer userNum) {
        Integer status = userDao.findNotifyCommentStatusByNum(userNum);
        return status != null && status == 1;
    }

    @Transactional
    public void updateNotifySetting(Integer userNum, String type, int status) {
        userDao.updateNotifySetting(userNum, type, status);
    }

    private Map<String, Object> toAlertItem(StockAlert alert) {
        Map<String, Object> item = new HashMap<>();
        String alertType = safeText(alert.getAlertType());
        boolean isCommunityComment = ALERT_TYPE_COMMENT.equals(alertType);

        item.put("id", alert.getId());
        item.put("stockCode", safeText(alert.getStockCode()));
        item.put("stockName", safeText(alert.getStockName()));
        item.put("alertType", alertType);
        item.put("changeRate", safeText(alert.getChangeRate()));
        item.put("price", safeText(alert.getPrice()));
        item.put("read", alert.isAlertRead());
        item.put("createdAt", alert.getCreatedAt() == null ? null : alert.getCreatedAt().toString());
        item.put("variant", resolveAlertVariant(alertType));

        if (isCommunityComment) {
            item.put("link", "/community/detail?board_id=" + safeText(alert.getStockCode()));
            item.put("title", COMMENT_TITLE);
            item.put(
                "message",
                safeText(alert.getChangeRate()) + "\uB2D8\uC774 '" + safeText(alert.getStockName()) + "' " + COMMENT_MESSAGE_SUFFIX
            );
            return item;
        }

        item.put("link", "/market/" + safeText(alert.getStockCode()));
        item.put("title", buildStockAlertTitle(alert));
        item.put("message", buildStockAlertMessage(alert));
        return item;
    }

    private String buildStockAlertTitle(StockAlert alert) {
        return getAlertTypeLabel(safeText(alert.getAlertType()));
    }

    private String buildStockAlertMessage(StockAlert alert) {
        String alertType = safeText(alert.getAlertType());
        String displayType = getAlertTypeLabel(alertType);
        String changeRate = formatChangeRate(alert.getChangeRate());
        String price = formatPriceMessage(alert.getPrice());
        String stockName = buildStockDisplayName(alert);
        String variant = resolveAlertVariant(alertType);

        if (!isDirectionalVariant(variant)) {
            return joinParts(
                " \u00B7 ",
                stockName,
                prefixValue("\uBCC0\uB3D9\uB960 ", changeRate),
                prefixValue("\uD604\uC7AC\uAC00 ", price)
            );
        }

        String prefix = ALERT_VARIANT_RISE.equals(variant) ? "\u25B2" : "\u25BC";
        return joinParts(" ", stockName, prefix, changeRate, displayType, "\u00B7", price);
    }

    private String buildStockDisplayName(StockAlert alert) {
        String stockName = safeText(alert.getStockName());
        String stockCode = safeText(alert.getStockCode());

        if (!stockName.isBlank() && !stockName.equals(stockCode)) {
            return stockName;
        }

        String resolvedName = resolveStockDisplayName(stockCode);
        if (!resolvedName.isBlank()) {
            return resolvedName;
        }

        if (!stockName.isBlank()) {
            return stockName;
        }
        return stockCode;
    }

    private String resolveStockDisplayName(String stockCode) {
        if (stockCode.isBlank()) {
            return "";
        }

        return stockDisplayNameCache.computeIfAbsent(stockCode, key -> {
            String displayName = stockRepository.findDisplayNameByStockCode(key);
            return safeText(displayName);
        });
    }

    private String resolveAlertVariant(String alertType) {
        if (ALERT_TYPE_COMMENT.equals(alertType)) {
            return ALERT_VARIANT_COMMENT;
        }
        if (
            ALERT_TYPE_RISE.equals(alertType)
            || ALERT_TYPE_PRICE_SURGE.equals(alertType)
        ) {
            return ALERT_VARIANT_RISE;
        }
        if (
            ALERT_TYPE_FALL.equals(alertType)
            || ALERT_TYPE_PRICE_DROP.equals(alertType)
            || ALERT_TYPE_LEGACY_DROP.equals(alertType)
        ) {
            return ALERT_VARIANT_FALL;
        }
        if (
            ALERT_TYPE_LEGACY_SURGE.equals(alertType)
            || ALERT_TYPE_VOLUME_SURGE.equals(alertType)
            || ALERT_TYPE_REALTIME_SURGE.equals(alertType)
            || ALERT_TYPE_ANOMALY_SEVERE.equals(alertType)
            || ALERT_TYPE_LEGACY_WARNING.equals(alertType)
        ) {
            return ALERT_VARIANT_SURGE;
        }
        return ALERT_VARIANT_ANOMALY;
    }

    private boolean isDirectionalVariant(String variant) {
        return ALERT_VARIANT_RISE.equals(variant) || ALERT_VARIANT_FALL.equals(variant);
    }

    private String getAlertTypeLabel(String alertType) {
        if (alertType.isBlank()) {
            return "";
        }

        return switch (alertType) {
            case ALERT_TYPE_LEGACY_ANOMALY -> "\uC774\uC0C1 \uAC70\uB798 \uAC10\uC9C0";
            case ALERT_TYPE_LEGACY_CAUTION, ALERT_TYPE_ANOMALY_CAUTION -> "\uC774\uC0C1 \uAC70\uB798 \uC8FC\uC758";
            case ALERT_TYPE_LEGACY_WARNING, ALERT_TYPE_ANOMALY_SEVERE -> "\uC774\uC0C1 \uAC70\uB798 \uC2EC\uAC01";
            case ALERT_TYPE_LEGACY_SURGE -> "\uC2E4\uC2DC\uAC04 \uAE09\uB4F1 \uAC10\uC9C0";
            case ALERT_TYPE_LEGACY_DROP -> "\uC2E4\uC2DC\uAC04 \uAE09\uB77D \uAC10\uC9C0";
            case ALERT_TYPE_PRICE_SURGE -> "\uAC00\uACA9 \uAE09\uB4F1 \uAC10\uC9C0";
            case ALERT_TYPE_PRICE_DROP -> "\uAC00\uACA9 \uAE09\uB77D \uAC10\uC9C0";
            case ALERT_TYPE_VOLUME_SURGE -> "\uAC70\uB798\uB7C9 \uAE09\uB4F1 \uAC10\uC9C0";
            case ALERT_TYPE_PATTERN_ANOMALY -> "\uD328\uD134 \uC774\uC0C1 \uAC10\uC9C0";
            case ALERT_TYPE_REALTIME_SURGE -> "\uC2E4\uC2DC\uAC04 \uAE09\uB4F1 \uAC10\uC9C0";
            case ALERT_TYPE_REALTIME_CAUTION -> "\uC2E4\uC2DC\uAC04 \uC8FC\uC758 \uAC10\uC9C0";
            default -> alertType.replace('_', ' ');
        };
    }

    private String formatChangeRate(String changeRate) {
        String value = safeText(changeRate);
        if (value.isBlank()) {
            return "";
        }
        if (value.contains("%")) {
            return value;
        }
        if (value.matches("[+-]?\\d+(?:\\.\\d+)?")) {
            return value + "%";
        }
        return value;
    }

    private String formatPriceMessage(String price) {
        String value = NumberFormatHelper.formatPrice(price);
        if (value.isBlank() || "0".equals(value)) {
            return "";
        }
        return value.endsWith("\uC6D0") ? value : value + "\uC6D0";
    }

    private String prefixValue(String prefix, String value) {
        return value.isBlank() ? "" : prefix + value;
    }

    private String joinParts(String delimiter, String... parts) {
        return Arrays.stream(parts)
            .filter(part -> part != null && !part.isBlank())
            .collect(Collectors.joining(delimiter));
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    static class NumberFormatHelper {
        static String formatPrice(String price) {
            try {
                return String.format("%,d", Long.parseLong(price.replace(",", "").trim()));
            } catch (Exception exception) {
                return price == null ? "0" : price.trim();
            }
        }
    }
}
