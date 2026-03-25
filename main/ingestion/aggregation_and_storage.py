import os
import sys
import requests
from pymongo import MongoClient

CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
MAIN_DIR = os.path.join(CURRENT_DIR, os.pardir)
PROJECT_ROOT = os.path.join(MAIN_DIR, os.pardir)

sys.path.insert(0, str(PROJECT_ROOT))

import logging
import numpy as np
import pandas as pd

from main.utils.helpers import convert_df_to_aggregated__buckets, validate_raw_metrics

from dotenv import load_dotenv
load_dotenv()

DATA_ENDPOINT= os.getenv("INGESTION_ENDPOINT")
DATA_PATH = os.path.join(PROJECT_ROOT, os.getenv("INGESTION_DATA_PATH"))
AGGREGATION_INTERVAL = os.getenv("INGESTION_AGGREGATION_INTERVAL")
MONGO_URI=os.getenv("MONGO_URI")
MONGO_DB_NAME=os.getenv("MONGO_DB_NAME")
MONGO_COLLECTION_NAME=os.getenv("MONGO_COLLECTION_NAME")



# --- Configure Logging ---
logger = logging.getLogger('INGESTION')
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
        mongo_collection = mongo_db[MONGO_COLLECTION_NAME] # Single collection
        logger.info("Successfully connected to MongoDB.")
    except Exception as e:
        logger.error(f"Failed to connect to MongoDB: {e}")
        mongo_client = None
else:
    logger.warning("MONGO_URI not set in .env. MongoDB operations will be skipped.")


def load_data():
    # API get request
    # if DATA_ENDPOINT:
    #     logger.info(f"Attempting to fetch data from API endpoint: {DATA_ENDPOINT}")
    #     try:
    #         response = requests.get(DATA_ENDPOINT, timeout=30)
    #         response.raise_for_status()
            
    #         data = response.json()
            
    #         if not data:
    #             logger.warning("API returned empty data")
            
    #         df = pd.DataFrame(data)
    #         logger.info(f"Successfully loaded data from API. Shape: {df.shape}")
    #         return df
    #     except requests.exceptions.RequestException as e:
    #         logger.error(f"Error fetching data from API endpoint {DATA_ENDPOINT}: {e}")
    #     except ValueError as e: # Catch JSON decoding errors
    #         logger.error(f"Error decoding JSON from API response: {e}")
    # else:
    #     logger.warning("No API endpoint configured")

    # Load from CSV for now -
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


# Get the recent item from aggregated_df & store in DB
def insert_item(aggregated_df: pd.DataFrame):
    aggregated_df['timeblock'] = pd.to_datetime(aggregated_df['timeblock'])

    logger.info(f"Processing {len(aggregated_df)} aggregated records for insertion.")

    inserted_count = 0
    updated_count=0
    error_count=0

    for index, row in aggregated_df.iterrows():
        doc = row.to_dict()

        query = {
            "timeblock": doc["timeblock"],
            "client_id": doc["client_id"]
        }

        try:
            result = mongo_collection.update_one(query, {"$set": doc}, upsert=True)           
            if result.upserted_id:
                logger.debug(f"Inserted new document for timeblock {doc['timeblock']} and client {doc['client_id']}.")
                inserted_count += 1 
            elif result.modified_count > 0:
                logger.debug(f"Updated existing document for timeblock {doc['timeblock']} and client {doc['client_id']}.")
                updated_count += 1 

        except Exception as e:
            logger.error(f"Failed to upsert document for timeblock {doc['timeblock']} and client {doc['client_id']}: {e}")
            error_count += 1
            
    logger.info(f"Database insertion summary: Inserted {inserted_count}, Errors {error_count}, Updated {updated_count}.")


# Load the data
df=load_data()

# Pydantic Validation of raw metrics
df=validate_raw_metrics(df,logger)

# Convert to buckets and Pydantic validation of aggregated metrics
aggregated_df=convert_df_to_aggregated__buckets(df, AGGREGATION_INTERVAL, logger)

insert_item(aggregated_df)

