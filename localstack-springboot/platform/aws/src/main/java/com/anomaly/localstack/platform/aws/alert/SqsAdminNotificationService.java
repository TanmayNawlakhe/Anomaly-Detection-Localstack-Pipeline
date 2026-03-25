package com.anomaly.localstack.platform.aws.alert;

import com.anomaly.localstack.config.AppProperties;
import com.anomaly.localstack.platform.aws.runtime.AwsResourceState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;

@Service
public class SqsAdminNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(SqsAdminNotificationService.class);

    private final SqsClient sqsClient;
    private final AwsResourceState resourceState;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final NotificationFeedService notificationFeedService;

    public SqsAdminNotificationService(SqsClient sqsClient,
                                       AwsResourceState resourceState,
                                       AppProperties appProperties,
                                       ObjectMapper objectMapper,
                                       NotificationFeedService notificationFeedService) {
        this.sqsClient = sqsClient;
        this.resourceState = resourceState;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.notificationFeedService = notificationFeedService;
    }

    @Scheduled(fixedDelayString = "${app.anomaly.alert-consumer-fixed-delay-ms:5000}")
    public void processAlertQueue() {
        if (!resourceState.isResourcesReady() || !appProperties.isAlertConsumerEnabled()) {
            return;
        }

        String queueUrl = resolveQueueUrl();
        if (queueUrl == null || queueUrl.isBlank()) {
            return;
        }

        List<Message> messages = sqsClient.receiveMessage(request -> request
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(1))
                .messages();

        if (messages.isEmpty()) {
            return;
        }

        String adminEmail = appProperties.getAdminAlertEmail();
        for (Message message : messages) {
            String notificationBody = extractAlertPayload(message.body());
            logger.warn("ADMIN NOTIFICATION (simulated email) to {} :: {}", adminEmail, notificationBody);
            notificationFeedService.addNotification(adminEmail, notificationBody);

            sqsClient.deleteMessage(request -> request
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle()));
        }
    }

    private String resolveQueueUrl() {
        String existingQueueUrl = resourceState.getAlertQueueUrl();
        if (existingQueueUrl != null && !existingQueueUrl.isBlank()) {
            return existingQueueUrl;
        }

        try {
            String resolvedQueueUrl = sqsClient.getQueueUrl(request -> request
                            .queueName(appProperties.getAlertQueueName()))
                    .queueUrl();
            resourceState.setAlertQueueUrl(resolvedQueueUrl);
            return resolvedQueueUrl;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractAlertPayload(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.hasNonNull("Message")) {
                return root.get("Message").asText();
            }
            return body;
        } catch (Exception ignored) {
            return body;
        }
    }
}
