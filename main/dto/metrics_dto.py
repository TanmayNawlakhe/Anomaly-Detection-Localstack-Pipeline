from typing import List
from pydantic import Field, BaseModel, validator
from datetime import datetime

# timestamp,client_id,request_count,success_count,error_count,avg_latency_ms,p95_latency_ms,payload_kb
class RawMetrics(BaseModel):
    timestamp: datetime = Field(..., description="Metric input timestamp")
    client_id: str = Field(..., description="Metric Client name or address")
    request_count: float = Field(..., description="Metric request count")
    success_count: int = Field(..., description="Request success count")
    error_count: int = Field(..., description="Request error count")
    avg_latency_ms: float = Field(..., description="Metric latency in milliseconds")
    p95_latency_ms: float = Field(description="p95 latency in milliseconds")
    payload_kb: float = Field(description="Payload in kilobytes")


class ServiceMetrics(BaseModel):
    metrics: List[RawMetrics]


# timeblock,client_id,request_count,error_count,avg_latency_ms
class AggregatedMetrics(BaseModel):
    timeblock: datetime = Field(..., description="Metric input timeblock")
    client_id: str = Field(..., description="Metric Client name or address")
    request_count: float = Field(..., description="Metric request count")
    error_count: int = Field(..., description="Request error count")
    avg_latency_ms: float = Field(..., description="Metric latency in milliseconds")

    @validator('error_count')
    def error_count_less_than_request_count(cls, v, values):
        if 'request_count' in values and v > values['request_count']:
            raise ValueError('error_count cannot be greater than request_count')
        return v