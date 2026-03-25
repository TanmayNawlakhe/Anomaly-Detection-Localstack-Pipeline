package com.anomaly.localstack.controller;

import com.anomaly.localstack.service.SimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/simulation")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        simulationService.start();
        return ResponseEntity.ok(Map.of("simulation", "RUNNING"));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        simulationService.stop();
        return ResponseEntity.ok(Map.of("simulation", "STOPPED"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "enabled", simulationService.isEnabled(),
                "seed_clients", simulationService.getClientCount(),
                "simulated_time", simulationService.getSimulatedCursor().toString(),
                "current_bucket_start", simulationService.getCurrentBucketStart().toString(),
                "next_bucket_start", simulationService.getNextBucketStart().toString(),
                "bucket_seconds", simulationService.getAggregationBucketSeconds()
        ));
    }
}
