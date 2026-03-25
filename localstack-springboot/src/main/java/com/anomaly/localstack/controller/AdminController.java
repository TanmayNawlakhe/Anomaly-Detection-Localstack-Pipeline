package com.anomaly.localstack.controller;

import com.anomaly.localstack.platform.aws.alert.NotificationFeedService;
import com.anomaly.localstack.platform.aws.repository.DynamoRepository;
import com.anomaly.localstack.service.DetectionService;
import com.anomaly.localstack.service.SimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final DynamoRepository dynamoRepository;
    private final SimulationService simulationService;
    private final DetectionService detectionService;
    private final NotificationFeedService notificationFeedService;

    public AdminController(DynamoRepository dynamoRepository,
                           SimulationService simulationService,
                           DetectionService detectionService,
                           NotificationFeedService notificationFeedService) {
        this.dynamoRepository = dynamoRepository;
        this.simulationService = simulationService;
        this.detectionService = detectionService;
        this.notificationFeedService = notificationFeedService;
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetData() {
        simulationService.stop();
        int deletedMetrics = dynamoRepository.purgeMetricsTable();
        int deletedAnomalies = dynamoRepository.purgeAnomalyTable();
        simulationService.resetSimulationClock();
        detectionService.resetDetectionState();
        notificationFeedService.clear();

        return ResponseEntity.ok(Map.of(
                "status", "RESET_DONE",
                "deleted_metrics", deletedMetrics,
                "deleted_anomalies", deletedAnomalies,
                "simulation_enabled", simulationService.isEnabled(),
                "simulated_time", simulationService.getSimulatedCursor().toString()
        ));
    }
}
