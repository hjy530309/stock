package com.Midterm.stock.scheduler;

import com.Midterm.stock.dto.StockResponseDto;
import com.Midterm.stock.entity.StockAlert;
import com.Midterm.stock.entity.WatchList;
import com.Midterm.stock.repository.StockAlertRepository;
import com.Midterm.stock.repository.WatchListRepository;
import com.Midterm.stock.service.WatchListService;
import com.Midterm.stock.service.stock.StockPriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AlertScheduler {

    private static final double ALERT_THRESHOLD = 0.5;
    private static final String ALERT_TYPE_RISE = "\uC0C1\uC2B9";
    private static final String ALERT_TYPE_FALL = "\uD558\uB77D";

    private final WatchListRepository watchListRepository;
    private final StockAlertRepository stockAlertRepository;
    private final StockPriceService stockPriceService;
    private final WatchListService watchListService;

    @Scheduled(cron = "0 */5 9-15 * * MON-FRI")
    @Transactional
    public void checkPriceAlerts() {
        if (LocalTime.now().isAfter(LocalTime.of(15, 30))) {
            return;
        }

        List<Object[]> distinctStocks = watchListRepository.findDistinctStocks();
        if (distinctStocks.isEmpty()) {
            return;
        }

        LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();

        for (Object[] row : distinctStocks) {
            String code = (String) row[0];
            String name = (String) row[1];

            try {
                StockResponseDto price = stockPriceService.getCurrentPrice(code);
                if (price == null || price.getChangeRate() == null) {
                    continue;
                }

                double rate = parseRate(price.getChangeRate());
                if (Math.abs(rate) < ALERT_THRESHOLD) {
                    continue;
                }

                String alertType = rate > 0 ? ALERT_TYPE_RISE : ALERT_TYPE_FALL;
                List<WatchList> watchers = watchListRepository.findByStockCode(code);

                for (WatchList watcher : watchers) {
                    if (!watchListService.isNotifyEnabled(watcher.getUserNum())) {
                        continue;
                    }

                    boolean exists = stockAlertRepository.existsTodayAlert(
                        watcher.getUserNum(),
                        code,
                        alertType,
                        startOfDay
                    );
                    if (exists) {
                        continue;
                    }

                    StockAlert alert = new StockAlert();
                    alert.setUserNum(watcher.getUserNum());
                    alert.setStockCode(code);
                    alert.setStockName(name);
                    alert.setChangeRate(String.format("%.2f", Math.abs(rate)));
                    alert.setAlertType(alertType);
                    alert.setPrice(price.getCurrentPrice());
                    alert.setAlertRead(false);
                    stockAlertRepository.save(alert);
                }

                Thread.sleep(200);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // Console noise intentionally suppressed during scheduled polling.
            }
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void cleanOldAlerts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        stockAlertRepository.deleteOlderThan(cutoff);
    }

    private double parseRate(String rate) {
        try {
            return Double.parseDouble(rate.replace(",", "").trim());
        } catch (Exception exception) {
            return 0.0;
        }
    }
}
