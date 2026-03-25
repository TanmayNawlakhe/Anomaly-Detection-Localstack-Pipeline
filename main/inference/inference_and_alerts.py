import json
import os
import sys
from pymongo import MongoClient
from datetime import datetime, timedelta, timezone
import pickle
from tensorflow.keras.models import load_model

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
MAIN_DIR = os.path.join(CURRENT_DIR, os.pardir)
PROJECT_ROOT = os.path.join(MAIN_DIR, os.pardir)

sys.path.insert(0, str(PROJECT_ROOT))

import logging
import numpy as np
import pandas as pd

from main.utils.helpers import detect_missing_buckets, generate_composite_signal

from dotenv import load_dotenv
load_dotenv()

AGGREGATION_INTERVAL = os.getenv("INGESTION_AGGREGATION_INTERVAL")
MONGO_URI=os.getenv("MONGO_URI")
MONGO_DB_NAME=os.getenv("MONGO_DB_NAME")
MONGO_COLLECTION_NAME=os.getenv("MONGO_COLLECTION_NAME")
SEARCH_WINDOW=int(os.getenv("DETECTION_WINDOW"))
MODEL_WINDOW=int(os.getenv("TRAINING_WINDOW"))
MODEL_DIR=os.getenv("MODEL_DIR")
OUTPUT_DIR=os.getenv("OUTPUT_DIR")

# Compute lookback correctly
REPORTING_WINDOW=SEARCH_WINDOW*24
LOOKBACK_RANGE = REPORTING_WINDOW + MODEL_WINDOW - 1

# --- Configure Logging ---
logger = logging.getLogger('INFERENCE')
logger.setLevel(logging.INFO)

ch = logging.StreamHandler()
formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
ch.setFormatter(formatter)

if not logger.handlers:
    logger.addHandler(ch)

# MongoDB connection
mongo_client = None
mongo_collection = None
if MONGO_URI:
    try:
        mongo_client = MongoClient(MONGO_URI)
        mongo_db = mongo_client[MONGO_DB_NAME]
        mongo_collection = mongo_db[MONGO_COLLECTION_NAME]
        logger.info("Successfully connected to MongoDB.")
    except Exception as e:
        logger.error(f"Failed to connect to MongoDB: {e}")
        mongo_client = None
else:
    logger.warning("MONGO_URI not set in .env. MongoDB operations will be skipped.")

# Window Creation
def create_windows(arr, w):
    return np.array([arr[i:i+w] for i in range(len(arr)-w+1)])

# Create query to fetch data
def create_query():
    curr_timeblock=get_curr_timeblock()

    # Define search range
    START_TIMEBLOCK=curr_timeblock-timedelta(hours=LOOKBACK_RANGE)
    END_TIMEBLOCK=curr_timeblock

    logger.info(f"Fetching data from {START_TIMEBLOCK} to {END_TIMEBLOCK}")
    query = {
        "timeblock": {
            "$gte": START_TIMEBLOCK,
            "$lt": END_TIMEBLOCK
        }
    }
    return query


# Handle missing values
def handle_missed(client_data: pd.DataFrame, client_result: object):
    curr_timeblock=get_curr_timeblock()
    reporting_cutoff = curr_timeblock - pd.Timedelta(hours=REPORTING_WINDOW - 1)

    missing_blocks = detect_missing_buckets(
        curr_timeblock,
        client_data["timeblock"],
        expected_count=LOOKBACK_RANGE,
        freq=AGGREGATION_INTERVAL,
    )

    for miss in missing_blocks:
        if miss >= reporting_cutoff:
            client_result["anomalies"].append({
                "time_block": miss.isoformat(),
                "window_type": AGGREGATION_INTERVAL,
                "anomaly_type": "MISSING_DATA",
                "severity": "HIGH"
            })

# Fetch data from DB
def fetch_data() -> pd.DataFrame:
    query=create_query()
    try:
        cursor = mongo_collection.find(query)
        data = list(cursor)

        df = pd.DataFrame(data)
        df['timeblock'] = pd.to_datetime(df['timeblock'], utc=True)
        df = df.sort_values(by=['client_id', 'timeblock']).reset_index(drop=True)

        total_docs=mongo_collection.count_documents(query)
        logger.info(f"Successfully fetched {total_docs} records for inference.")
        return df

    except Exception as e:
        logger.error(f"Error fetching data from MongoDB: {e}")
        exit(1)


def process_client_data(client_id: str, client_data: pd.DataFrame, output:list):
    curr_timeblock=get_curr_timeblock()
    reporting_cutoff = curr_timeblock - pd.Timedelta(hours=REPORTING_WINDOW - 1)

    client_result = {
        "client_id": client_id,
        "status": "OK",
        "anomalies": []
    }

    # Load model artifacts
    client_dir = os.path.join(MODEL_DIR, client_id)
    if not os.path.exists(client_dir):
        client_result["status"] = "WARMUP"
        output.append(client_result)
        return

    model = load_model(f"{client_dir}/model.keras")
    scaler = pickle.load(open(f"{client_dir}/scaler.pkl", "rb"))
    config = pickle.load(open(f"{client_dir}/config.pkl", "rb"))

    MODEL_WINDOW = config["window"]
    threshold = config["threshold"]

    if len(client_data) < MODEL_WINDOW:
        client_result["status"] = "INSUFFICIENT_DATA"
        logger.warning(f"Client {client_id}: INSUFFICIENT_DATA. Needed {MODEL_WINDOW} records, got {len(client_data)}.")
        output.append(client_result)
        return
    
    # Handle missing timeblocks
    handle_missed(client_data, client_result)

    signal = generate_composite_signal(client_data, logger)
    logger.info(f"INFERENCE - Client {client_id}: Signal stats - Mean: {np.mean(signal):.4f}, Std: {np.std(signal):.4f}, Min: {np.min(signal):.4f}, Max: {np.max(signal):.4f}")

    scaled = scaler.transform(signal)
    windows = create_windows(scaled, MODEL_WINDOW)

    preds = model.predict(windows, verbose=0)
    errors = np.mean(np.abs(preds - windows), axis=(1, 2))

    error_df = client_data.iloc[MODEL_WINDOW - 1:].copy()
    error_df["reconstruction_error"] = errors

    for _, row in error_df.iterrows():
        if row["timeblock"] < reporting_cutoff:
            continue
        if row["reconstruction_error"] > threshold:
            client_result["anomalies"].append({
                "time_block": row["timeblock"].isoformat(),
                "window_type": AGGREGATION_INTERVAL,
                "anomaly_type": "USAGE_PATTERN_DEVIATION",
                "score": float(row["reconstruction_error"]),
                "threshold": float(threshold),
                "severity": (
                    "HIGH"
                    if row["reconstruction_error"] > threshold * 1.5
                    else "MEDIUM"
                )
            })

    if client_result["anomalies"]:
        client_result["status"] = "ANOMALY"

    output.append(client_result)


def get_curr_timeblock():
    # curr_time=datetime.now(timezone.utc)
    curr_time = datetime(2024, 12, 31, 0, 0, tzinfo=timezone.utc)
    curr_timeblock=pd.Timestamp(curr_time).floor(AGGREGATION_INTERVAL)
    return curr_timeblock


if __name__ == "__main__":
    output=[]
    df = fetch_data()

    unique_clients = df['client_id'].unique()
    logger.info(f"Found {len(unique_clients)} clients in the fetched data")

    for client_id, cdf in df.groupby("client_id"):
        cdf = cdf.sort_values('timeblock').reset_index(drop=True)
        process_client_data(client_id, cdf, output)

    with open(f"{OUTPUT_DIR}/anomalies.json", "w") as f:
                json.dump(output, f, indent=2, default=str)