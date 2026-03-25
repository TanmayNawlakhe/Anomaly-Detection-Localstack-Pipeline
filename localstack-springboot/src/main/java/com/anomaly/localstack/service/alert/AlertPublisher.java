package com.anomaly.localstack.service.alert;

import com.anomaly.localstack.model.AnomalyRecord;

public interface AlertPublisher {
    void publish(AnomalyRecord anomaly);
}
