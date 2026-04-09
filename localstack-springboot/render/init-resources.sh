#!/usr/bin/env bash
set -euo pipefail

echo "[localstack-init] Creating DynamoDB tables..."
awslocal dynamodb create-table \
  --table-name service_metrics_aggregated \
  --attribute-definitions AttributeName=client_id,AttributeType=S AttributeName=timeblock,AttributeType=S \
  --key-schema AttributeName=client_id,KeyType=HASH AttributeName=timeblock,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST >/dev/null 2>&1 || true

awslocal dynamodb create-table \
  --table-name anomaly_results \
  --attribute-definitions AttributeName=client_id,AttributeType=S AttributeName=timeblock,AttributeType=S \
  --key-schema AttributeName=client_id,KeyType=HASH AttributeName=timeblock,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST >/dev/null 2>&1 || true

echo "[localstack-init] Creating SNS topic + SQS queue..."
TOPIC_ARN=$(awslocal sns create-topic --name anomaly-alerts-topic --query 'TopicArn' --output text)
QUEUE_URL=$(awslocal sqs create-queue --queue-name anomaly-alerts-queue --query 'QueueUrl' --output text)
QUEUE_ARN=$(awslocal sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

echo "[localstack-init] Applying queue policy for SNS->SQS..."
POLICY=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowTopicPublish",
      "Effect": "Allow",
      "Principal": {"Service": "sns.amazonaws.com"},
      "Action": "sqs:SendMessage",
      "Resource": "${QUEUE_ARN}",
      "Condition": {
        "ArnEquals": {"aws:SourceArn": "${TOPIC_ARN}"}
      }
    }
  ]
}
JSON
)

awslocal sqs set-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attributes Policy="$POLICY" >/dev/null

echo "[localstack-init] Ensuring SNS subscription exists..."
EXISTING_SUB=$(awslocal sns list-subscriptions-by-topic \
  --topic-arn "$TOPIC_ARN" \
  --query "Subscriptions[?Endpoint=='${QUEUE_ARN}'].SubscriptionArn" \
  --output text)

if [[ -z "${EXISTING_SUB}" || "${EXISTING_SUB}" == "None" ]]; then
  awslocal sns subscribe --topic-arn "$TOPIC_ARN" --protocol sqs --notification-endpoint "$QUEUE_ARN" >/dev/null
fi

echo "[localstack-init] Resource bootstrap completed."
