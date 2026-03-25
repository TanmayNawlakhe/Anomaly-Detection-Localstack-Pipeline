package com.anomaly.localstack.service;

import com.anomaly.localstack.config.AppProperties;
import com.anomaly.localstack.model.AggregatedMetric;
import com.anomaly.localstack.model.RawMetric;
import com.anomaly.localstack.platform.aws.repository.DynamoRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    private final DynamoRepository dynamoRepository;
    private final Duration aggregationDuration;

    public IngestionService(DynamoRepository dynamoRepository, AppProperties appProperties) {
        this.dynamoRepository = dynamoRepository;
        this.aggregationDuration = Duration.parse(appProperties.getAggregationInterval());
    }

    public List<AggregatedMetric> aggregateAndStore(List<RawMetric> rawMetrics) {
        Map<String, BucketAccumulator> grouped = new HashMap<>();

        for (RawMetric metric : rawMetrics) {
            Instant bucket = floor(metric.getTimestamp(), aggregationDuration);
            String key = metric.getClientId() + "|" + bucket;
            grouped.computeIfAbsent(key, value -> new BucketAccumulator(metric.getClientId(), bucket))
                    .add(metric.getRequestCount(), metric.getErrorCount(), metric.getAvgLatencyMs());
        }

        List<AggregatedMetric> output = new ArrayList<>();
        for (BucketAccumulator accumulator : grouped.values()) {
            AggregatedMetric aggregatedMetric = accumulator.toMetric();
            dynamoRepository.upsertMetric(aggregatedMetric);
            output.add(aggregatedMetric);
        }
        return output;
    }

    private Instant floor(Instant instant, Duration duration) {
        long epochSeconds = instant.getEpochSecond();
        long bucketSeconds = duration.getSeconds();
        long floored = (epochSeconds / bucketSeconds) * bucketSeconds;
        return Instant.ofEpochSecond(floored);
    }

    private static class BucketAccumulator {
        private final String clientId;
        private final Instant timeblock;
        private long requestCount;
        private long errorCount;
        private double totalLatency;
        private long samples;

        private BucketAccumulator(String clientId, Instant timeblock) {
            this.clientId = clientId;
            this.timeblock = timeblock;
        }

        private void add(long requestCount, long errorCount, double avgLatencyMs) {
            this.requestCount += requestCount;
            this.errorCount += errorCount;
            this.totalLatency += avgLatencyMs;
            this.samples += 1;
        }

        private AggregatedMetric toMetric() {
            double averageLatency = samples == 0 ? 0.0 : totalLatency / samples;
            return new AggregatedMetric(clientId, timeblock, requestCount, errorCount, averageLatency);
        }
    }
}
