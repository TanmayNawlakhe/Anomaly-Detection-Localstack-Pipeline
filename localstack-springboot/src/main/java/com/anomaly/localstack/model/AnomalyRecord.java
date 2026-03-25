package com.anomaly.localstack.model;

import java.time.Instant;

public record AnomalyRecord(
        String clientId,
        Instant timeblock,
        String anomalyType,
        String severity,
        double score,
        double threshold
) {
}
