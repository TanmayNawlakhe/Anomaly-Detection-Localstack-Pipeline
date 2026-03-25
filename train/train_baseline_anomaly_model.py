import os
import sys

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.join(CURRENT_DIR, os.pardir)

sys.path.insert(0, str(PROJECT_ROOT))

import pickle
import logging
import numpy as np
import pandas as pd

from sklearn.preprocessing import StandardScaler
from tensorflow.keras.models import Model
from tensorflow.keras.layers import Input, LSTM, RepeatVector, TimeDistributed, Dense
from tensorflow.keras.callbacks import EarlyStopping
from main.utils.helpers import convert_df_to_aggregated__buckets, generate_composite_signal, validate_raw_metrics

from dotenv import load_dotenv
load_dotenv()

DATA_PATH = os.path.join(PROJECT_ROOT, os.getenv("TRAINING_DATA_PATH"))
MODEL_DIR = os.path.join(PROJECT_ROOT, os.getenv("MODEL_DIR"))
WINDOW = int(os.getenv("TRAINING_WINDOW"))
AGGREGATION_INTERVAL = os.getenv("TRAINING_AGGREGATION_INTERVAL")

os.makedirs(MODEL_DIR, exist_ok=True)

# --- Configure Logging ---
logger = logging.getLogger('Trainer')
logger.setLevel(logging.INFO)

ch = logging.StreamHandler()
formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
ch.setFormatter(formatter)

if not logger.handlers:
    logger.addHandler(ch)

# Create windows for training
def create_windows(arr, w):
    return np.array([arr[i:i+w] for i in range(len(arr)-w+1)])

# Autoencoder Model creation
def build_ae(w):
    i = Input(shape=(w, 1))
    x = LSTM(64, activation="relu")(i)
    x = RepeatVector(w)(x)
    o = LSTM(64, activation="relu", return_sequences=True)(x)
    o = TimeDistributed(Dense(1))(o)
    m = Model(i, o)
    m.compile(optimizer="adam", loss="mae")
    return m

def load_data() :
    try:
        df = pd.read_csv(DATA_PATH)
        logger.info(f"Successfully loaded data from {DATA_PATH}. Shape: {df.shape}")
        return df
    except FileNotFoundError:
        logger.error(f"Error: Data file not found at {DATA_PATH}. Please check the path and ensure the file exists")
        exit(1)
    except Exception as e:
        logger.error(f"An error occurred while reading the data: {e}")
        exit(1)

# Load the data
df=load_data()

# Pydantic Validation of raw metrics
df=validate_raw_metrics(df,logger)

# Convert to buckets and Pydantic validation of aggregated metrics
aggregated_df=convert_df_to_aggregated__buckets(df, AGGREGATION_INTERVAL, logger)

# Handle missing values
aggregated_df.dropna(inplace=True)

logger.info("Starting training process")

for client, client_aggregated_df in aggregated_df.groupby("client_id"):
    logger.info(f"Training model for {client}")

    client_aggregated_df = client_aggregated_df.sort_values("timeblock")

    # Generate composite signal
    signal = generate_composite_signal(client_aggregated_df, logger)
    logger.info(f"TRAINING - Client {client}: Signal stats - Mean: {np.mean(signal):.4f}, Std: {np.std(signal):.4f}, Min: {np.min(signal):.4f}, Max: {np.max(signal):.4f}")

    scaler = StandardScaler()
    scaled = scaler.fit_transform(signal)

    windows = create_windows(scaled, WINDOW)
    if len(windows) < 32:
        logger.warning(f"Not enough aggregated data points for client {client}, skipping. Need at least 32 windows, got {len(windows)}")
        continue
    logger.info(f"Created {windows.shape[0]} windows of size {WINDOW} for client {client}")

    model = build_ae(WINDOW)
    model.fit(
        windows, windows,
        epochs=40,
        batch_size=32,
        validation_split=0.2,
        callbacks=[EarlyStopping(patience=3, restore_best_weights=True)],
        verbose=0
    )

    # Evaluate the model
    preds = model.predict(windows)
    errors = np.mean(np.abs(preds - windows), axis=(1,2))
    threshold = np.percentile(errors, 99.5)

    client_dir = os.path.join(MODEL_DIR, client)
    os.makedirs(client_dir, exist_ok=True)

    model.save(f"{client_dir}/model.keras")
    pickle.dump(scaler, open(f"{client_dir}/scaler.pkl", "wb"))
    pickle.dump(
        {"window": WINDOW, "threshold": threshold},
        open(f"{client_dir}/config.pkl", "wb")
    )

    logger.info(f"Saved model for {client} | threshold={threshold:.3f}")

logger.info("Training complete")