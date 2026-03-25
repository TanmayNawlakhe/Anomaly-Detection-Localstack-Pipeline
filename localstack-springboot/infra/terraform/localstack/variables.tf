variable "aws_region" {
  type        = string
  description = "AWS region for LocalStack provider emulation"
  default     = "us-east-1"
}

variable "aws_endpoint" {
  type        = string
  description = "LocalStack endpoint URL"
  default     = "http://localhost:4566"
}

variable "aws_access_key" {
  type        = string
  description = "Access key for LocalStack"
  default     = "test"
}

variable "aws_secret_key" {
  type        = string
  description = "Secret key for LocalStack"
  default     = "test"
}

variable "metrics_table_name" {
  type        = string
  description = "DynamoDB table for aggregated service metrics"
  default     = "service_metrics_aggregated"
}

variable "anomaly_table_name" {
  type        = string
  description = "DynamoDB table for anomaly detection outputs"
  default     = "anomaly_results"
}

variable "alert_topic_name" {
  type        = string
  description = "SNS topic that receives anomaly alerts"
  default     = "anomaly-alerts-topic"
}

variable "alert_queue_name" {
  type        = string
  description = "SQS queue subscribed to the anomaly SNS topic"
  default     = "anomaly-alerts-queue"
}
