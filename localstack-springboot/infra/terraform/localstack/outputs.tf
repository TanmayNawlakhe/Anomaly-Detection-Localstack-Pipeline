output "metrics_table_name" {
  description = "Provisioned metrics DynamoDB table name"
  value       = aws_dynamodb_table.metrics.name
}

output "anomaly_table_name" {
  description = "Provisioned anomaly DynamoDB table name"
  value       = aws_dynamodb_table.anomalies.name
}

output "alert_topic_arn" {
  description = "Provisioned SNS topic ARN"
  value       = aws_sns_topic.alerts.arn
}

output "alert_queue_url" {
  description = "Provisioned SQS queue URL"
  value       = aws_sqs_queue.alerts.id
}

output "alert_queue_arn" {
  description = "Provisioned SQS queue ARN"
  value       = aws_sqs_queue.alerts.arn
}
