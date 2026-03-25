terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region                      = var.aws_region
  access_key                  = var.aws_access_key
  secret_key                  = var.aws_secret_key
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    dynamodb = var.aws_endpoint
    sns      = var.aws_endpoint
    sqs      = var.aws_endpoint
    sts      = var.aws_endpoint
    iam      = var.aws_endpoint
  }
}

resource "aws_dynamodb_table" "metrics" {
  name         = var.metrics_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "client_id"
  range_key    = "timeblock"

  attribute {
    name = "client_id"
    type = "S"
  }

  attribute {
    name = "timeblock"
    type = "S"
  }
}

resource "aws_dynamodb_table" "anomalies" {
  name         = var.anomaly_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "client_id"
  range_key    = "timeblock"

  attribute {
    name = "client_id"
    type = "S"
  }

  attribute {
    name = "timeblock"
    type = "S"
  }
}

resource "aws_sns_topic" "alerts" {
  name = var.alert_topic_name
}

resource "aws_sqs_queue" "alerts" {
  name = var.alert_queue_name
}

data "aws_iam_policy_document" "sns_to_sqs" {
  statement {
    sid    = "AllowTopicPublish"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["sns.amazonaws.com"]
    }

    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.alerts.arn]

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_sns_topic.alerts.arn]
    }
  }
}

resource "aws_sqs_queue_policy" "alerts" {
  queue_url = aws_sqs_queue.alerts.id
  policy    = data.aws_iam_policy_document.sns_to_sqs.json
}

resource "aws_sns_topic_subscription" "alerts_to_queue" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.alerts.arn
}
