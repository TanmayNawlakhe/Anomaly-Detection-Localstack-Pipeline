package com.anomaly.localstack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.anomaly")
public class AppProperties {

    private double alpha = 100.0;
    private double beta = 0.1;
    private double sigmaMultiplier = 3.0;
    private int lookbackHours = 24;
    private int minPoints = 12;
    private String aggregationInterval = "PT1H";
    private String metricsTable = "service_metrics_aggregated";
    private String anomalyTable = "anomaly_results";
    private String alertTopicName = "anomaly-alerts-topic";
    private String alertQueueName = "anomaly-alerts-queue";
    private String adminAlertEmail = "admin@example.com";
    private boolean alertConsumerEnabled = true;
    private int alertConsumerFixedDelayMs = 5000;
    private String pipelineMode = "hybrid";
    private String pythonExecutable = "python";
    private String pythonScriptPath = "python/autoencoder_inference_localstack.py";
    private String modelDir = "../models";
    private int simulationBatchSize = 8;
    private int simulationFixedDelayMs = 5000;
    private String simulationAnomalySeedFile = "../output/anomalies.json";
    private boolean simulationEnabledByDefault = true;
    private int detectionStrideBuckets = 24;

    public double getAlpha() {
        return alpha;
    }

    public void setAlpha(double alpha) {
        this.alpha = alpha;
    }

    public double getBeta() {
        return beta;
    }

    public void setBeta(double beta) {
        this.beta = beta;
    }

    public double getSigmaMultiplier() {
        return sigmaMultiplier;
    }

    public void setSigmaMultiplier(double sigmaMultiplier) {
        this.sigmaMultiplier = sigmaMultiplier;
    }

    public int getLookbackHours() {
        return lookbackHours;
    }

    public void setLookbackHours(int lookbackHours) {
        this.lookbackHours = lookbackHours;
    }

    public int getMinPoints() {
        return minPoints;
    }

    public void setMinPoints(int minPoints) {
        this.minPoints = minPoints;
    }

    public String getAggregationInterval() {
        return aggregationInterval;
    }

    public void setAggregationInterval(String aggregationInterval) {
        this.aggregationInterval = aggregationInterval;
    }

    public String getMetricsTable() {
        return metricsTable;
    }

    public void setMetricsTable(String metricsTable) {
        this.metricsTable = metricsTable;
    }

    public String getAnomalyTable() {
        return anomalyTable;
    }

    public void setAnomalyTable(String anomalyTable) {
        this.anomalyTable = anomalyTable;
    }

    public String getAlertTopicName() {
        return alertTopicName;
    }

    public void setAlertTopicName(String alertTopicName) {
        this.alertTopicName = alertTopicName;
    }

    public String getAlertQueueName() {
        return alertQueueName;
    }

    public void setAlertQueueName(String alertQueueName) {
        this.alertQueueName = alertQueueName;
    }

    public String getAdminAlertEmail() {
        return adminAlertEmail;
    }

    public void setAdminAlertEmail(String adminAlertEmail) {
        this.adminAlertEmail = adminAlertEmail;
    }

    public boolean isAlertConsumerEnabled() {
        return alertConsumerEnabled;
    }

    public void setAlertConsumerEnabled(boolean alertConsumerEnabled) {
        this.alertConsumerEnabled = alertConsumerEnabled;
    }

    public int getAlertConsumerFixedDelayMs() {
        return alertConsumerFixedDelayMs;
    }

    public void setAlertConsumerFixedDelayMs(int alertConsumerFixedDelayMs) {
        this.alertConsumerFixedDelayMs = alertConsumerFixedDelayMs;
    }

    public String getPipelineMode() {
        return pipelineMode;
    }

    public void setPipelineMode(String pipelineMode) {
        this.pipelineMode = pipelineMode;
    }

    public String getPythonExecutable() {
        return pythonExecutable;
    }

    public void setPythonExecutable(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    public String getPythonScriptPath() {
        return pythonScriptPath;
    }

    public void setPythonScriptPath(String pythonScriptPath) {
        this.pythonScriptPath = pythonScriptPath;
    }

    public String getModelDir() {
        return modelDir;
    }

    public void setModelDir(String modelDir) {
        this.modelDir = modelDir;
    }

    public int getSimulationBatchSize() {
        return simulationBatchSize;
    }

    public void setSimulationBatchSize(int simulationBatchSize) {
        this.simulationBatchSize = simulationBatchSize;
    }

    public int getSimulationFixedDelayMs() {
        return simulationFixedDelayMs;
    }

    public void setSimulationFixedDelayMs(int simulationFixedDelayMs) {
        this.simulationFixedDelayMs = simulationFixedDelayMs;
    }

    public String getSimulationAnomalySeedFile() {
        return simulationAnomalySeedFile;
    }

    public void setSimulationAnomalySeedFile(String simulationAnomalySeedFile) {
        this.simulationAnomalySeedFile = simulationAnomalySeedFile;
    }

    public boolean isSimulationEnabledByDefault() {
        return simulationEnabledByDefault;
    }

    public void setSimulationEnabledByDefault(boolean simulationEnabledByDefault) {
        this.simulationEnabledByDefault = simulationEnabledByDefault;
    }

    public int getDetectionStrideBuckets() {
        return detectionStrideBuckets;
    }

    public void setDetectionStrideBuckets(int detectionStrideBuckets) {
        this.detectionStrideBuckets = detectionStrideBuckets;
    }
}
