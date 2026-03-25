package com.anomaly.localstack.platform.aws.repository;

import com.anomaly.localstack.config.AppProperties;
import com.anomaly.localstack.model.AggregatedMetric;
import com.anomaly.localstack.model.AnomalyRecord;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class DynamoRepository {

    private final DynamoDbClient dynamoDbClient;
    private final AppProperties appProperties;

    public DynamoRepository(DynamoDbClient dynamoDbClient, AppProperties appProperties) {
        this.dynamoDbClient = dynamoDbClient;
        this.appProperties = appProperties;
    }

    public void upsertMetric(AggregatedMetric metric) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("client_id", AttributeValue.fromS(metric.clientId()));
        item.put("timeblock", AttributeValue.fromS(metric.timeblock().toString()));
        item.put("request_count", AttributeValue.fromN(String.valueOf(metric.requestCount())));
        item.put("error_count", AttributeValue.fromN(String.valueOf(metric.errorCount())));
        item.put("avg_latency_ms", AttributeValue.fromN(String.valueOf(metric.avgLatencyMs())));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(appProperties.getMetricsTable())
                .item(item)
                .build());
    }

    public void putAnomaly(AnomalyRecord anomalyRecord) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("client_id", AttributeValue.fromS(anomalyRecord.clientId()));
        item.put("timeblock", AttributeValue.fromS(anomalyRecord.timeblock().toString()));
        item.put("anomaly_type", AttributeValue.fromS(anomalyRecord.anomalyType()));
        item.put("severity", AttributeValue.fromS(anomalyRecord.severity()));
        item.put("score", AttributeValue.fromN(String.valueOf(anomalyRecord.score())));
        item.put("threshold", AttributeValue.fromN(String.valueOf(anomalyRecord.threshold())));

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(appProperties.getAnomalyTable())
                .item(item)
                .build());
    }

    public List<String> listClients() {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(appProperties.getMetricsTable())
                .projectionExpression("client_id")
                .build();

        List<Map<String, AttributeValue>> items = dynamoDbClient.scan(scanRequest).items();
        Set<String> clients = new LinkedHashSet<>();
        for (Map<String, AttributeValue> item : items) {
            if (item.containsKey("client_id")) {
                clients.add(item.get("client_id").s());
            }
        }
        return new ArrayList<>(clients);
    }

    public List<AggregatedMetric> getRecentMetrics(String clientId, Instant fromInclusive, Instant toExclusive) {
        Map<String, String> names = new HashMap<>();
        names.put("#cid", "client_id");
        names.put("#tb", "timeblock");

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":client", AttributeValue.fromS(clientId));
        values.put(":from", AttributeValue.fromS(fromInclusive.toString()));
        values.put(":to", AttributeValue.fromS(toExclusive.toString()));

        QueryRequest request = QueryRequest.builder()
                .tableName(appProperties.getMetricsTable())
                .keyConditionExpression("#cid = :client AND #tb BETWEEN :from AND :to")
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .scanIndexForward(true)
                .build();

        List<AggregatedMetric> output = new ArrayList<>();
        for (Map<String, AttributeValue> item : dynamoDbClient.query(request).items()) {
            output.add(new AggregatedMetric(
                    item.get("client_id").s(),
                    Instant.parse(item.get("timeblock").s()),
                    Long.parseLong(item.get("request_count").n()),
                    Long.parseLong(item.get("error_count").n()),
                    Double.parseDouble(item.get("avg_latency_ms").n())
            ));
        }
        return output;
    }

    public List<AggregatedMetric> getLatestMetrics(int limit) {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(appProperties.getMetricsTable())
                .build();

        List<AggregatedMetric> output = new ArrayList<>();
        for (Map<String, AttributeValue> item : dynamoDbClient.scan(scanRequest).items()) {
            output.add(new AggregatedMetric(
                    item.get("client_id").s(),
                    Instant.parse(item.get("timeblock").s()),
                    Long.parseLong(item.getOrDefault("request_count", AttributeValue.fromN("0")).n()),
                    Long.parseLong(item.getOrDefault("error_count", AttributeValue.fromN("0")).n()),
                    Double.parseDouble(item.getOrDefault("avg_latency_ms", AttributeValue.fromN("0")).n())
            ));
        }

        output.sort(Comparator.comparing(AggregatedMetric::timeblock).reversed());
        return output.stream().limit(limit).toList();
    }

    public List<AnomalyRecord> getLatestAnomalies(int limit) {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(appProperties.getAnomalyTable())
                .build();

        List<AnomalyRecord> output = new ArrayList<>();
        for (Map<String, AttributeValue> item : dynamoDbClient.scan(scanRequest).items()) {
            output.add(new AnomalyRecord(
                    item.get("client_id").s(),
                    Instant.parse(item.get("timeblock").s()),
                    item.getOrDefault("anomaly_type", AttributeValue.fromS("UNKNOWN")).s(),
                    item.getOrDefault("severity", AttributeValue.fromS("LOW")).s(),
                    Double.parseDouble(item.getOrDefault("score", AttributeValue.fromN("0.0")).n()),
                    Double.parseDouble(item.getOrDefault("threshold", AttributeValue.fromN("0.0")).n())
            ));
        }

        output.sort(Comparator.comparing(AnomalyRecord::timeblock).reversed());
        return output.stream().limit(limit).toList();
    }

    public List<Map<String, String>> getLatestMetricItemsRaw(int limit) {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(appProperties.getMetricsTable())
                .build();

        List<Map<String, String>> output = new ArrayList<>();
        for (Map<String, AttributeValue> item : dynamoDbClient.scan(scanRequest).items()) {
            Map<String, String> mapped = new HashMap<>();
            mapped.put("client_id", item.getOrDefault("client_id", AttributeValue.fromS("")).s());
            mapped.put("timeblock", item.getOrDefault("timeblock", AttributeValue.fromS("")).s());
            mapped.put("request_count", item.getOrDefault("request_count", AttributeValue.fromN("0")).n());
            mapped.put("error_count", item.getOrDefault("error_count", AttributeValue.fromN("0")).n());
            mapped.put("avg_latency_ms", item.getOrDefault("avg_latency_ms", AttributeValue.fromN("0")).n());
            output.add(mapped);
        }

        output.sort((left, right) -> right.get("timeblock").compareTo(left.get("timeblock")));
        return output.stream().limit(limit).toList();
    }

    public Optional<Map<String, String>> getMetricItemRaw(String clientId, Instant timeblock) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("client_id", AttributeValue.fromS(clientId));
        key.put("timeblock", AttributeValue.fromS(timeblock.toString()));

        var response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(appProperties.getMetricsTable())
                .key(key)
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        Map<String, AttributeValue> item = response.item();
        Map<String, String> mapped = new HashMap<>();
        mapped.put("client_id", item.getOrDefault("client_id", AttributeValue.fromS("")).s());
        mapped.put("timeblock", item.getOrDefault("timeblock", AttributeValue.fromS("")).s());
        mapped.put("request_count", item.getOrDefault("request_count", AttributeValue.fromN("0")).n());
        mapped.put("error_count", item.getOrDefault("error_count", AttributeValue.fromN("0")).n());
        mapped.put("avg_latency_ms", item.getOrDefault("avg_latency_ms", AttributeValue.fromN("0")).n());
        return Optional.of(mapped);
    }

    public Optional<Instant> getLatestMetricTimeblock() {
        List<AggregatedMetric> latest = getLatestMetrics(1);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(latest.get(0).timeblock());
    }

    public long getMetricsCount() {
        return countItems(appProperties.getMetricsTable());
    }

    public long getAnomaliesCount() {
        return countItems(appProperties.getAnomalyTable());
    }

    public Map<String, Long> getAnomalySeverityCounts() {
        return groupAnomalyCountsByField("severity");
    }

    public Map<String, Long> getAnomalyTypeCounts() {
        return groupAnomalyCountsByField("anomaly_type");
    }

    public int purgeMetricsTable() {
        return purgeTable(appProperties.getMetricsTable());
    }

    public int purgeAnomalyTable() {
        return purgeTable(appProperties.getAnomalyTable());
    }

    private long countItems(String tableName) {
        long count = 0L;
        Map<String, AttributeValue> startKey = null;

        do {
            ScanRequest.Builder builder = ScanRequest.builder()
                    .tableName(tableName)
                    .select("COUNT");
            if (startKey != null && !startKey.isEmpty()) {
                builder.exclusiveStartKey(startKey);
            }

            var response = dynamoDbClient.scan(builder.build());
            count += response.count();
            startKey = response.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());

        return count;
    }

    private Map<String, Long> groupAnomalyCountsByField(String fieldName) {
        Map<String, Long> counts = new HashMap<>();

        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(appProperties.getAnomalyTable())
                .build();

        for (Map<String, AttributeValue> item : dynamoDbClient.scan(scanRequest).items()) {
            String fieldValue = item.getOrDefault(fieldName, AttributeValue.fromS("UNKNOWN")).s();
            counts.put(fieldValue, counts.getOrDefault(fieldValue, 0L) + 1L);
        }

        return counts;
    }

    private int purgeTable(String tableName) {
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;

        do {
            ScanRequest.Builder scanBuilder = ScanRequest.builder()
                    .tableName(tableName)
                    .projectionExpression("client_id, timeblock");
            if (startKey != null && !startKey.isEmpty()) {
                scanBuilder.exclusiveStartKey(startKey);
            }

            var scanResponse = dynamoDbClient.scan(scanBuilder.build());
            for (Map<String, AttributeValue> item : scanResponse.items()) {
                Map<String, AttributeValue> key = new HashMap<>();
                key.put("client_id", item.get("client_id"));
                key.put("timeblock", item.get("timeblock"));
                keys.add(key);
            }

            startKey = scanResponse.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());

        int deleted = 0;
        for (int index = 0; index < keys.size(); index += 25) {
            int endIndex = Math.min(index + 25, keys.size());
            List<WriteRequest> writes = new ArrayList<>();
            for (Map<String, AttributeValue> key : keys.subList(index, endIndex)) {
                writes.add(WriteRequest.builder()
                        .deleteRequest(DeleteRequest.builder().key(key).build())
                        .build());
            }

            dynamoDbClient.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(tableName, writes))
                    .build());
            deleted += writes.size();
        }

        return deleted;
    }
}
