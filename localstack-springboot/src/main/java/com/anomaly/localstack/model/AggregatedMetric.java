package com.anomaly.localstack.model;

import java.time.Instant;

public record AggregatedMetric(
        String clientId,
        Instant timeblock,
        long requestCount,
        long errorCount,
        double avgLatencyMs
) {
}
