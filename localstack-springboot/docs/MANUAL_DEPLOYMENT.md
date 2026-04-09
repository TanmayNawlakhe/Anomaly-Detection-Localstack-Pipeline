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

## 4.2 Render app-only behavior (current)
Current `render.yaml` deploys only `anomaly-app` (web service).

`autoencoder-worker` stays local in this model, so model loading happens on your laptop runtime via:
- local worker mount: `../models:/workspace/models`
- local worker env: `MODEL_DIR=/workspace/models`

Result: Render app does not need model files; your local worker does.

## 4.3 If you later move worker to cloud
If you deploy worker remotely later, use one of these patterns for model artifacts:
- **Object storage pull on startup** (recommended): download model files from S3/R2/GCS into a local directory, then set `MODEL_DIR` to that path.
- **Bake models into a custom worker image**: simple, but model updates require rebuilding image.
- **Model registry fetch**: cleanest for versioning/governance.

---

## 5) Render deployment (current files)

Repo includes:
- `render.yaml` (app-only web service)

Deploy steps:
1. Push repo changes to GitHub.
2. In Render, create Blueprint from repo root.
3. Deploy `anomaly-app` service.
4. In Render env vars, set `APP_AWS_ENDPOINT` to your tunnel URL for local LocalStack.
5. Redeploy and verify app health + dashboard API.

---

## 6) Troubleshooting

- App logs show resources not ready:
  - Ensure local `localstack` is healthy.
  - Ensure local Terraform was applied (`.\scripts\terraform-up.ps1`).
  - Ensure tunnel URL is active and set in Render `APP_AWS_ENDPOINT`.
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
