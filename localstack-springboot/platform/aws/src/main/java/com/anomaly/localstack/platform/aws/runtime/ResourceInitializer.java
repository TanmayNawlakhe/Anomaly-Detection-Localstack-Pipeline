package com.anomaly.localstack.platform.aws.runtime;

import com.anomaly.localstack.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.ListSubscriptionsByTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Set;

@Component
public class ResourceInitializer {

    private static final Logger logger = LoggerFactory.getLogger(ResourceInitializer.class);

    private final DynamoDbClient dynamoDbClient;
    private final SnsClient snsClient;
    private final SqsClient sqsClient;
    private final AppProperties appProperties;
    private final AwsResourceState resourceState;

    public ResourceInitializer(DynamoDbClient dynamoDbClient,
                               SnsClient snsClient,
                               SqsClient sqsClient,
                               AppProperties appProperties,
                               AwsResourceState resourceState) {
        this.dynamoDbClient = dynamoDbClient;
        this.snsClient = snsClient;
        this.sqsClient = sqsClient;
        this.appProperties = appProperties;
        this.resourceState = resourceState;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeResources() {
                discoverResources(true);
        }

        @Scheduled(fixedDelayString = "${app.aws.resource-discovery-fixed-delay-ms:5000}")
        public void refreshResourceState() {
                if (resourceState.isResourcesReady()) {
                        return;
                }
                discoverResources(false);
        }

        private void discoverResources(boolean logMissingAsWarning) {
        resourceState.setResourcesReady(false);

                if (!tableExists(appProperties.getMetricsTable())) {
                        logMissing(logMissingAsWarning, "Required DynamoDB table missing: {}", appProperties.getMetricsTable());
                        return;
                }

                if (!tableExists(appProperties.getAnomalyTable())) {
                        logMissing(logMissingAsWarning, "Required DynamoDB table missing: {}", appProperties.getAnomalyTable());
                        return;
                }

                String topicArn = resolveTopicArn(appProperties.getAlertTopicName());
                if (topicArn == null) {
                        logMissing(logMissingAsWarning, "Required SNS topic missing: {}", appProperties.getAlertTopicName());
                        return;
                }

                String queueUrl = resolveQueueUrl(appProperties.getAlertQueueName());
                if (queueUrl == null) {
                        logMissing(logMissingAsWarning, "Required SQS queue missing: {}", appProperties.getAlertQueueName());
                        return;
                }

                String queueArn = resolveQueueArn(queueUrl);
                if (queueArn == null) {
                        logMissing(logMissingAsWarning, "Could not resolve queue ARN for queue URL {}", queueUrl);
                        return;
                }

                if (!hasSubscription(topicArn, queueArn)) {
                        logMissing(logMissingAsWarning, "Missing SNS->SQS subscription for topic {} and queue {}", topicArn, queueArn);
                        return;
                }

                resourceState.setAlertTopicArn(topicArn);
                resourceState.setAlertQueueUrl(queueUrl);
                resourceState.setResourcesReady(true);
                logger.info("AWS resources discovered and validated; schedulers can start processing");
    }

        private void logMissing(boolean warn, String message, Object... args) {
                if (warn) {
                        logger.warn(message, args);
                        return;
                }
                logger.debug(message, args);
        }

        private boolean tableExists(String tableName) {
        Set<String> tableNames = Set.copyOf(dynamoDbClient.listTables(ListTablesRequest.builder().build()).tableNames());
                return tableNames.contains(tableName);
    }

        private String resolveTopicArn(String topicName) {
                return snsClient.listTopicsPaginator()
                                .topics()
                                .stream()
                                .map(topic -> topic.topicArn())
                                .filter(topicArn -> topicArn != null && topicArn.endsWith(":" + topicName))
                                .findFirst()
                                .orElse(null);
        }

        private String resolveQueueUrl(String queueName) {
                try {
                        return sqsClient.getQueueUrl(builder -> builder.queueName(queueName)).queueUrl();
                } catch (Exception exception) {
                        return null;
        }
        }

        private String resolveQueueArn(String queueUrl) {
                String queueArn = sqsClient.getQueueAttributes(builder -> builder
                                                .queueUrl(queueUrl)
                                                .attributeNames(QueueAttributeName.QUEUE_ARN))
                                .attributes()
                                .get(QueueAttributeName.QUEUE_ARN);

                if (queueArn == null || queueArn.isBlank()) {
                        return null;
        }
                return queueArn;
        }

        private boolean hasSubscription(String topicArn, String queueArn) {
                return snsClient.listSubscriptionsByTopic(ListSubscriptionsByTopicRequest.builder()
                                                .topicArn(topicArn)
                                                .build())
                .subscriptions()
                .stream()
                .anyMatch(subscription -> subscription.endpoint() != null && queueArn.equals(subscription.endpoint()));
    }
}
