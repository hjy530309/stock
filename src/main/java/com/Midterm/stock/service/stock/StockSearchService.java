package com.Midterm.stock.service.stock;

import com.Midterm.stock.entity.Stock;
import com.Midterm.stock.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 종목 검색 + DB 관리 서비스
 * - DB에서 종목명/코드 키워드 검색
 * - Python(FinanceDataReader)으로 전체 종목 목록 갱신
 * - KRX API 실패 시 FALLBACK_STOCKS 사용
 */
@Service
@RequiredArgsConstructor
public class StockSearchService {

    private final StockRepository stockRepository;

    @Value("${python.path:python}")
    private String pythonPath;

    /** DB 실패 시 최소 검색용 Fallback (삭제 금지) */
    private static final List<Map<String, String>> FALLBACK_STOCKS = List.of(
            Map.of("code","005930","name","삼성전자",   "fullName","삼성전자",   "market","KOSPI"),
            Map.of("code","000660","name","SK하이닉스", "fullName","SK하이닉스", "market","KOSPI"),
            Map.of("code","005380","name","현대차",     "fullName","현대자동차", "market","KOSPI"),
            Map.of("code","000270","name","기아",       "fullName","기아",       "market","KOSPI"),
            Map.of("code","051910","name","LG화학",     "fullName","LG화학",     "market","KOSPI"),
            Map.of("code","006400","name","삼성SDI",    "fullName","삼성SDI",    "market","KOSPI"),
            Map.of("code","035420","name","NAVER",      "fullName","NAVER",      "market","KOSPI"),
            Map.of("code","035720","name","카카오",     "fullName","카카오",     "market","KOSPI")
    );

    /**
     * 종목명/코드 키워드 검색
     * - DB 비어있으면 자동 갱신
     * - 결과 없으면 FALLBACK_STOCKS로 대체
     */
    public List<Map<String, String>> searchStock(String keyword) {
        try {
            if (stockRepository.count() == 0) {
                System.out.println("DB 종목 없음 → KRX 로드 중...");
                refreshStockListToDB();
            }
            if (keyword == null || keyword.isBlank()) return List.of();

            List<Stock> result = stockRepository.findByKeyword(
                    keyword,
                    org.springframework.data.domain.PageRequest.of(0, 10)
            );

            if (result.isEmpty()) {
                return fallbackSearch(keyword);
            }
            return result.stream().map(Stock::toMap).collect(Collectors.toList());

        } catch (Exception e) {
            System.out.println("종목 검색 오류: " + e.getMessage());
            return fallbackSearch(keyword);
        }
    }

    /**
     * Python(FinanceDataReader)으로 전체 종목 가져와 DB 저장
     * - 중복 종목코드 제거 (ORA-00001 방지)
     */
    @Transactional
    public void refreshStockListToDB() {
        System.out.println("=== 종목 목록 갱신 시작 ===");
        try {
            String workingDir = System.getProperty("user.dir");
            String scriptPath = workingDir + "/python/fetch_stocks.py";

            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath);
            pb.redirectErrorStream(false);
            pb.directory(new java.io.File(workingDir));
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes(), "UTF-8").trim();
            String errors = new String(process.getErrorStream().readAllBytes(), "UTF-8").trim();
            process.waitFor();

            if (!errors.isBlank()) System.out.println("Python 오류: " + errors);
            if (output.isBlank()) {
                System.out.println("Python 출력 없음 → Fallback 저장");
                saveFallbackToDB();
                return;
            }

            int jsonStart = output.indexOf('[');
            if (jsonStart > 0) output = output.substring(jsonStart);

            List<Map<String, String>> stocks = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(output, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});

            // 중복 제거
            LinkedHashMap<String, Map<String, String>> dedupMap = new LinkedHashMap<>();
            for (Map<String, String> m : stocks) {
                String code = m.get("code");
                if (code != null && !code.isBlank()) dedupMap.put(code, m);
            }
            List<Map<String, String>> deduped = new ArrayList<>(dedupMap.values());
            System.out.println("=== 중복 제거 후: " + deduped.size() + "개 ===");

            stockRepository.deleteAll();
            stockRepository.flush();

            List<Stock> entities = deduped.stream().map(m -> {
                Stock s = new Stock();
                s.setStockCode(m.get("code"));
                s.setStockName(m.get("name"));
                s.setFullName(m.get("fullName"));
                s.setMarket(m.get("market"));
                return s;
            }).collect(Collectors.toList());

            stockRepository.saveAll(entities);
            System.out.println("=== DB 저장 완료: " + entities.size() + "개 ===");

        } catch (Exception e) {
            System.out.println("종목 갱신 오류: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Fallback 종목으로 키워드 검색
      * DB 조회가 실패했을 때 기본 종목 목록으로 검색 결과를 대체 */
    private List<Map<String, String>> fallbackSearch(String keyword) {
        return FALLBACK_STOCKS.stream()
                .filter(s -> s.get("name").contains(keyword) || s.get("code").contains(keyword))
                .limit(10)
                .collect(Collectors.toList());
    }

    /** FALLBACK_STOCKS를 DB에 저장
      * 기본 종목 목록을 DB에 저장해 최소 검색 기능을 유지*/
    private void saveFallbackToDB() {
        stockRepository.deleteAll();
        List<Stock> entities = FALLBACK_STOCKS.stream().map(m -> {
            Stock s = new Stock();
            s.setStockCode(m.get("code"));
            s.setStockName(m.get("name"));
            s.setFullName(m.get("fullName"));
            s.setMarket(m.get("market"));
            return s;
        }).collect(Collectors.toList());
        stockRepository.saveAll(entities);
        System.out.println("Fallback " + entities.size() + "개 저장 완료");
    }
}
