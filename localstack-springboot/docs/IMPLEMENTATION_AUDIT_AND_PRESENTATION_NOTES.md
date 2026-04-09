# Implementation Audit + Presentation Notes

## 1) What is implemented now (actual architecture)

This repository currently runs a **scheduler-driven Spring Boot orchestration** on top of LocalStack, with optional Python autoencoder inference.

### Runtime flow (actual)
1. Raw metrics are ingested via `POST /api/v1/metrics/ingest`.
2. `IngestionService` buckets them using `app.anomaly.aggregation-interval` (default `PT1H`) and upserts into DynamoDB table `service_metrics_aggregated`.
3. `SimulationService` can generate synthetic metrics on a fixed delay and advances a simulated clock bucket-by-bucket.
4. `DetectionService` runs on scheduler (`app.scheduler.fixed-delay-ms`) and detects anomalies based on configured pipeline mode.
5. Anomalies are written to DynamoDB table `anomaly_results`.
6. Alerts are published to SNS (`anomaly-alerts-topic`), then delivered to SQS (`anomaly-alerts-queue`) via subscription.
7. `SqsAdminNotificationService` consumes queue messages and stores simulated email delivery entries for dashboard display.

### Core infrastructure/services in use
- **LocalStack services used**: `dynamodb`, `sns`, `sqs`
- **Provisioning**: Terraform (`infra/terraform/localstack/*.tf`)
- **Compute/orchestration**: Spring Boot schedulers (`@Scheduled`) + optional Python worker container

---

## 2) Service-by-service explanation (Spring Boot)

### App entry + config
- `src/main/java/com/anomaly/localstack/AnomalyLocalstackApplication.java`
  - Enables Spring scheduling and config properties.
- `src/main/java/com/anomaly/localstack/config/AppProperties.java`
  - Binds all anomaly/simulation/timing/pipeline properties.

### AWS platform layer (active implementations)
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/config/LocalStackAwsProperties.java`
  - Reads LocalStack endpoint/region/credentials.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/config/AwsClientConfig.java`
  - Creates DynamoDB/SNS/SQS clients.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/runtime/AwsResourceState.java`
  - Stores discovered ARNs/URLs and readiness.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/runtime/ResourceInitializer.java`
  - Validates Terraform-provisioned resources and SNS->SQS subscription.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/repository/DynamoRepository.java`
  - DB adapter for reads/writes/stats/purge.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/alert/SnsAlertPublisher.java`
  - Publishes anomaly alerts to SNS.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/alert/SqsAdminNotificationService.java`
  - Polls SQS and records simulated admin notifications.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/alert/NotificationFeedService.java`
  - In-memory notification feed for dashboard.

### Business pipeline layer
- `src/main/java/com/anomaly/localstack/service/IngestionService.java`
  - Aggregates metrics into time buckets.
- `src/main/java/com/anomaly/localstack/service/SimulationService.java`
  - Synthetic traffic + accelerated simulated time.
- `src/main/java/com/anomaly/localstack/service/DetectionService.java`
  - Statistical detection and optional Python bridge invocation.
- `src/main/java/com/anomaly/localstack/service/AutoencoderBridgeService.java`
  - Invokes Python script from Java process.

### API/controllers
- `src/main/java/com/anomaly/localstack/controller/MetricsController.java` -> `/api/v1/metrics/ingest`
- `src/main/java/com/anomaly/localstack/controller/SimulationController.java` -> start/stop/status
- `src/main/java/com/anomaly/localstack/controller/DashboardController.java` -> summary + anomaly detail
- `src/main/java/com/anomaly/localstack/controller/AdminController.java` -> reset/purge

### UI
- `src/main/resources/static/index.html`
  - Simulation controls, manual ingest, raw metrics table, anomaly table, pie charts, SNS/SQS notification panel.

### Python worker
- `python/autoencoder_inference_localstack.py`
  - Reads DynamoDB metrics, loads client model artifacts (`model.keras`, `scaler.pkl`, `config.pkl`), writes anomalies to DynamoDB.
- `python/run_worker.py`
  - Periodic inference loop (`WORKER_INTERVAL_SECONDS`, default `60`).

---

## 3) Discrepancies: original idea vs implementation

### A) `researchtopic (1).pdf` vs code
1. **Lambda/EventBridge planned** -> **Spring schedulers implemented**.
2. **SNS/SES alerting planned** -> **SNS/SQS simulated notifications implemented** (no real SES delivery).
3. **Serverless orchestration proposal** -> **containerized app + scheduler orchestration**.

### B) `anomalydetect (1) (1).txt` vs code
1. **Concern: model artifacts not saved** -> now addressed in `train/train_baseline_anomaly_model.py`.
2. **Concern: lambda size/packaging issues** -> avoided by scheduler + worker design.
3. **Plan says Lambda aggregation/inference** -> implemented as Spring + optional worker.
4. **Plan says SES/SNS alerts** -> implemented SNS->SQS + simulated admin log feed.

### C) `Internship Report.pdf` vs code
1. Report claims: “fully implemented serverless pipeline utilizing AWS Lambda ... Event Bridge ...” -> not true for current codebase runtime.
2. Report mentions broader AWS stack (e.g., S3/Kinesis) -> not provisioned in active Terraform for this module.
3. Report/tooling mentions Streamlit visualization -> current UI is Spring static web page (`index.html`).
4. Report describes real-time cloud-native flow, but in repo simulation is accelerated and scheduler-driven.

---

## 4) Simulation timing behavior (important for demo)

Current behavior in `application-local.yml`:
- `aggregation-interval: PT1H` (logical bucket = 1 hour)
- `simulation-fixed-delay-ms: 10000` (every 10 seconds wall time)

`SimulationService` advances `simulatedCursor` by one full aggregation bucket per tick.
So, **yes**: right now 1 logical hour passes in ~10 seconds (accelerated simulation).

### Make it real-time (1 hour bucket = 1 real hour)
Set:
- `app.anomaly.simulation-fixed-delay-ms: 3600000`

Optional consistency tuning:
- `app.scheduler.fixed-delay-ms: 3600000` (if you want detector to run hourly)
- `app.anomaly.detection-stride-buckets: 1` (detect each bucket) or keep `24` (once per 24 buckets)
- worker `WORKER_INTERVAL_SECONDS: 3600` (if relying on worker cadence)

---

## 5) How to switch from `statistical` to ML mode

Current local profile has:
- `pipeline-mode: statistical` in `src/main/resources/application-local.yml`

Also, Docker Compose currently sets:
- `APP_ANOMALY_PIPELINE_MODE=statistical` in `docker-compose.yml`

This environment variable overrides YAML at runtime for the container.

### Available modes (from `DetectionService`)
- `statistical` -> Java statistical detector only
- `python-autoencoder` -> Python bridge only
- `hybrid` -> Python bridge + Java statistical detector

### What to change
1. In `src/main/resources/application-local.yml`, set:
```yaml
app:
  anomaly:
    pipeline-mode: python-autoencoder
```
(or `hybrid`)

2. In `docker-compose.yml`, update (or remove) this line under `anomaly-app`:
```yaml
- APP_ANOMALY_PIPELINE_MODE=python-autoencoder
```
(or `hybrid`)

3. Restart app container so env changes apply.

### Verify active mode
- Call dashboard summary and inspect `pipeline_mode` field:
  - `GET /api/v1/dashboard/summary`

---

## 6) Correct startup order (verified)

```powershell
cd D:\Projects\AnomalyLocalstack\v2\localstack-springboot
Copy-Item .env.example .env -ErrorAction SilentlyContinue
docker compose up -d localstack
.\scripts\terraform-up.ps1
docker compose up -d anomaly-app autoencoder-worker
```

Recommended: if changing pipeline mode/env vars, run:
```powershell
docker compose up -d --force-recreate anomaly-app
```

---

## 7) Presentation-safe positioning (suggested)

Use wording like:
- “The architecture **evolved** from proposed Lambda/EventBridge to scheduler-based orchestration for local reproducibility and rapid iteration.”
- “Core AWS interactions (DynamoDB + SNS + SQS) are fully emulated on LocalStack with Terraform-managed resources.”
- “The system supports statistical, ML-only, and hybrid detection modes, selectable via configuration.”
