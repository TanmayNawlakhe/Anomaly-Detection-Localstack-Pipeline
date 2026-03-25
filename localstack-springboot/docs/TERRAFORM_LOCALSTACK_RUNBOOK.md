# Terraform LocalStack Runbook

This runbook defines the new infrastructure lifecycle for LocalStack resources using Terraform.

## Scope
Terraform in `infra/terraform/localstack` provisions and destroys:
- DynamoDB table `service_metrics_aggregated`
- DynamoDB table `anomaly_results`
- SNS topic `anomaly-alerts-topic`
- SQS queue `anomaly-alerts-queue`
- SNS -> SQS subscription and SQS queue policy

Spring Boot now validates these resources at startup (and retries discovery periodically). It no longer creates them.

## Prerequisites
- Docker / Docker Compose
- Terraform CLI (>= 1.5) **or** Docker-only fallback via `hashicorp/terraform:1.9.8`
- Project root: `localstack-springboot`

## Terraform Script Behavior
- `scripts/terraform-up.ps1` and `scripts/terraform-down.ps1` first try local `terraform`.
- If Terraform CLI is not installed, scripts automatically run Dockerized Terraform.
- In Docker fallback mode, scripts set `TF_VAR_aws_endpoint=http://host.docker.internal:4566` so Terraform can reach LocalStack from inside the container.

## Standard Startup (PowerShell)
```powershell
cd D:\AnomalyLocalstack\v2\localstack-springboot

docker compose up -d localstack

.\scripts\terraform-up.ps1

docker compose up -d anomaly-app autoencoder-worker
```

## Standard Shutdown (PowerShell)
```powershell
cd D:\AnomalyLocalstack\v2\localstack-springboot

docker compose stop anomaly-app autoencoder-worker

.\scripts\terraform-down.ps1

docker compose down localstack
```

## Direct Terraform Commands (if preferred)
```powershell
cd D:\AnomalyLocalstack\v2\localstack-springboot

terraform -chdir=infra/terraform/localstack init
terraform -chdir=infra/terraform/localstack apply -auto-approve

# Later cleanup
terraform -chdir=infra/terraform/localstack destroy -auto-approve
```

## Custom Variables
Copy `infra/terraform/localstack/terraform.tfvars.example` to a local var-file and use:

```powershell
.\scripts\terraform-up.ps1 -VarFile my.local.tfvars
.\scripts\terraform-down.ps1 -VarFile my.local.tfvars
```

## Quick Verification
```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health | Select-Object -ExpandProperty Content
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/v1/simulation/status | Select-Object -ExpandProperty Content
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/v1/dashboard/summary | Select-Object -ExpandProperty Content
```

UI:
- `http://localhost:8080/index.html`
