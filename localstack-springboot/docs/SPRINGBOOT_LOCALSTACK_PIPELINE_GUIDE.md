# Spring Boot + LocalStack Anomaly Pipeline Guide

## 1) Project Focus (from `researchtopic (1).pdf`)
This project targets **near real-time anomaly detection for operational/business metrics** using:
- **Autoencoder-based unsupervised anomaly detection**
- **Per-client model artifacts**
- **Cloud-native orchestration with AWS-like services (LocalStack)**
- **Reproducible local validation** without cloud cost

The key research intent is not only model quality, but also validating the **end-to-end architecture lifecycle** (ingestion → aggregation → inference → anomaly persistence → alerting) in a robust, reproducible local environment.

---

## 2) What We Changed Architecturally
Originally planned architecture (proposal-level):
- Lambda for aggregation/inference
- EventBridge schedule triggers
- DynamoDB for storage
- SNS/SES for alerting

Current implemented architecture (this repo):
- **Spring Boot schedulers replaced Lambda triggers** for aggregation simulation + detection orchestration
- **No EventBridge dependency required** for runtime scheduling in the local pipeline
- **DynamoDB + SNS + SQS remain core LocalStack resources**
- Python autoencoder inference runs as a worker (`autoencoder-worker`) and can also be bridged from app services

Why this change:
- Practical local-development simplicity
- Avoid packaging/friction around local Lambda zip/runtime constraints
- Faster iteration on scheduler timing and accelerated simulated-clock behavior

---

## 3) Current End-to-End Pipeline
1. Metrics enter via UI/manual API or simulator.
2. Metrics are bucketed by aggregation interval (default logical bucket: `PT1H`).
3. Aggregated records are written into DynamoDB table `service_metrics_aggregated`.
4. Detection scheduler evaluates a lookback window over bucketed data.
5. Detected anomalies are written into DynamoDB table `anomaly_results`.
6. Alert messages are published to SNS topic `anomaly-alerts-topic`.
7. SNS is subscribed to SQS queue `anomaly-alerts-queue` for observable alert fan-out.
8. Python worker evaluates autoencoder models and writes ML anomaly outputs to DynamoDB.

---

## 4) LocalStack Resources Used (Current)
### Active LocalStack services
- `dynamodb`
- `sns`
- `sqs`

### Provisioning model
- Infrastructure is managed via Terraform in `infra/terraform/localstack`.
- Runtime application startup validates and discovers resources; it does not create them.

### Provisioned resources
- DynamoDB table: `service_metrics_aggregated`
- DynamoDB table: `anomaly_results`
- SNS topic: `anomaly-alerts-topic`
- SQS queue: `anomaly-alerts-queue`
- SNS → SQS subscription + queue policy

### Resources intentionally not used now
- EventBridge runtime triggers (replaced by Spring schedulers)
- Lambda runtime functions for aggregation/inference (replaced by Spring scheduler + Python worker)

---

## 5) Simulation + Timeblock Strategy
- **Aggregation criterion remains hourly** (`PT1H`) to stay consistent with trained model assumptions.
- **Accelerated simulation clock** advances bucket time each scheduler tick (e.g., every 10 seconds wall-clock == +1 hour logical time).
- Detection runs using bucket-aligned logic and stride controls (`detection-stride-buckets`) to emulate daily detection on accelerated data.
- Client IDs are normalized to `client_a@example.com`, `client_b@example.com`, `client_c@example.com` to match model artifact folders in `../models`.

---

## 6) Original Python Pipeline (Reference)
These files represent the original anomaly detection implementation:
- `train/train_baseline_anomaly_model.py`
  - Trains per-client LSTM autoencoder models.
  - Saves `model.keras`, `scaler.pkl`, `config.pkl` (window + threshold).
- `main/ingestion/aggregation_and_storage.py`
  - Validates raw metrics, aggregates into time buckets, stores in MongoDB.
- `main/inference/inference_and_alerts.py`
  - Loads model artifacts, computes reconstruction errors, flags anomalies.
  - Handles missing buckets and writes `output/anomalies.json`.
- `main/utils/helpers.py`
  - Validation helpers, bucket conversion, composite signal generation.
- `main/dto/metrics_dto.py`
  - Pydantic DTOs for raw and aggregated metric validation.

This logic was mapped conceptually into the SpringBoot + LocalStack architecture.

---

## 7) Spring Boot Module File-by-File Guide
Path root: `localstack-springboot/`

## 7.1 Build / Runtime / Infra
- `build.gradle`
  - Spring Boot build config, Java 17, AWS SDK BOM and dependencies.
- `settings.gradle`
  - Gradle project naming.
- `Dockerfile`
  - Multi-stage build (Gradle build stage + JRE runtime stage).
- `docker-compose.yml`
  - Orchestrates `localstack`, `anomaly-app`, `autoencoder-worker`.
  - Mounts root `models/` and `output/` where needed.
- `infra/terraform/localstack/main.tf`
  - Terraform resources for DynamoDB tables, SNS topic, SQS queue, queue policy, and SNS→SQS subscription.
- `infra/terraform/localstack/variables.tf`
  - Input variables for endpoint, credentials, and resource naming.
- `infra/terraform/localstack/outputs.tf`
  - Provisioning outputs for topic/queue/tables.
- `scripts/terraform-up.ps1`, `scripts/terraform-down.ps1`
  - PowerShell wrappers for `terraform init/apply` and `terraform init/destroy`.
- `scripts/install-java17.ps1`
  - Helper to install/configure Java 17 on Windows.

## 7.2 App Entrypoint
- `src/main/java/com/anomaly/localstack/AnomalyLocalstackApplication.java`
  - Spring Boot entrypoint with scheduling + configuration properties enabled.

## 7.3 Config Layer
- `src/main/java/com/anomaly/localstack/config/AppProperties.java`
  - Binds anomaly, simulation, scheduler, model, lookback settings.

## 7.4 Platform AWS Layer
- External source root: `platform/aws/src/main/java` (wired via Gradle `sourceSets`).
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/config/LocalStackAwsProperties.java`
  - Binds AWS endpoint/region/credentials from config.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/config/AwsClientConfig.java`
  - Creates AWS SDK clients for DynamoDB, SNS, SQS against LocalStack.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/runtime/AwsResourceState.java`
  - Holds initialized resource handles (topic ARN, queue URL) and resource-ready state.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/runtime/ResourceInitializer.java`
  - On app ready + periodic refresh: validates and discovers Terraform-provisioned tables/topic/queue/subscription.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/alert/SnsAlertPublisher.java`
  - Infra implementation of anomaly alert publishing to SNS.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/alert/SqsAdminNotificationService.java`
  - Polls SQS and routes admin-targeted simulated notifications.
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/repository/DynamoRepository.java`
  - DynamoDB adapter for aggregated metrics/anomalies queries/writes, dashboard summaries, and reset purge operations.

## 7.5 Model Layer
- `src/main/java/com/anomaly/localstack/model/RawMetric.java`
  - Input metric schema with validation.
- `src/main/java/com/anomaly/localstack/model/RawMetricsRequest.java`
  - Request wrapper for ingest endpoint.
- `src/main/java/com/anomaly/localstack/model/AggregatedMetric.java`
  - Aggregated record representation.
- `src/main/java/com/anomaly/localstack/model/AnomalyRecord.java`
  - Stored anomaly representation.

## 7.6 Repository Layer
- `platform/aws/src/main/java/com/anomaly/localstack/platform/aws/repository/DynamoRepository.java`
  - Upserts metrics and anomalies.
  - Queries recent metrics by client/timeblock.
  - Supplies dashboard raw/aggregated views, counts, severity/type stats.
  - Supports admin purge/reset operations.

## 7.7 Service Layer
- `src/main/java/com/anomaly/localstack/service/IngestionService.java`
  - Buckets raw metrics and stores aggregated rows in DynamoDB.
- `src/main/java/com/anomaly/localstack/service/SimulationService.java`
  - Generates realistic mixed normal/anomalous traffic.
  - Uses accelerated simulated clock aligned to aggregation interval.
- `src/main/java/com/anomaly/localstack/service/DetectionService.java`
  - Bucket-aware statistical detection over lookback window.
  - Writes anomalies and publishes alerts through `service.alert.AlertPublisher` abstraction.
  - Exposes detection status helpers for UI.
- `src/main/java/com/anomaly/localstack/service/AutoencoderBridgeService.java`
  - Optional bridge to invoke Python inference script from app context using platform AWS endpoint/credentials.

## 7.8 Controller Layer
- `src/main/java/com/anomaly/localstack/controller/MetricsController.java`
  - `POST /api/v1/metrics/ingest` for manual/programmatic ingestion.
- `src/main/java/com/anomaly/localstack/controller/SimulationController.java`
  - Start/stop/status endpoints for simulation lifecycle.
- `src/main/java/com/anomaly/localstack/controller/DashboardController.java`
  - Dashboard summary API: status, latest records, anomaly lists, chart stats.
- `src/main/java/com/anomaly/localstack/controller/AdminController.java`
  - Reset endpoint to clear DynamoDB tables and restart simulation state.

## 7.9 Resource/UI Layer
- `src/main/resources/application.yml`
  - Base defaults for app + anomaly + AWS settings.
- `src/main/resources/application-local.yml`
  - LocalStack profile overrides, simulation behavior tuning.
- `src/main/resources/static/index.html`
  - Simulation console UI:
    - simulation controls
    - manual ingest (including custom timestamp)
    - raw DynamoDB table
    - expandable anomaly rows with details
    - live KPI + pie-chart views
    - SNS/SQS simulated email deliveries table
    - reset action

## 7.10 Python Worker Layer
- `python/autoencoder_inference_localstack.py`
  - Pulls aggregated records from DynamoDB.
  - Loads per-client model/scaler/config artifacts.
  - Computes reconstruction errors and writes anomalies to DynamoDB.
- `python/run_worker.py`
  - Looping worker runner for periodic inference execution.
- `python/requirements.txt`
  - Python dependencies for worker/inference runtime.

---

## 8) Alert Delivery Reality in LocalStack
 - Alerts are emitted only for detected anomalies.
 - SNS -> SQS delivery is real in LocalStack emulation.
 - `ADMIN_ALERT_EMAIL` is respected as notification target metadata and log routing.
 - LocalStack free does not send real external email to inboxes; integrate SES/SMTP provider for real email.

---

## 9) What Was Cleaned Up as Unnecessary
The following were removed/disabled from the SpringBoot LocalStack stack because they were not needed for the current scheduler-based architecture:
- EventBridge initialization flow from startup resources
- EventBridge/Lambda/CloudWatchLogs SDK dependencies and beans
- EventBridge-specific config keys in app properties
- Empty placeholder `localstack-springboot/models/` folder

---

## 10) Operational Notes
- `simulation-enabled-by-default` is set to `false` for safer startup.
- Use UI **Start Simulation** explicitly.
- Recommended startup order:
  1. `docker compose up -d localstack`
  2. `.\scripts\terraform-up.ps1`
  3. `docker compose up -d anomaly-app autoencoder-worker`
- Recommended shutdown order:
  1. `docker compose stop anomaly-app autoencoder-worker`
  2. `.\scripts\terraform-down.ps1`
  3. `docker compose down localstack`
- Use admin reset endpoint for clean reruns:
  - `POST /api/v1/admin/reset`
- GPU warning logs in TensorFlow worker (`Could not find cuda drivers`) are expected on CPU machines and are not failures.

---

## 11) Future Improvements (Optional)
- Dedicated detection-run history table and chart
- Separate model quality dashboard (window coverage, warmup status, threshold drift)
- Optional retraining pipeline from DynamoDB snapshots
- SES/email integration for alert delivery simulation

---

## 12) Quick Verify Commands
```powershell
cd D:\AnomalyLocalstack\v2\localstack-springboot
docker compose up --build -d localstack
.\scripts\terraform-up.ps1
docker compose up --build -d anomaly-app autoencoder-worker
docker compose ps
Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health | Select-Object -ExpandProperty Content
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/v1/simulation/status | Select-Object -ExpandProperty Content
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/v1/dashboard/summary | Select-Object -ExpandProperty Content
```

UI:
- `http://localhost:8080/index.html`
