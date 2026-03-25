package com.anomaly.localstack.controller;

import com.anomaly.localstack.config.AppProperties;
import com.anomaly.localstack.platform.aws.alert.NotificationFeedService;
import com.anomaly.localstack.platform.aws.repository.DynamoRepository;
import com.anomaly.localstack.service.DetectionService;
import com.anomaly.localstack.service.SimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DynamoRepository dynamoRepository;
    private final SimulationService simulationService;
    private final DetectionService detectionService;
    private final NotificationFeedService notificationFeedService;
    private final AppProperties appProperties;

    public DashboardController(DynamoRepository dynamoRepository,
                               SimulationService simulationService,
                               DetectionService detectionService,
                               NotificationFeedService notificationFeedService,
                               AppProperties appProperties) {
        this.dynamoRepository = dynamoRepository;
        this.simulationService = simulationService;
        this.detectionService = detectionService;
        this.notificationFeedService = notificationFeedService;
        this.appProperties = appProperties;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(Map.ofEntries(
                Map.entry("pipeline_mode", appProperties.getPipelineMode()),
                Map.entry("simulation_enabled", simulationService.isEnabled()),
                Map.entry("detection_stride_buckets", appProperties.getDetectionStrideBuckets()),
                Map.entry("buckets_until_next_detection", detectionService.getBucketsUntilNextRun()),
                Map.entry("last_detection_bucket", String.valueOf(detectionService.getLastProcessedBucket())),
                Map.entry("latest_observed_bucket", String.valueOf(detectionService.getLatestObservedBucket())),
                Map.entry("latest_metrics", dynamoRepository.getLatestMetrics(25)),
                Map.entry("latest_metrics_raw", dynamoRepository.getLatestMetricItemsRaw(25)),
                Map.entry("latest_anomalies", dynamoRepository.getLatestAnomalies(25)),
                Map.entry("total_metric_entries", dynamoRepository.getMetricsCount()),
                Map.entry("total_anomaly_entries", dynamoRepository.getAnomaliesCount()),
                Map.entry("anomalies_by_severity", dynamoRepository.getAnomalySeverityCounts()),
                Map.entry("anomalies_by_type", dynamoRepository.getAnomalyTypeCounts()),
                Map.entry("simulated_notifications", notificationFeedService.latest(25))
        ));
    }

    @GetMapping("/anomaly-detail")
    public ResponseEntity<Map<String, Object>> anomalyDetail(@RequestParam String clientId,
                                                             @RequestParam String timeblock) {
        Instant parsedTimeblock;
        try {
            parsedTimeblock = Instant.parse(timeblock);
        } catch (Exception ignored) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid timeblock format. Expected ISO-8601 UTC instant."
            ));
        }

        return ResponseEntity.ok(Map.of(
                "client_id", clientId,
                "timeblock", parsedTimeblock.toString(),
                "matching_aggregated_metric", dynamoRepository.getMetricItemRaw(clientId, parsedTimeblock).orElse(null)
        ));
    }
}
