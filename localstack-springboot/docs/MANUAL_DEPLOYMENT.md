# Manual Deployment Guide

This guide explains how to deploy the current prototype manually and how model artifacts are handled.

## 1) What gets deployed

Current architecture is 3 runtime services:
- `localstack` (DynamoDB + SNS + SQS emulation)
- `anomaly-app` (Spring Boot API + UI + statistical scheduler)
- `autoencoder-worker` (Python ML inference loop)

For a full prototype, all three should be running.

---

## 2) Local manual deployment (Docker Compose)

From `localstack-springboot/`:

```powershell
cd D:\Projects\AnomalyLocalstack\v2\localstack-springboot
Copy-Item .env.example .env -ErrorAction SilentlyContinue
docker compose up -d localstack
.\scripts\terraform-up.ps1
docker compose up -d anomaly-app autoencoder-worker
```

Quick checks:

```powershell
docker compose ps
Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health | Select-Object -ExpandProperty Content
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/v1/dashboard/summary | Select-Object -ExpandProperty Content
```

UI:
- `http://localhost:8080/index.html`

---

## 3) Manual service split (if starting containers yourself)

You can start components in this order:
1. `localstack`
2. resource provisioning (`terraform-up.ps1`)
3. `anomaly-app`
4. `autoencoder-worker`

The app can run without worker, but then autonomous ML inference is missing.

---

## 4) Model artifacts (`models/`) — important

## 4.1 Local deployment behavior (already handled)
In `docker-compose.yml`, models are mounted from host filesystem into containers:
- for app: `../models:/workspace/models`
- for worker: `../models:/workspace/models`

So yes: for local runtime, external model folder is already handled via bind mounts.

## 4.2 Render blueprint behavior (current)
In `render.yaml`, worker uses:
- `MODEL_DIR=/opt/render/project/src/models`

This means models must exist in the deployed source tree at repo path `models/`.

So for Render, current setup assumes one of these:
1. `models/` is committed in repo, or
2. build/start step populates that folder before worker starts.

If models are not present there, worker runs but cannot load client artifacts.

## 4.3 If you want models outside containers on Render
Use one of these patterns:
- **Object storage pull on startup** (recommended): download model files from S3/R2/GCS into a local directory, then set `MODEL_DIR` to that path.
- **Bake models into a custom worker image**: simple, but model updates require rebuilding image.
- **Model registry fetch**: cleanest for versioning/governance.

---

## 5) Render deployment (current files)

Repo includes:
- `render.yaml` (3 services)
- `localstack-springboot/Dockerfile.localstack`
- `localstack-springboot/render/init-resources.sh` (auto-creates DynamoDB/SNS/SQS resources)

Deploy steps:
1. Push repo changes to GitHub.
2. In Render, create Blueprint from repo root.
3. Deploy all services.
4. Verify app health endpoint and dashboard API.

---

## 6) Troubleshooting

- App logs show resources not ready:
  - Ensure LocalStack service is healthy and init script ran.
- No ML anomalies appearing:
  - Confirm worker is running.
  - Confirm `MODEL_DIR` path contains per-client files: `model.keras`, `scaler.pkl`, `config.pkl`.
- Mode confusion:
  - `APP_ANOMALY_PIPELINE_MODE` env var overrides YAML profile values.

---

## 7) Recommended mode for your current demo

Keep:
- app mode: `statistical`
- worker enabled (ML every minute)

This gives:
- statistical detection on app schedule/stride
- independent ML inference from worker
- both writing to `anomaly_results`
