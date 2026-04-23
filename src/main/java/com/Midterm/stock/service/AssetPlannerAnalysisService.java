package com.Midterm.stock.service;

import com.Midterm.stock.dto.AssetPlannerAnalysisDto;
import com.Midterm.stock.dto.AssetPlannerPredictionRequestDto;
import com.Midterm.stock.dto.AssetPlannerPredictionResponseDto;
import com.Midterm.stock.repository.AssetDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@Service
public class AssetPlannerAnalysisService {

    @Autowired
    private AssetDao assetDao;

    private final String predictionApiUrl;
    private final String predictionApiScript;
    private final String pythonPath;
    private final int predictionApiStartupTimeoutSeconds;
    private final Path assetApiLogPath = Path.of(System.getProperty("java.io.tmpdir"), "stoxle-asset-api.log");

    private volatile Process assetApiProcess;

    public AssetPlannerAnalysisService(
            @Value("${asset.prediction.api-url:http://127.0.0.1:9000/api/asset/predict}") String predictionApiUrl,
            @Value("${asset.prediction.script:${user.dir}/python/ml/asset_api.py}") String predictionApiScript,
            @Value("${python.path:python}") String pythonPath,
            @Value("${asset.prediction.startup-timeout:12}") int predictionApiStartupTimeoutSeconds
    ) {
        this.predictionApiUrl = predictionApiUrl;
        this.predictionApiScript = predictionApiScript;
        this.pythonPath = pythonPath;
        this.predictionApiStartupTimeoutSeconds = predictionApiStartupTimeoutSeconds;
    }

    public AssetPlannerAnalysisDto analyzeAndSave(AssetPlannerAnalysisDto formDto, int loginNum) throws Exception {
        AssetPlannerPredictionRequestDto requestDto = new AssetPlannerPredictionRequestDto();
        requestDto.setCurrent_asset(formDto.getCurrentAsset());
        requestDto.setMonthly_income(formDto.getMonthlyIncome());
        requestDto.setMonthly_expense(formDto.getMonthlyExpense());
        requestDto.setGoal_amount(formDto.getGoalAmount());
        requestDto.setGoal_months(formDto.getGoalMonths());
        requestDto.setExpected_return(formDto.getExpectedReturn());
        requestDto.setAge(formDto.getAge());
        requestDto.setJob_type(formDto.getJobType());
        requestDto.setRisk_preference(formDto.getRiskPreference());

        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<AssetPlannerPredictionRequestDto> entity =
                new HttpEntity<>(requestDto, headers);

        ResponseEntity<AssetPlannerPredictionResponseDto> response;
        try {
            ensurePredictionApiReady(restTemplate);
            response = postPrediction(restTemplate, entity);
        } catch (RestClientException e) {
            throw new RuntimeException(
                    "자산 예측 API 호출에 실패했습니다. FastAPI 서버 상태와 주소를 확인해주세요: " + predictionApiUrl,
                    e
            );
        }

        AssetPlannerPredictionResponseDto responseDto = response.getBody();

        if (responseDto == null) {
            throw new RuntimeException("FastAPI 응답이 비어있음");
        }

        AssetPlannerAnalysisDto resultDto = new AssetPlannerAnalysisDto();
        resultDto.setCurrentAsset(formDto.getCurrentAsset());
        resultDto.setMonthlyIncome(formDto.getMonthlyIncome());
        resultDto.setMonthlyExpense(formDto.getMonthlyExpense());
        resultDto.setGoalAmount(formDto.getGoalAmount());
        resultDto.setGoalMonths(formDto.getGoalMonths());
        resultDto.setExpectedReturn(formDto.getExpectedReturn());
        resultDto.setAge(formDto.getAge());
        resultDto.setJobType(formDto.getJobType());
        resultDto.setRiskPreference(formDto.getRiskPreference());

        long monthlyCashflow = formDto.getMonthlyIncome() - formDto.getMonthlyExpense();
        resultDto.setMonthlySaving(monthlyCashflow);

        if (responseDto.getInput_summary() != null) {
            resultDto.setMonthlySaving(responseDto.getInput_summary().getMonthly_cashflow());
        }

        resultDto.setPrediction(responseDto.getPrediction());
        resultDto.setPredictionLabel(responseDto.getPrediction_label());
        resultDto.setToneTitle(responseDto.getTone_title());
        resultDto.setModelPrediction(responseDto.getModel_prediction());
        resultDto.setModelPredictionLabel(responseDto.getModel_prediction_label());
        resultDto.setModelProbability(responseDto.getModel_probability());

        if (responseDto.getAnalysis() != null) {
            resultDto.setRequiredMonthlySaving(responseDto.getAnalysis().getRequired_monthly_cashflow());
            resultDto.setEstimatedFinalAsset(responseDto.getAnalysis().getEstimated_final_asset());
            resultDto.setGoalGap(responseDto.getAnalysis().getGoal_gap());
            resultDto.setMessage(responseDto.getAnalysis().getMessage());
        } else {
            resultDto.setRequiredMonthlySaving(0);
            resultDto.setEstimatedFinalAsset(0);
            resultDto.setGoalGap(0);
            resultDto.setMessage("분석 결과를 받아오지 못했습니다.");
        }

        assetDao.insertAnalysisHistory(resultDto, loginNum);
        return resultDto;
    }

    public ArrayList<AssetPlannerAnalysisDto> getHistory(int loginNum) {
        return assetDao.getAnalysisHistory(loginNum);
    }

    private ResponseEntity<AssetPlannerPredictionResponseDto> postPrediction(
            RestTemplate restTemplate,
            HttpEntity<AssetPlannerPredictionRequestDto> entity
    ) {
        try {
            return restTemplate.postForEntity(
                    predictionApiUrl,
                    entity,
                    AssetPlannerPredictionResponseDto.class
            );
        } catch (ResourceAccessException e) {
            if (!isLocalPredictionApi()) {
                throw e;
            }

            ensurePredictionApiReady(restTemplate);
            return restTemplate.postForEntity(
                    predictionApiUrl,
                    entity,
                    AssetPlannerPredictionResponseDto.class
            );
        }
    }

    private void ensurePredictionApiReady(RestTemplate restTemplate) {
        if (isPredictionApiAvailable(restTemplate) || !isLocalPredictionApi()) {
            return;
        }

        startPredictionApiProcessIfNeeded();
        waitForPredictionApi(restTemplate);
    }

    private boolean isPredictionApiAvailable(RestTemplate restTemplate) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(resolveHealthUrl(), String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            return false;
        }
    }

    private synchronized void startPredictionApiProcessIfNeeded() {
        if (assetApiProcess != null && assetApiProcess.isAlive()) {
            return;
        }

        Path scriptPath = Path.of(predictionApiScript);
        if (!Files.exists(scriptPath)) {
            throw new RuntimeException("자산 예측 스크립트를 찾을 수 없습니다: " + predictionApiScript);
        }

        try {
            Files.createDirectories(assetApiLogPath.getParent());

            ProcessBuilder processBuilder = new ProcessBuilder(
                    resolvePythonExecutable(),
                    scriptPath.toString()
            );
            processBuilder.directory(scriptPath.getParent().toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(assetApiLogPath.toFile()));
            processBuilder.environment().put("PYTHONIOENCODING", "UTF-8");
            processBuilder.environment().put("PYTHONUTF8", "1");

            URI uri = URI.create(predictionApiUrl);
            if (uri.getHost() != null) {
                processBuilder.environment().put("ASSET_API_HOST", uri.getHost());
            }
            if (uri.getPort() > 0) {
                processBuilder.environment().put("ASSET_API_PORT", String.valueOf(uri.getPort()));
            }

            assetApiProcess = processBuilder.start();
        } catch (IOException e) {
            throw new RuntimeException("자산 예측 FastAPI 서버를 시작하지 못했습니다. 로그: " + assetApiLogPath, e);
        }
    }

    private void waitForPredictionApi(RestTemplate restTemplate) {
        long timeoutMillis = Math.max(3, predictionApiStartupTimeoutSeconds) * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (System.currentTimeMillis() < deadline) {
            if (isPredictionApiAvailable(restTemplate)) {
                return;
            }

            if (assetApiProcess != null && !assetApiProcess.isAlive()) {
                throw new RuntimeException(
                        "자산 예측 FastAPI 서버가 시작 직후 종료되었습니다. 로그를 확인해주세요: " + assetApiLogPath
                );
            }

            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("자산 예측 FastAPI 서버 시작 대기를 중단했습니다.", e);
            }
        }

        throw new RuntimeException("자산 예측 FastAPI 서버가 시작되지 않았습니다. 로그: " + assetApiLogPath);
    }

    private String resolveHealthUrl() {
        String suffix = "/api/asset/predict";
        if (predictionApiUrl.endsWith(suffix)) {
            return predictionApiUrl.substring(0, predictionApiUrl.length() - suffix.length()) + "/api/health";
        }
        return predictionApiUrl;
    }

    private boolean isLocalPredictionApi() {
        try {
            URI uri = URI.create(predictionApiUrl);
            String host = uri.getHost();
            return host == null || "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        } catch (Exception e) {
            return false;
        }
    }

    private String resolvePythonExecutable() {
        if (!isBlank(pythonPath) && !isGenericPythonCommand(pythonPath)) {
            return pythonPath;
        }

        String projectRoot = System.getProperty("user.dir");
        String[] candidates = {
                Path.of(projectRoot, ".venv", "Scripts", "python.exe").toString(),
                Path.of(projectRoot, "python", ".venv", "Scripts", "python.exe").toString(),
                Path.of(projectRoot, ".venv", "bin", "python").toString(),
                Path.of(projectRoot, "python", ".venv", "bin", "python").toString()
        };

        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }

        return isBlank(pythonPath) ? "python" : pythonPath;
    }

    private boolean isGenericPythonCommand(String value) {
        if (isBlank(value)) {
            return true;
        }

        String normalized = value.trim().replace('\\', '/').toLowerCase();
        return normalized.equals("python")
                || normalized.equals("python.exe")
                || normalized.equals("py")
                || normalized.equals("py.exe");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
