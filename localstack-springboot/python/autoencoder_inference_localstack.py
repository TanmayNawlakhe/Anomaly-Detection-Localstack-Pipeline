import json
import os
import pickle
from datetime import datetime, timezone, timedelta

import boto3
import numpy as np
import pandas as pd
from tensorflow.keras.models import load_model


AWS_REGION = os.getenv("AWS_REGION", "us-east-1")
AWS_ACCESS_KEY_ID = os.getenv("AWS_ACCESS_KEY_ID", "test")
AWS_SECRET_ACCESS_KEY = os.getenv("AWS_SECRET_ACCESS_KEY", "test")
AWS_ENDPOINT_URL = os.getenv("AWS_ENDPOINT_URL", "http://localhost:4566")

METRICS_TABLE = os.getenv("METRICS_TABLE", "service_metrics_aggregated")
ANOMALY_TABLE = os.getenv("ANOMALY_TABLE", "anomaly_results")
MODEL_DIR = os.getenv("MODEL_DIR", "../models")
LOOKBACK_HOURS = int(os.getenv("LOOKBACK_HOURS", "24"))
ALPHA = float(os.getenv("ALPHA", "100.0"))
BETA = float(os.getenv("BETA", "0.1"))


def get_client():
    return boto3.client(
        "dynamodb",
        region_name=AWS_REGION,
        endpoint_url=AWS_ENDPOINT_URL,
        aws_access_key_id=AWS_ACCESS_KEY_ID,
        aws_secret_access_key=AWS_SECRET_ACCESS_KEY,
    )


def parse_items(items):
    records = []
    for item in items:
        records.append(
            {
                "client_id": item["client_id"]["S"],
                "timeblock": item["timeblock"]["S"],
                "request_count": float(item.get("request_count", {"N": "0"})["N"]),
                "error_count": float(item.get("error_count", {"N": "0"})["N"]),
                "avg_latency_ms": float(item.get("avg_latency_ms", {"N": "0"})["N"]),
            }
        )
    return pd.DataFrame(records)


def list_clients(client):
    response = client.scan(TableName=METRICS_TABLE, ProjectionExpression="client_id")
    clients = sorted({row["client_id"]["S"] for row in response.get("Items", [])})
    return clients


def get_client_data(client, client_id, start, end):
    response = client.query(
        TableName=METRICS_TABLE,
        KeyConditionExpression="#c = :client and #t between :start and :end",
        ExpressionAttributeNames={"#c": "client_id", "#t": "timeblock"},
        ExpressionAttributeValues={
            ":client": {"S": client_id},
            ":start": {"S": start},
            ":end": {"S": end},
        },
        ScanIndexForward=True,
    )
    frame = parse_items(response.get("Items", []))
    if frame.empty:
        return frame
    frame["timeblock"] = pd.to_datetime(frame["timeblock"], utc=True)
    return frame


def create_windows(arr, w):
    return np.array([arr[i : i + w] for i in range(len(arr) - w + 1)])


def generate_signal(df):
    error_rate = df["error_count"] / df["request_count"].clip(lower=1)
    signal = df["request_count"] + ALPHA * error_rate + BETA * df["avg_latency_ms"]
    return signal.values.reshape(-1, 1)


def put_anomaly(client, record):
    client.put_item(
        TableName=ANOMALY_TABLE,
        Item={
            "client_id": {"S": record["client_id"]},
            "timeblock": {"S": record["timeblock"]},
            "anomaly_type": {"S": record["anomaly_type"]},
            "severity": {"S": record["severity"]},
            "score": {"N": str(record["score"])},
            "threshold": {"N": str(record["threshold"])},
        },
    )


def run_client_inference(ddb_client, client_id, frame):
    client_dir = os.path.join(MODEL_DIR, client_id)
    if not os.path.exists(client_dir):
        fallback_candidates = [
            client_id.replace("@xyz.com", "@example.com"),
            client_id.replace("@siemens.com", "@example.com"),
        ]
        for fallback_client_id in fallback_candidates:
            fallback_dir = os.path.join(MODEL_DIR, fallback_client_id)
            if os.path.exists(fallback_dir):
                client_dir = fallback_dir
                break

    if not os.path.exists(client_dir):
        return {"client_id": client_id, "status": "WARMUP", "anomalies": []}

    model = load_model(os.path.join(client_dir, "model.keras"))
    scaler = pickle.load(open(os.path.join(client_dir, "scaler.pkl"), "rb"))
    config = pickle.load(open(os.path.join(client_dir, "config.pkl"), "rb"))

    model_window = int(config.get("window", 24))
    threshold = float(config.get("threshold", 0.0))

    if len(frame) < model_window:
        return {"client_id": client_id, "status": "INSUFFICIENT_DATA", "anomalies": []}

    signal = generate_signal(frame)
    scaled = scaler.transform(signal)
    windows = create_windows(scaled, model_window)

    preds = model.predict(windows, verbose=0)
    errors = np.mean(np.abs(preds - windows), axis=(1, 2))

    eval_df = frame.iloc[model_window - 1 :].copy()
    eval_df["reconstruction_error"] = errors

    anomalies = []
    for _, row in eval_df.iterrows():
        score = float(row["reconstruction_error"])
        if score <= threshold:
            continue
        record = {
            "client_id": client_id,
            "timeblock": row["timeblock"].isoformat(),
            "anomaly_type": "USAGE_PATTERN_DEVIATION",
            "severity": "HIGH" if score > threshold * 1.5 else "MEDIUM",
            "score": score,
            "threshold": threshold,
        }
        anomalies.append(record)
        put_anomaly(ddb_client, record)

    return {
        "client_id": client_id,
        "status": "ANOMALY" if anomalies else "OK",
        "anomalies": anomalies,
    }


def main():
    ddb_client = get_client()
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    start = (now - timedelta(hours=LOOKBACK_HOURS)).isoformat()
    end = now.isoformat()

    outputs = []
    clients = list_clients(ddb_client)
    for client_id in clients:
        frame = get_client_data(ddb_client, client_id, start, end)
        if frame.empty:
            continue
        outputs.append(run_client_inference(ddb_client, client_id, frame))

    print(json.dumps(outputs))


if __name__ == "__main__":
    main()
