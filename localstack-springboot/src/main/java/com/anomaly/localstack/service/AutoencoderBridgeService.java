package com.anomaly.localstack.service;

import com.anomaly.localstack.config.AppProperties;
import com.anomaly.localstack.platform.aws.config.LocalStackAwsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

@Service
public class AutoencoderBridgeService {

    private static final Logger logger = LoggerFactory.getLogger(AutoencoderBridgeService.class);

    private final AppProperties appProperties;
    private final LocalStackAwsProperties awsProperties;

    public AutoencoderBridgeService(AppProperties appProperties,
                                    LocalStackAwsProperties awsProperties) {
        this.appProperties = appProperties;
        this.awsProperties = awsProperties;
    }

    public void runPythonInference() {
        File script = new File(appProperties.getPythonScriptPath());
        if (!script.exists()) {
            logger.warn("Python inference script not found at {}", script.getPath());
            return;
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                appProperties.getPythonExecutable(),
                script.getPath()
        );
        processBuilder.redirectErrorStream(true);

        Map<String, String> env = new HashMap<>(processBuilder.environment());
        env.put("AWS_REGION", awsProperties.getRegion());
        env.put("AWS_ACCESS_KEY_ID", awsProperties.getAccessKey());
        env.put("AWS_SECRET_ACCESS_KEY", awsProperties.getSecretKey());
        env.put("AWS_ENDPOINT_URL", awsProperties.getEndpoint());
        env.put("METRICS_TABLE", appProperties.getMetricsTable());
        env.put("ANOMALY_TABLE", appProperties.getAnomalyTable());
        env.put("MODEL_DIR", appProperties.getModelDir());
        env.put("LOOKBACK_HOURS", String.valueOf(appProperties.getLookbackHours()));
        env.put("ALPHA", String.valueOf(appProperties.getAlpha()));
        env.put("BETA", String.valueOf(appProperties.getBeta()));

        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[PY-AE] {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("Python autoencoder inference exited with code {}", exitCode);
            }
        } catch (Exception exception) {
            logger.error("Failed to execute python autoencoder bridge: {}", exception.getMessage());
        }
    }
}
