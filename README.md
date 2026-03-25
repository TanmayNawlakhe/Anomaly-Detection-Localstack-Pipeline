# LocalStack Spring Boot Anomaly Orchestrator

This module implements your requested LocalStack-backed Spring Boot platform layer:

- AWS client beans for LocalStack (`DynamoDbClient`, `SnsClient`, `SqsClient`)
- Terraform-managed LocalStack resources (`DynamoDB` + `SNS/SQS` + subscription/policy)
- Startup-time resource validation/discovery in app (no create-on-startup)
- Ingestion API that aggregates metrics into time buckets per client
- Scheduled anomaly detection and alert publishing to SNS
- Realtime simulation seeded from `../output/anomalies.json`
- Browser UI for simulation controls and manual ingestion (`/index.html`)
- Autoencoder worker that processes LocalStack DynamoDB data and writes anomaly results

Code organization now separates infra concerns from domain flow:
- `platform/aws/src/main/java/*` contains LocalStack/AWS clients, resource lifecycle, DynamoDB adapter, and SNS/SQS notification plumbing.
- `service/*` keeps anomaly pipeline logic (ingestion/simulation/detection/bridge) focused on business flow.
- `build.gradle` includes this external source root through `sourceSets.main.java.srcDirs += ['platform/aws/src/main/java']`.
- Simulation/model client IDs are standardized to `client_a@example.com`, `client_b@example.com`, `client_c@example.com` to align with `../models` artifacts.

Detailed architecture and file-by-file documentation:
- `docs/SPRINGBOOT_LOCALSTACK_PIPELINE_GUIDE.md`
- `docs/TERRAFORM_LOCALSTACK_RUNBOOK.md`

## Architecture Mapping to Your Existing Python Flow

- Your Python ingestion (`main/ingestion/aggregation_and_storage.py`) groups metrics into time buckets and stores them.
- This app mirrors that in `IngestionService` and persists to DynamoDB table `service_metrics_aggregated`.
- Your Python inference computes a composite signal and flags deviations.
- This app mirrors that in `DetectionService` on a scheduler (LocalStack alternative to EventBridge + Lambda).

## End-to-End Process Flow (Current)

1. **Service events arrive** via `POST /api/v1/metrics/ingest`.
2. **Aggregation step** groups metrics by `clientId + timeblock` (`PT1H` by default).
3. **Storage step** upserts grouped records to DynamoDB table `service_metrics_aggregated`.
4. **Terraform provisioning** creates DynamoDB tables, SNS topic, SQS queue, and SNS→SQS subscription.
5. **Detection scheduler** runs every `app.scheduler.fixed-delay-ms`, queries lookback window, computes composite signal, and classifies anomalies.
6. **Autoencoder worker** reads same aggregated DynamoDB records, loads client `.keras` + scaler/config artifacts, and writes ML anomalies.
7. **Anomaly persistence** stores findings into DynamoDB table `anomaly_results`.
8. **Alerting** publishes anomaly events to SNS topic `anomaly-alerts-topic`.

## Build and Run (Gradle)

```bash
gradle clean bootJar
```

wrapper-style helper scripts are included:

```bash
./gradlew clean bootJar
```

```powershell
.\gradlew.bat clean bootJar
```

if your machine defaults to Java 8, install Java 17 first:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\install-java17.ps1
```

or with Dockerized Gradle:

```bash
docker run --rm -v "$PWD":/workspace -w /workspace gradle:8.14.3-jdk17 gradle --no-daemon clean bootJar
```

## Project Structure

- `src/main/java/com/anomaly/localstack/config` - LocalStack property binding and AWS beans
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws` - LocalStack property binding, DynamoDB/SNS/SQS adapters, and startup resource validation/discovery
- `src/main/java/com/anomaly/localstack/controller/MetricsController.java` - ingestion endpoint
- `infra/terraform/localstack/*.tf` - Terraform IaC for LocalStack resource lifecycle

## Run with Docker Compose

Create `localstack-springboot/.env` (you can copy from `.env.example`) and set your admin target:

```dotenv
ADMIN_ALERT_EMAIL=admin@example.com
ALERT_CONSUMER_ENABLED=true
ALERT_CONSUMER_FIXED_DELAY_MS=5000
```

```powershell
docker compose up --build -d localstack
\.\scripts\terraform-up.ps1
docker compose up --build -d anomaly-app autoencoder-worker
```

App endpoint: `http://localhost:8080`
LocalStack endpoint: `http://localhost:4566`
UI endpoint: `http://localhost:8080/index.html`

## Ingest Metrics

```bash
curl -X POST "http://localhost:8080/api/v1/metrics/ingest" \
  -H "Content-Type: application/json" \
  -d '{
    "metrics": [
      {
        "timestamp": "2026-03-23T10:00:00Z",
        "clientId": "client_a@example.com",
        "requestCount": 1200,
        "errorCount": 12,
        "avgLatencyMs": 95.4
      },
      {
        "timestamp": "2026-03-23T10:15:00Z",
        "clientId": "client_a@example.com",
        "requestCount": 1150,
        "errorCount": 8,
        "avgLatencyMs": 88.0
      }
    ]
  }'
```

## Notes

- Scheduler interval is `app.scheduler.fixed-delay-ms` (default `30000`).
- Runtime orchestration in this project is scheduler-driven (no EventBridge/Lambda dependency).
- This module is designed as the platform orchestration layer around your existing Python model pipeline.
- Build system is Gradle (`build.gradle`, `settings.gradle`).
- LocalStack infra lifecycle is Terraform-based; use `scripts/terraform-up.ps1` and `scripts/terraform-down.ps1`.
- `autoencoder-worker` in `docker-compose.yml` executes ML inference cycles using `python/autoencoder_inference_localstack.py`.
- SNS/SQS alerts are active: anomalies are published to SNS and delivered to SQS.
- Admin notifications are consumed from SQS by `SqsAdminNotificationService` and logged as simulated email notifications to `ADMIN_ALERT_EMAIL`.
- Dashboard UI includes an `SNS/SQS Simulated Email Deliveries` table fed from recent consumed notifications.
- Setting `ADMIN_ALERT_EMAIL=<your-email>` changes the target in alert payload/log routing, but LocalStack free still simulates delivery.
- LocalStack free tier does not send real internet email; for actual inbox delivery, integrate real SES/SMTP provider outside LocalStack.
- Realtime charts are rendered as pie charts for anomaly severity/type distribution.
