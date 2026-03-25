package com.anomaly.localstack.platform.aws.alert;

import com.anomaly.localstack.config.AppProperties;
import com.anomaly.localstack.model.AnomalyRecord;
import com.anomaly.localstack.platform.aws.runtime.AwsResourceState;
import com.anomaly.localstack.service.alert.AlertPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;

@Service
public class SnsAlertPublisher implements AlertPublisher {

    private static final Logger logger = LoggerFactory.getLogger(SnsAlertPublisher.class);

    private final SnsClient snsClient;
    private final AwsResourceState resourceState;
    private final AppProperties appProperties;

    public SnsAlertPublisher(SnsClient snsClient,
                             AwsResourceState resourceState,
                             AppProperties appProperties) {
        this.snsClient = snsClient;
        this.resourceState = resourceState;
        this.appProperties = appProperties;
    }

    @Override
    public void publish(AnomalyRecord anomaly) {
        String topicArn = resourceState.getAlertTopicArn();
        if (topicArn == null || topicArn.isBlank()) {
            logger.warn("Skipping SNS alert because topic ARN is not initialized yet");
            return;
        }

        String message = "Anomaly detected for client=" + anomaly.clientId()
                + ", timeblock=" + anomaly.timeblock()
                + ", score=" + anomaly.score()
                + ", threshold=" + anomaly.threshold()
                + ", severity=" + anomaly.severity()
                + ", notify_admin=" + appProperties.getAdminAlertEmail();

        snsClient.publish(request -> request.topicArn(topicArn).subject("Anomaly Alert").message(message));
        logger.info("Published anomaly alert to SNS for client {} at {}", anomaly.clientId(), anomaly.timeblock());
    }
}
