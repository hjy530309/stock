package com.Midterm.stock.service.stock;

import com.Midterm.stock.dto.StockChartDto;
import com.Midterm.stock.dto.StockResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ?섏쑉 ?쒖꽭 ?쒕퉬??
 * EXIM ?쇰퀎 ?섏쑉???곗꽑 ?ъ슜?댁꽌 李⑦듃? ?붿빟 媛믪쓽 湲곗???留욎텛怨?
 * 遺議깊븳 ?곗씠?곕쭔 KIS / Frankfurter / open.er-api ?쒖꽌濡?蹂댁셿?쒕떎.
 */
@Service
public class ExchangeService {

    private static final String KIS_FX_TR_ID = "HHDFS00000300";
    private static final DateTimeFormatter EXIM_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int EXCHANGE_CHART_POINTS = 30;
    private static final int EXCHANGE_LOOKBACK_DAYS = 60;

    private static final Map<String, String> KIS_SYMBOLS = Map.of(
            "USD", "FX@KRWUSD",
            "JPY", "FX@KRWJPY",
            "EUR", "FX@KRWEUR",
            "CNH", "FX@KRWCNH",
            "GBP", "FX@KRWGBP"
    );

    private static final Map<String, String> EXIM_UNITS = Map.of(
            "USD", "USD",
            "JPY", "JPY(100)",
            "EUR", "EUR",
            "CNH", "CNH",
            "GBP", "GBP"
    );

    private static final Map<String, String> FRANKFURTER_BASES = Map.of(
            "USD", "USD",
            "JPY", "JPY",
            "EUR", "EUR",
            "CNH", "CNY",
            "GBP", "GBP"
    );

    private final KisApiService kisApi;
    private final String eximApiKey;
    private final WebClient eximClient;
    private final WebClient frankfurterClient;
    private final WebClient erClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExchangeService(
            KisApiService kisApi,
            @Value("${exim.api.key:}") String eximApiKey
    ) {
        this.kisApi = kisApi;
        this.eximApiKey = eximApiKey;

        try {
            io.netty.handler.ssl.SslContext sslContext = io.netty.handler.ssl.SslContextBuilder.forClient()
                    .trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE)
                    .build();

            reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                    .secure(spec -> spec.sslContext(sslContext))
                    .responseTimeout(Duration.ofSeconds(10));

            this.eximClient = createClient("https://www.koreaexim.go.kr", httpClient);
            this.frankfurterClient = createClient("https://api.frankfurter.dev/v2", httpClient);
            this.erClient = createClient("https://open.er-api.com", httpClient);
        } catch (Exception e) {
            throw new RuntimeException("Exchange WebClient initialization failed", e);
        }
    }

    // 怨듯넻 ?ㅼ젙???곸슜??WebClient ?몄뒪?댁뒪瑜??앹꽦
    private WebClient createClient(String baseUrl, reactor.netty.http.client.HttpClient httpClient) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * ?섏쑉 ?쒖꽭 ?붿빟? EXIM -> KIS -> Frankfurter -> open.er-api ?쒖꽌濡?議고쉶?쒕떎.
     * ???④퀎?먯꽌 異⑸텇??媛믪씠 ?섏삤硫??ㅼ쓬 ?④퀎???몄텧?섏? ?딅뒗??
     */
    public StockResponseDto getExchangeRate(String currency) {
        String normalizedCurrency = normalizeCurrency(currency);
        StockResponseDto dto = emptyResponseDto();

        if (loadFromExim(normalizedCurrency, dto)) {
            return dto;
        }

        if (loadFromKis(normalizedCurrency, dto)) {
            return dto;
        }

        if (loadFromFrankfurter(normalizedCurrency, dto)) {
            return dto;
        }

        loadFromErApi(normalizedCurrency, dto);
        return dto;
    }

    /**
     * ?섏쑉 李⑦듃??理쒓렐 60?쇱쓣 ?묒뼱???뺣낫??理쒕? 30媛??ъ씤?몃? ?ъ슜?쒕떎.
     * EXIM 李⑦듃媛 鍮꾨㈃ Frankfurter, 留덉?留됱쑝濡?2?ъ씤???대갚 李⑦듃瑜?留뚮뱺??
     */
    public StockChartDto getExchangeChart(String currency) {
        String normalizedCurrency = normalizeCurrency(currency);
        String eximUnit = EXIM_UNITS.get(normalizedCurrency);
        List<String> labels = new ArrayList<>();
        List<String> prices = new ArrayList<>();
        List<String> volumes = new ArrayList<>();

        if (eximUnit != null && eximApiKey != null && !eximApiKey.isBlank()) {
            for (int i = 0; i < EXCHANGE_LOOKBACK_DAYS && labels.size() < EXCHANGE_CHART_POINTS; i++) {
                LocalDate date = LocalDate.now().minusDays(i);
                ExchangeSnapshot snapshot = fetchEximSnapshot(eximUnit, date);
                if (snapshot == null) {
                    continue;
                }

                labels.add(snapshot.date.format(EXIM_DATE_FORMAT));
                prices.add(formatNumber(snapshot.price));
                volumes.add("0");
            }
        }

        if (!labels.isEmpty()) {
            Collections.reverse(labels);
            Collections.reverse(prices);
            Collections.reverse(volumes);
            return buildChartDto(labels, prices, volumes);
        }

        StockChartDto frankfurterChart = buildFrankfurterChart(normalizedCurrency);
        if (!frankfurterChart.getLabels().isEmpty()) {
            return frankfurterChart;
        }

        return buildFallbackChart(normalizedCurrency);
    }

    // 李⑦듃 ?곗씠?곌? ?놁쓣 ???꾩옱媛? ?꾩씪媛留뚯쑝濡?理쒖냼 李⑦듃瑜?留뚮벀
    private StockChartDto buildFallbackChart(String currency) {
        StockResponseDto quote = getExchangeRate(currency);
        double currentPrice = parseNumber(quote.getCurrentPrice());
        if (currentPrice <= 0) {
            return emptyChart();
        }

        double priceChange = parseNumber(quote.getPriceChange());
        double previousPrice = currentPrice - priceChange;
        if (previousPrice <= 0 || Double.isNaN(previousPrice) || Double.isInfinite(previousPrice)) {
            previousPrice = currentPrice;
        }

        List<String> labels = new ArrayList<>();
        List<String> prices = new ArrayList<>();
        List<String> volumes = new ArrayList<>();
        labels.add(LocalDate.now().minusDays(1).format(EXIM_DATE_FORMAT));
        labels.add(LocalDate.now().format(EXIM_DATE_FORMAT));
        prices.add(formatNumber(previousPrice));
        prices.add(formatNumber(currentPrice));
        volumes.add("0");
        volumes.add("0");
        return buildChartDto(labels, prices, volumes);
    }

    // EXIM API?먯꽌 ?섏쑉 ?꾩옱媛? ?꾩씪 ?鍮??뺣낫瑜?梨꾩?
    private boolean loadFromExim(String currency, StockResponseDto dto) {
        String eximUnit = EXIM_UNITS.get(currency);
        if (eximUnit == null || eximApiKey == null || eximApiKey.isBlank()) {
            return false;
        }

        try {
            ExchangeSnapshot latest = findEximSnapshot(eximUnit, LocalDate.now(), 10);
            if (latest == null) {
                return false;
            }

            ExchangeSnapshot previous = findEximSnapshot(eximUnit, latest.date.minusDays(1), 10);

            dto.setCurrentPrice(formatNumber(latest.price));
            if (previous == null) {
                dto.setPriceChange("0");
                dto.setChangeRate("0");
                return true;
            }

            double priceChange = latest.price - previous.price;
            double changeRate = previous.price == 0 ? 0 : (priceChange / previous.price) * 100;

            dto.setPriceChange(formatSignedNumber(priceChange));
            dto.setChangeRate(formatSignedNumber(changeRate));
            return true;
        } catch (Exception e) {
            // System.out.println("Exim exchange quote failed [" + currency + "]: " + e.getMessage());
            return false;
        }
    }

    // EXIM ?ㅽ뙣 ??KIS ?섏쑉 ?쒖꽭濡??꾩옱媛? ?깅씫瑜좎쓣 蹂댁셿
    private boolean loadFromKis(String currency, StockResponseDto dto) {
        String symbol = KIS_SYMBOLS.get(currency);
        if (symbol == null) {
            return false;
        }

        try {
            kisApi.issueToken();
            JsonNode response = kisApi.get(uriBuilder -> uriBuilder
                    .path("/uapi/overseas-price/v1/quotations/price")
                    .queryParam("AUTH", "")
                    .queryParam("EXCD", "FX")
                    .queryParam("SYMB", symbol)
                    .build(), KIS_FX_TR_ID);

            JsonNode output = response == null ? null : response.get("output");
            if (output == null || output.isNull()) {
                return false;
            }

            String currentPrice = firstText(output, "stck_prpr", "last", "ovrs_nmix_prpr", "base_price");
            if (!hasPositiveNumber(currentPrice)) {
                return false;
            }

            double currentPriceValue = parseNumber(currentPrice);
            dto.setCurrentPrice(formatNumber(currentPriceValue));

            String priceChangeText = firstText(output, "prdy_vrss", "tday_rltv", "change");
            String changeRateText = firstText(output, "prdy_ctrt", "tday_rltv_rt", "change_rate");

            if (priceChangeText.isBlank() || changeRateText.isBlank()
                    || (parseNumber(priceChangeText) == 0 && parseNumber(changeRateText) == 0)) {
                Double previousClose = fetchKisPreviousClose(symbol, currentPriceValue);
                if (previousClose != null && previousClose > 0) {
                    double priceChange = currentPriceValue - previousClose;
                    double changeRate = previousClose == 0 ? 0 : (priceChange / previousClose) * 100;
                    dto.setPriceChange(formatSignedNumber(priceChange));
                    dto.setChangeRate(formatSignedNumber(changeRate));
                    return true;
                }
            }

            dto.setPriceChange(defaultZero(priceChangeText));
            dto.setChangeRate(defaultZero(changeRateText));
            return true;
        } catch (Exception e) {
            // System.out.println("KIS exchange quote failed [" + currency + "]: " + e.getMessage());
            return false;
        }
    }

    // KIS ?ㅽ뙣 ??Frankfurter ?섏쑉 ?곗씠?곕? ?ъ슜???꾩옱媛瑜?怨꾩궛
    private boolean loadFromFrankfurter(String currency, StockResponseDto dto) {
        try {
            ExchangeRatePoint latest = fetchFrankfurterRate(currency, null);
            if (latest == null || latest.price <= 0) {
                return false;
            }

            dto.setCurrentPrice(formatNumber(latest.price));

            ExchangeRatePoint previous = fetchFrankfurterPreviousRate(currency, latest.date);
            if (previous == null || previous.price <= 0) {
                dto.setPriceChange("0");
                dto.setChangeRate("0");
                return true;
            }

            double priceChange = latest.price - previous.price;
            double changeRate = previous.price == 0 ? 0 : (priceChange / previous.price) * 100;
            dto.setPriceChange(formatSignedNumber(priceChange));
            dto.setChangeRate(formatSignedNumber(changeRate));
            return true;
        } catch (Exception e) {
            // System.out.println("Frankfurter exchange quote failed [" + currency + "]: " + e.getMessage());
            return false;
        }
    }

    // 留덉?留??泥??섎떒?쇰줈 open.er-api 媛믪쓣 ?꾩옱媛 DTO??諛섏쁺?쒕떎.
    private void loadFromErApi(String currency, StockResponseDto dto) {
        try {
            String raw = erClient.get()
                    .uri("/v6/latest/" + currency)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (raw == null) {
                return;
            }

            JsonNode root = objectMapper.readTree(raw);
            if (!"success".equals(root.path("result").asText())) {
                return;
            }

            double krw = root.path("rates").path("KRW").asDouble(0);
            if (krw <= 0) {
                return;
            }

            if ("JPY".equals(currency)) {
                krw *= 100;
            }

            dto.setCurrentPrice(String.format(Locale.US, "%.2f", krw));
        } catch (Exception e) {
            // System.out.println("Fallback exchange quote failed [" + currency + "]: " + e.getMessage());
        }
    }

    // Frankfurter ?쒓퀎???묐떟??李⑦듃 DTO濡?蹂??
    private StockChartDto buildFrankfurterChart(String currency) {
        try {
            // EXIM 李⑦듃媛 鍮꾨뒗 寃쎌슦?먮쭔 ?몃? ?섏쑉 湲곌컙 API濡??쇰퀎 ?ъ씤?몃? 蹂닿컯
            List<ExchangeRatePoint> points = fetchFrankfurterSeries(
                    currency,
                    LocalDate.now().minusDays(EXCHANGE_LOOKBACK_DAYS),
                    LocalDate.now()
            );
            if (points.isEmpty()) {
                return emptyChart();
            }

            int startIndex = Math.max(0, points.size() - EXCHANGE_CHART_POINTS);
            List<String> labels = new ArrayList<>();
            List<String> prices = new ArrayList<>();
            List<String> volumes = new ArrayList<>();
            for (int i = startIndex; i < points.size(); i++) {
                ExchangeRatePoint point = points.get(i);
                labels.add(point.date.format(EXIM_DATE_FORMAT));
                prices.add(formatNumber(point.price));
                volumes.add("0");
            }
            return buildChartDto(labels, prices, volumes);
        } catch (Exception e) {
            // System.out.println("Frankfurter exchange chart failed [" + currency + "]: " + e.getMessage());
            return emptyChart();
        }
    }

    // ?쇰꺼, 媛寃? 嫄곕옒??諛곗뿴???쒖? 李⑦듃 DTO濡?
    private StockChartDto buildChartDto(List<String> labels, List<String> prices, List<String> volumes) {
        StockChartDto dto = new StockChartDto();
        dto.setLabels(labels);
        dto.setClosePrices(prices);
        dto.setVolumes(volumes);
        return dto;
    }

    // 理쒓렐 硫곗튌 踰붿쐞 ?덉뿉???ъ슜 媛?ν븳 EXIM ?섏쑉 ?ㅻ깄?룹쓣 李얠쓬
    private ExchangeSnapshot findEximSnapshot(String unit, LocalDate startDate, int maxDaysBack) {
        for (int i = 0; i < maxDaysBack; i++) {
            LocalDate date = startDate.minusDays(i);
            ExchangeSnapshot snapshot = fetchEximSnapshot(unit, date);
            if (snapshot != null) {
                return snapshot;
            }
        }
        return null;
    }

    // ?뱀젙 ?좎쭨??EXIM ?섏쑉 ?곗씠?곕? ?④굔 議고쉶
    private ExchangeSnapshot fetchEximSnapshot(String unit, LocalDate date) {
        try {
            String raw = eximClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/site/program/financial/exchangeJSON")
                            .queryParam("authkey", eximApiKey)
                            .queryParam("searchdate", date.format(EXIM_DATE_FORMAT))
                            .queryParam("data", "AP01")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (raw == null || raw.isBlank() || !raw.trim().startsWith("[")) {
                return null;
            }

            JsonNode items = objectMapper.readTree(raw);
            for (JsonNode item : items) {
                if (!unit.equals(item.path("cur_unit").asText())) {
                    continue;
                }

                double price = parseNumber(item.path("deal_bas_r").asText());
                if (price > 0) {
                    return new ExchangeSnapshot(date, price);
                }
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
    }

    // 議고쉶 ?ㅽ뙣 ?쒖뿉???붾㈃??源⑥?吏 ?딅룄濡?湲곕낯 ?섏쑉 DTO瑜?留뚮벀
    private StockResponseDto emptyResponseDto() {
        StockResponseDto dto = new StockResponseDto();
        dto.setCurrentPrice("0");
        dto.setPriceChange("0");
        dto.setChangeRate("0");
        return dto;
    }

    // ?곗씠?곌? ?놁쓣 ???ъ슜??鍮??섏쑉 李⑦듃 DTO瑜?留뚮몱
    private StockChartDto emptyChart() {
        StockChartDto dto = new StockChartDto();
        dto.setLabels(new ArrayList<>());
        dto.setClosePrices(new ArrayList<>());
        dto.setVolumes(new ArrayList<>());
        return dto;
    }

    // ?붾㈃/?몃? API?먯꽌 ?ㅼ뼱???듯솕 肄붾뱶瑜??대? ?쒖? 肄붾뱶濡??뺢퇋??
    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "USD";
        }

        String normalized = currency.toUpperCase(Locale.ROOT).trim();
        int bracketIndex = normalized.indexOf('(');
        if (bracketIndex >= 0) {
            normalized = normalized.substring(0, bracketIndex);
        }
        return normalized;
    }

    // ?щ윭 ?꾨낫 ?꾨뱶 以?泥?踰덉㎏ ?좏슚 臾몄옄??媛믪쓣 媛?몄샂
    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }

            String text = value.asText();
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    // ?뱀젙 ?좎쭨??Frankfurter ?섏쑉 媛믪쓣 ?④굔 議고쉶
    private ExchangeRatePoint fetchFrankfurterRate(String currency, LocalDate date) {
        String base = FRANKFURTER_BASES.get(currency);
        if (base == null) {
            return null;
        }

        JsonNode node = frankfurterClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/rate/{base}/KRW");
                    if (date != null) {
                        builder.queryParam("date", date);
                    }
                    return builder.build(base);
                })
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (node == null || node.isNull()) {
            return null;
        }

        double rate = node.path("rate").asDouble(0);
        String dateText = node.path("date").asText();
        if (rate <= 0 || dateText == null || dateText.isBlank()) {
            return null;
        }
        return new ExchangeRatePoint(LocalDate.parse(dateText), applyDisplayMultiplier(currency, rate));
    }

    // 湲곗????댁쟾 媛??媛源뚯슫 Frankfurter ?섏쑉 媛믪쓣 李얠쓬
    private ExchangeRatePoint fetchFrankfurterPreviousRate(String currency, LocalDate latestDate) {
        List<ExchangeRatePoint> points = fetchFrankfurterSeries(currency, latestDate.minusDays(7), latestDate.minusDays(1));
        if (points.isEmpty()) {
            return null;
        }
        return points.get(points.size() - 1);
    }

    // ?좎쭨 援ш컙??Frankfurter ?쒓퀎???섏쑉 ?곗씠?곕? ?섏쭛
    private List<ExchangeRatePoint> fetchFrankfurterSeries(String currency, LocalDate from, LocalDate to) {
        String base = FRANKFURTER_BASES.get(currency);
        if (base == null || to.isBefore(from)) {
            return List.of();
        }

        JsonNode root = frankfurterClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/rates")
                        .queryParam("base", base)
                        .queryParam("quotes", "KRW")
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (root == null || !root.isArray()) {
            return List.of();
        }

        List<ExchangeRatePoint> points = new ArrayList<>();
        for (JsonNode item : root) {
            String dateText = item.path("date").asText();
            double rate = item.path("rate").asDouble(0);
            if (dateText == null || dateText.isBlank() || rate <= 0) {
                continue;
            }
            points.add(new ExchangeRatePoint(LocalDate.parse(dateText), applyDisplayMultiplier(currency, rate)));
        }
        return points;
    }

    // KIS ?묐떟???깅씫 ?뺣낫媛 ?놁쓣 ???댁쟾 醫낃?瑜?異붿젙??蹂댁셿
    private Double fetchKisPreviousClose(String symbol, double currentPrice) {
        try {
            JsonNode response = kisApi.get(uriBuilder -> uriBuilder
                    .path("/uapi/overseas-price/v1/quotations/dailyprice")
                    .queryParam("AUTH", "")
                    .queryParam("EXCD", "FX")
                    .queryParam("SYMB", symbol)
                    .queryParam("GUBN", "0")
                    .queryParam("BYMD", "")
                    .queryParam("MODP", "0")
                    .build(), "HHDFS76240000");

            JsonNode dailyRows = response == null ? null : response.get("output2");
            if (dailyRows == null || !dailyRows.isArray()) {
                return null;
            }

            Double latestClose = null;
            Double previousClose = null;
            for (JsonNode row : dailyRows) {
                double close = firstPositiveNumber(
                        row,
                        "clos", "close", "last", "ovrs_nmix_prpr", "base_price", "open"
                );
                if (close <= 0) {
                    continue;
                }
                if (latestClose == null) {
                    latestClose = close;
                    continue;
                }
                previousClose = close;
                break;
            }

            if (previousClose != null) {
                return previousClose;
            }
            if (latestClose != null && latestClose > 0 && currentPrice > 0 && latestClose != currentPrice) {
                return latestClose;
            }
        } catch (Exception e) {
            // System.out.println("KIS exchange dailyprice fallback failed [" + symbol + "]: " + e.getMessage());
        }
        return null;
    }

    // ?щ윭 ?꾨뱶 ?꾨낫 以?0蹂대떎 ??泥?踰덉㎏ ?レ옄 媛믪쓣 李얠쓬
    private double firstPositiveNumber(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            double value = parseNumber(firstText(node, fieldName));
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    // 臾몄옄?댁씠 0蹂대떎 ???レ옄濡??댁꽍 媛?ν븳吏 ?뺤씤
    private boolean hasPositiveNumber(String value) {
        return parseNumber(value) > 0;
    }

    // 鍮꾩뼱 ?덈뒗 ?レ옄 臾몄옄?댁쓣 0?쇰줈 移섑솚
    private String defaultZero(String value) {
        return value == null || value.isBlank() ? "0" : value;
    }

    // 臾몄옄???섏쑉 媛믪쓣 怨꾩궛 媛?ν븳 ?レ옄濡?蹂??
    private double parseNumber(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }

        try {
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ?レ옄 媛믪쓣 ?붾㈃怨?DTO?먯꽌 怨듯넻?쇰줈 ?곕뒗 臾몄옄???뺤떇?쇰줈 蹂??
    private String formatNumber(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    // ?깅씫 ?レ옄瑜?遺?멸? ?ы븿??臾몄옄?대줈 蹂??
    private String formatSignedNumber(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private record ExchangeSnapshot(LocalDate date, double price) {
    }

    // JPY(100) 媛숈? ?쒖떆 ?⑥쐞瑜?留욎텛湲??꾪빐 ?듯솕蹂?諛곗쑉???곸슜
    private double applyDisplayMultiplier(String currency, double rate) {
        return "JPY".equals(currency) ? rate * 100 : rate;
    }

    private record ExchangeRatePoint(LocalDate date, double price) {
    }
}
