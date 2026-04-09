package com.anomaly.localstack.service;

import com.anomaly.localstack.config.AppProperties;
import com.anomaly.localstack.model.RawMetric;
import com.anomaly.localstack.platform.aws.runtime.AwsResourceState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SimulationService {
    private static final Set<String> CANONICAL_CLIENTS = Set.of(
        "client_a@example.com",
        "client_b@example.com",
        "client_c@example.com"
    );


    private static final Logger logger = LoggerFactory.getLogger(SimulationService.class);

    private final IngestionService ingestionService;
    private final AwsResourceState awsResourceState;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean enabled;
    private final Random random;
    private final Set<String> clients;
    private final Map<String, Integer> anomalyWeights;
    private final Duration aggregationDuration;
    private Instant simulatedCursor;

    public SimulationService(IngestionService ingestionService,
                             AwsResourceState awsResourceState,
                             AppProperties appProperties,
                             ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.awsResourceState = awsResourceState;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.enabled = new AtomicBoolean(appProperties.isSimulationEnabledByDefault());
        this.random = new Random(42);
        this.clients = ConcurrentHashMap.newKeySet();
        this.anomalyWeights = new ConcurrentHashMap<>();
        this.aggregationDuration = Duration.parse(appProperties.getAggregationInterval());
        this.simulatedCursor = floor(Instant.now(), aggregationDuration);
    }

    @PostConstruct
    public void initializeSeeds() {
        clients.addAll(CANONICAL_CLIENTS);

        File seedFile = new File(appProperties.getSimulationAnomalySeedFile());
        if (!seedFile.exists()) {
            logger.warn("Anomaly seed file not found at {}. Using default simulation profile.", seedFile.getPath());
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(seedFile);
            for (JsonNode node : root) {
                String clientId = normalizeClientId(node.path("client_id").asText());
                if (clientId == null || clientId.isBlank()) {
                    continue;
                }
                if (!CANONICAL_CLIENTS.contains(clientId)) {
                    continue;
                }

                int weight = 1;
                for (JsonNode anomaly : node.path("anomalies")) {
                    String type = anomaly.path("anomaly_type").asText();
                    if ("USAGE_PATTERN_DEVIATION".equals(type)) {
                        weight += 2;
                    } else if ("MISSING_DATA".equals(type)) {
                        weight += 1;
                    }
                }
                anomalyWeights.put(clientId, weight);
            }
            logger.info("Loaded anomaly seeds for {} clients from {}", clients.size(), seedFile.getPath());
        } catch (Exception exception) {
            logger.warn("Failed to parse anomaly seed file {}: {}", seedFile.getPath(), exception.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.anomaly.simulation-fixed-delay-ms:5000}")
    public void emitSimulationBatch() {
        if (!awsResourceState.isResourcesReady()) {
            return;
        }

        if (!enabled.get()) {
            return;
        }

        List<RawMetric> batch = generateBatch();
        ingestionService.aggregateAndStore(batch);
        logger.info("Simulated and ingested {} raw metrics", batch.size());
        simulatedCursor = simulatedCursor.plus(aggregationDuration);
    }

    public void start() {
        enabled.set(true);
    }

    public void stop() {
        enabled.set(false);
    }

    public void resetSimulationClock() {
        this.simulatedCursor = floor(Instant.now(), aggregationDuration);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public int getClientCount() {
        return clients.size();
    }

    public Instant getSimulatedCursor() {
        return simulatedCursor;
    }

    public Instant getCurrentBucketStart() {
        return simulatedCursor;
    }

    public Instant getNextBucketStart() {
        return simulatedCursor.plus(aggregationDuration);
    }

    public long getAggregationBucketSeconds() {
        return Math.max(1L, aggregationDuration.getSeconds());
    }

    private List<RawMetric> generateBatch() {
        List<RawMetric> metrics = new ArrayList<>();
        int batchSize = Math.max(1, appProperties.getSimulationBatchSize());
        Instant bucketStart = simulatedCursor;
        List<String> clientList = new ArrayList<>(clients);

        long bucketSeconds = Math.max(1, aggregationDuration.getSeconds());

        for (int index = 0; index < batchSize; index++) {
            String clientId = clientList.get(random.nextInt(clientList.size()));

            boolean missingDataEvent = shouldInjectMissingData(clientId);
            if (missingDataEvent) {
                continue;
            }

            long offset = random.nextInt((int) Math.min(bucketSeconds, Integer.MAX_VALUE));
            metrics.add(createMetric(clientId, bucketStart.plusSeconds(offset)));
        }
        return metrics;
    }

    private RawMetric createMetric(String clientId, Instant timestamp) {
        int weight = anomalyWeights.getOrDefault(clientId, 1);
        double anomalyProbability = Math.min(0.08 + (0.025 * weight), 0.35);
        boolean usageAnomaly = random.nextDouble() < anomalyProbability;

        long requestCount;
        long errorCount;
        double latency;

        if (usageAnomaly) {
            boolean burstPattern = random.nextBoolean();
            boolean severeSpike = random.nextDouble() < 0.45;
            if (burstPattern) {
                requestCount = 5500 + random.nextInt(5000);
                errorCount = (long) Math.max(1, requestCount * (0.10 + random.nextDouble() * 0.12));
                latency = 300 + random.nextDouble() * 220;
            } else {
                requestCount = 2200 + random.nextInt(2200);
                errorCount = (long) Math.max(1, requestCount * (0.22 + random.nextDouble() * 0.15));
                latency = 450 + random.nextDouble() * 320;
            }

            if (severeSpike) {
                requestCount = Math.round(requestCount * (1.4 + random.nextDouble() * 0.6));
                errorCount = Math.round(Math.max(errorCount, requestCount * (0.28 + random.nextDouble() * 0.20)));
                latency = latency * (1.15 + random.nextDouble() * 0.45);
            }
        } else {
            requestCount = 850 + random.nextInt(650);
            errorCount = (long) Math.max(0, requestCount * (0.003 + random.nextDouble() * 0.009));
            latency = 75 + random.nextDouble() * 45;
        }

        RawMetric metric = new RawMetric();
        metric.setClientId(clientId);
        metric.setTimestamp(timestamp);
        metric.setRequestCount(requestCount);
        metric.setErrorCount(errorCount);
        metric.setAvgLatencyMs(latency);
        return metric;
    }

    private boolean shouldInjectMissingData(String clientId) {
        int weight = anomalyWeights.getOrDefault(clientId, 1);
        double probability = Math.min(0.005 + (0.005 * weight), 0.05);
        return random.nextDouble() < probability;
    }

    private Instant floor(Instant instant, Duration duration) {
        long bucketSeconds = Math.max(1, duration.getSeconds());
        long epochSeconds = instant.getEpochSecond();
        long floored = (epochSeconds / bucketSeconds) * bucketSeconds;
        return Instant.ofEpochSecond(floored).truncatedTo(ChronoUnit.SECONDS);
    }

    private String normalizeClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return clientId;
        }

        if (clientId.endsWith("@siemens.com")) {
            return clientId.replace("@siemens.com", "@example.com");
        }
        if (clientId.endsWith("@xyz.com")) {
            return clientId.replace("@xyz.com", "@example.com");
        }
        return clientId;
    }
}
