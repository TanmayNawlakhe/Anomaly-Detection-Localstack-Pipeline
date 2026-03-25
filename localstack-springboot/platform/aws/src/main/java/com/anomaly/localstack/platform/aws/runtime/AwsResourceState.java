package com.anomaly.localstack.platform.aws.runtime;

import org.springframework.stereotype.Component;

@Component
public class AwsResourceState {

    private String alertTopicArn;
    private String alertQueueUrl;
    private volatile boolean resourcesReady;

    public String getAlertTopicArn() {
        return alertTopicArn;
    }

    public void setAlertTopicArn(String alertTopicArn) {
        this.alertTopicArn = alertTopicArn;
    }

    public String getAlertQueueUrl() {
        return alertQueueUrl;
    }

    public void setAlertQueueUrl(String alertQueueUrl) {
        this.alertQueueUrl = alertQueueUrl;
    }

    public boolean isResourcesReady() {
        return resourcesReady;
    }

    public void setResourcesReady(boolean resourcesReady) {
        this.resourcesReady = resourcesReady;
    }
}
