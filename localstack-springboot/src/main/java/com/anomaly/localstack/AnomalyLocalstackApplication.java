package com.anomaly.localstack;

import com.anomaly.localstack.config.AppProperties;
import com.anomaly.localstack.platform.aws.config.LocalStackAwsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({LocalStackAwsProperties.class, AppProperties.class})
public class AnomalyLocalstackApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnomalyLocalstackApplication.class, args);
    }
}
