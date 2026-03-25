import os
import logging
from typing import List
import pandas as pd
from main.dto.metrics_dto import RawMetrics, AggregatedMetrics, ServiceMetrics
from pydantic import ValidationError
from dotenv import load_dotenv
load_dotenv()


def generate_composite_signal(df: pd.DataFrame, logger:logging.Logger) :
    
    ALPHA=float(os.getenv("COMPOSITE_SIGNAL_ALPHA"))
    BETA=float(os.getenv("COMPOSITE_SIGNAL_BETA"))

    error_rate = df["error_count"] / df["request_count"].clip(lower=1)
    signal = (
        df["request_count"]
        + ALPHA * error_rate
        + BETA * df["avg_latency_ms"]
    )

    logger.info("Composite Signal generated")
    return signal.values.reshape(-1, 1)


def convert_df_to_aggregated__buckets(df: pd.DataFrame, INTERVAL:str, logger:logging.Logger) -> pd.DataFrame:
        
    df["timestamp"] = pd.to_datetime(df["timestamp"])
    df.sort_values(["client_id", "timestamp"], inplace=True)

    # Get aggregation interval
    df['timeblock'] = df['timestamp'].dt.floor(INTERVAL)

    # Aggregate all client data into time blocks
    aggregated_df = df.groupby(['client_id', 'timeblock']).agg(
        request_count=('request_count', 'sum'),
        error_count=('error_count', 'sum'),
        avg_latency_ms=('avg_latency_ms', 'mean')
    ).reset_index()

    logger.info("Raw data aggregated into buckets")

    validated_aggr_data : List[AggregatedMetrics] = []
    validation_errors_count = 0

    for index, row in aggregated_df.iterrows():
        try:
            aggr_metric = AggregatedMetrics(**row.to_dict())
            validated_aggr_data.append(aggr_metric)
        except ValidationError as e:
            logger.warning(f"Validation error for aggregated row {index}: {e}")
            validation_errors_count += 1
        except Exception as e:
            logger.warning(f"Unexpected error processing aggregated row {index}: {e}")
            validation_errors_count += 1

    if validation_errors_count > 0:
        logger.warning(f"Encountered {validation_errors_count} validation errors in raw data. Skipping invalid rows")
        # exit(1)
    else:
        logger.info("All aggregated data rows passed Pydantic validation")

    return aggregated_df



def validate_raw_metrics(df: pd.DataFrame, logger:logging.Logger) -> pd.DataFrame :
        
    validated_raw_data : ServiceMetrics = ServiceMetrics(metrics=[])
    validation_errors_count = 0

    df["timestamp"] = pd.to_datetime(df["timestamp"])

    for index, row in df.iterrows():
        try:
            raw_metric = RawMetrics(**row.to_dict())
            validated_raw_data.metrics.append(raw_metric)
        except ValidationError as e:
            logger.warning(f"Validation error for row {index}: {e}")
            validation_errors_count += 1
        except Exception as e:
            logger.warning(f"Unexpected error processing row {index}: {e}")
            validation_errors_count += 1

    if validation_errors_count > 0:
        logger.warning(f"Encountered {validation_errors_count} validation errors in raw data. Skipping invalid rows")
        # exit(1)
    else:
        logger.info("All raw data rows passed Pydantic validation")

    # Convert validated_raw_data back to a DataFrame
    df_validated = pd.DataFrame([m.dict() for m in validated_raw_data.metrics])

    if df_validated.empty:
        logger.error("No valid raw data remaining after Pydantic validation. Exiting.")
        exit(1)
    return df_validated


def detect_missing_buckets(curr_timebock, bucket_times, expected_count, freq="h"):
    """
    Detect missing time buckets inside the lookback range.
    """
    if len(bucket_times) == 0:
        return []

    end = curr_timebock-pd.Timedelta(hours=1)
    expected_range = pd.date_range(
        end=end,
        periods=expected_count,
        freq=freq
    )

    return sorted(set(expected_range) - set(bucket_times))