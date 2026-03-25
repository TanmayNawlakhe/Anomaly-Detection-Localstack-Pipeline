package com.anomaly.localstack.service;

import com.anomaly.localstack.config.AppProperties;
import com.anomaly.localstack.model.AggregatedMetric;
import com.anomaly.localstack.model.AnomalyRecord;
import com.anomaly.localstack.platform.aws.repository.DynamoRepository;
import com.anomaly.localstack.platform.aws.runtime.AwsResourceState;
import com.anomaly.localstack.service.alert.AlertPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DetectionService {

    private final DynamoRepository dynamoRepository;
    private final AlertPublisher alertPublisher;
    private final AwsResourceState resourceState;
    private final AppProperties appProperties;
    private final AutoencoderBridgeService autoencoderBridgeService;
    private final Duration aggregationDuration;
    private final AtomicReference<Instant> lastProcessedBucket;
    private final AtomicReference<Instant> latestObservedBucket;

    public DetectionService(DynamoRepository dynamoRepository,
                            AlertPublisher alertPublisher,
                            AwsResourceState resourceState,
                            AppProperties appProperties,
                            AutoencoderBridgeService autoencoderBridgeService) {
        this.dynamoRepository = dynamoRepository;
        this.alertPublisher = alertPublisher;
        this.resourceState = resourceState;
        this.appProperties = appProperties;
        this.autoencoderBridgeService = autoencoderBridgeService;
        this.aggregationDuration = Duration.parse(appProperties.getAggregationInterval());
        this.lastProcessedBucket = new AtomicReference<>();
        this.latestObservedBucket = new AtomicReference<>();
    }

    @Scheduled(fixedDelayString = "${app.scheduler.fixed-delay-ms:30000}")
    public void runDetection() {
        if (!resourceState.isResourcesReady()) {
            return;
        }

        String mode = appProperties.getPipelineMode();

        if ("python-autoencoder".equalsIgnoreCase(mode)) {
            autoencoderBridgeService.runPythonInference();
            return;
        }

        if ("hybrid".equalsIgnoreCase(mode)) {
            autoencoderBridgeService.runPythonInference();
        }

        Optional<Instant> latestBucketOptional = dynamoRepository.getLatestMetricTimeblock();
        if (latestBucketOptional.isEmpty()) {
            return;
        }

        Instant closedBucketEnd = latestBucketOptional.get();
        latestObservedBucket.set(closedBucketEnd);

        Instant previousBucket = lastProcessedBucket.get();
        int detectionStrideBuckets = Math.max(1, appProperties.getDetectionStrideBuckets());
        if (previousBucket != null) {
            Instant nextEligible = previousBucket.plus(aggregationDuration.multipliedBy(detectionStrideBuckets));
            if (closedBucketEnd.isBefore(nextEligible)) {
                return;
            }
        }

        if (previousBucket != null && !closedBucketEnd.isAfter(previousBucket)) {
            return;
        }

        long aggregationSeconds = Math.max(1L, aggregationDuration.getSeconds());
        long lookbackSeconds = appProperties.getLookbackHours() * 3600L;
        long lookbackBuckets = Math.max(1L, (lookbackSeconds + aggregationSeconds - 1) / aggregationSeconds);

        Instant start = closedBucketEnd.minus(aggregationDuration.multipliedBy(Math.max(0L, lookbackBuckets - 1L)));
        Instant end = closedBucketEnd;

        List<String> clients = dynamoRepository.listClients();
        for (String client : clients) {
            List<AggregatedMetric> data = dynamoRepository.getRecentMetrics(client, start, end);
            if (data.size() < appProperties.getMinPoints()) {
                continue;
            }
            data.sort(Comparator.comparing(AggregatedMetric::timeblock));
            detectAndStore(client, data);
        }

        lastProcessedBucket.set(closedBucketEnd);
    }

    public Instant getLastProcessedBucket() {
        return lastProcessedBucket.get();
    }

    public Instant getLatestObservedBucket() {
        return latestObservedBucket.get();
    }

    public int getBucketsUntilNextRun() {
        Instant latest = latestObservedBucket.get();
        if (latest == null) {
            return 0;
        }

        Instant last = lastProcessedBucket.get();
        if (last == null) {
            return 0;
        }

        int stride = Math.max(1, appProperties.getDetectionStrideBuckets());
        long deltaSeconds = Math.max(0L, latest.getEpochSecond() - last.getEpochSecond());
        long bucketSeconds = Math.max(1L, aggregationDuration.getSeconds());
        long progressedBuckets = deltaSeconds / bucketSeconds;
        long remaining = Math.max(0L, stride - progressedBuckets);
        return (int) remaining;
    }

    public void resetDetectionState() {
        lastProcessedBucket.set(null);
        latestObservedBucket.set(null);
    }

    private void detectAndStore(String clientId, List<AggregatedMetric> data) {
        List<Double> signal = new ArrayList<>();
        for (AggregatedMetric metric : data) {
            double errorRate = metric.requestCount() == 0 ? 0.0 : (double) metric.errorCount() / metric.requestCount();
            signal.add(metric.requestCount() + appProperties.getAlpha() * errorRate + appProperties.getBeta() * metric.avgLatencyMs());
        }

        double mean = signal.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = signal.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average()
                .orElse(0.0);
        double std = Math.sqrt(variance);
        double sigmaThreshold = mean + appProperties.getSigmaMultiplier() * std;
        double percentileThreshold = percentile(signal, 97.5);
        double threshold = Math.min(sigmaThreshold, percentileThreshold);

        for (int index = 0; index < signal.size(); index++) {
            double score = signal.get(index);
            if (score > threshold) {
                AggregatedMetric metric = data.get(index);
                String severity = score > threshold * 1.5 ? "HIGH" : "MEDIUM";
                AnomalyRecord anomaly = new AnomalyRecord(
                        clientId,
                        metric.timeblock(),
                        "USAGE_PATTERN_DEVIATION",
                        severity,
                        score,
                        threshold
                );
                dynamoRepository.putAnomaly(anomaly);
                alertPublisher.publish(anomaly);
            }
        }
    }

    private double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }

        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        double rank = (percentile / 100.0) * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);

        if (low == high) {
            return sorted.get(low);
        }

        double weight = rank - low;
        return sorted.get(low) * (1.0 - weight) + sorted.get(high) * weight;
    }
}
