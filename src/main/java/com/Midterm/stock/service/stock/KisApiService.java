package com.Midterm.stock.service.stock;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;

/**
 * KIS API 怨듯넻 ?쒕퉬??
 * - OAuth ?좏겙 諛쒓툒 諛?罹먯떛
 * - 怨듯넻 GET ?붿껌 ?ы띁
 * - kisClient WebClient 愿由?
 */
@Service
public class KisApiService {

    private final String appKey;
    private final String appSecret;
    final WebClient kisClient; // package-private: 媛숈? ?⑦궎吏 ?쒕퉬?ㅼ뿉???묎렐

    private String accessToken;
    private LocalDateTime tokenExpireTime;

    public KisApiService(
            @Value("${kis.api.app-key}") String appKey,
            @Value("${kis.api.app-secret}") String appSecret,
            @Value("${kis.api.base-url}") String baseUrl
    ) {
        this.appKey = appKey;
        this.appSecret = appSecret;

        try {
            io.netty.handler.ssl.SslContext sslContext =
                    io.netty.handler.ssl.SslContextBuilder.forClient()
                            .trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE)
                            .build();

            reactor.netty.resources.ConnectionProvider provider =
                    reactor.netty.resources.ConnectionProvider.builder("kis-pool")
                            .maxConnections(10)
                            .maxIdleTime(java.time.Duration.ofSeconds(15))
                            .maxLifeTime(java.time.Duration.ofSeconds(50))
                            .pendingAcquireTimeout(java.time.Duration.ofSeconds(10))
                            .evictInBackground(java.time.Duration.ofSeconds(10))
                            .build();

            reactor.netty.http.client.HttpClient httpClient =
                    reactor.netty.http.client.HttpClient.create(provider)
                            .secure(t -> t.sslContext(sslContext)
                                    .handshakeTimeout(java.time.Duration.ofSeconds(30)))
                            .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000)
                            .responseTimeout(java.time.Duration.ofSeconds(15))
                            .doOnConnected(conn -> conn
                                    .addHandlerLast(new io.netty.handler.timeout.ReadTimeoutHandler(
                                            15, java.util.concurrent.TimeUnit.SECONDS))
                                    .addHandlerLast(new io.netty.handler.timeout.WriteTimeoutHandler(
                                            15, java.util.concurrent.TimeUnit.SECONDS)));

            this.kisClient = WebClient.builder()
                    .baseUrl(baseUrl)
                    .clientConnector(new org.springframework.http.client.reactive
                            .ReactorClientHttpConnector(httpClient))
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("KIS WebClient 珥덇린???ㅽ뙣", e);
        }
    }

    /**
     * KIS OAuth ?좏겙 諛쒓툒 (23?쒓컙 罹먯떛)
     * synchronized: ?숈떆 ?ㅼ쨷 ?붿껌 ??以묐났 諛쒓툒 諛⑹?
     */
    public synchronized void issueToken() {
        if (accessToken != null && tokenExpireTime != null
                && LocalDateTime.now().isBefore(tokenExpireTime)) {
            return;
        }
        try {
            String body = "{\"grant_type\":\"client_credentials\","
                    + "\"appkey\":\"" + appKey + "\","
                    + "\"appsecret\":\"" + appSecret + "\"}";

            JsonNode response = kisClient.post()
                    .uri("/oauth2/tokenP")
                    .header("content-type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null && response.has("access_token")) {
                this.accessToken = response.get("access_token").asText();
                this.tokenExpireTime = LocalDateTime.now().plusHours(23);
                // System.out.println("?좏겙 諛쒓툒 ?꾨즺!");
            }
        } catch (Exception e) {
            // System.out.println("?좏겙 諛쒓툒 ?ㅻ쪟: " + e.getMessage());
        }
    }

    // KIS API ?붿껌 媛?理쒖냼 媛꾧꺽 (100ms) - 珥덈떦 10???쒗븳 ???
    private long lastCallMs = 0;
    private static final long MIN_CALL_INTERVAL_MS = 100;

    /**
     * KIS API 怨듯넻 GET ?붿껌 (理쒕? 2???ъ떆??+ ?몄텧 媛꾧꺽 ?쒗븳)
     */
    public JsonNode get(
            java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFunc,
            String trId) {
        if (accessToken == null) {
            // System.out.println("?좏겙 ?놁쓬 - KIS API ?ㅽ궢 [" + trId + "]");
            return null;
        }

        // ?몄텧 媛꾧꺽 蹂댁옣 (synchronized濡?吏곷젹??
        synchronized (this) {
            long now = System.currentTimeMillis();
            long wait = MIN_CALL_INTERVAL_MS - (now - lastCallMs);
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            lastCallMs = System.currentTimeMillis();
        }

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return kisClient.get()
                        .uri(uriFunc)
                        .header("authorization", "Bearer " + accessToken)
                        .header("appkey", appKey)
                        .header("appsecret", appSecret)
                        .header("tr_id", trId)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();
            } catch (Exception e) {
                // System.out.println("KIS API ?ㅻ쪟 [" + trId + "] ?쒕룄 " + attempt + ": " + e.getMessage());
                if (attempt == 2) return null;
                try { Thread.sleep(600); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return null;
    }
}
