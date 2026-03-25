package com.anomaly.localstack.controller;

import com.anomaly.localstack.model.AggregatedMetric;
import com.anomaly.localstack.model.RawMetricsRequest;
import com.anomaly.localstack.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final IngestionService ingestionService;

    public MetricsController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody RawMetricsRequest request) {
        List<AggregatedMetric> aggregated = ingestionService.aggregateAndStore(request.getMetrics());
        return ResponseEntity.ok(Map.of(
                "status", "INGESTED",
                "aggregated_records", aggregated.size()
        ));
    }
}
