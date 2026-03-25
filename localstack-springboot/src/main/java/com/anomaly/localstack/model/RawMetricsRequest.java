package com.anomaly.localstack.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class RawMetricsRequest {

    @Valid
    @NotEmpty
    private List<RawMetric> metrics;

    public List<RawMetric> getMetrics() {
        return metrics;
    }

    public void setMetrics(List<RawMetric> metrics) {
        this.metrics = metrics;
    }
}
