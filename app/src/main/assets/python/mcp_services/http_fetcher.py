from typing import Any, Dict

import requests
from pydantic import BaseModel, Field, HttpUrl


class FetchRequest(BaseModel):
    url: HttpUrl
    timeout_ms: int = Field(default=3000, ge=250, le=15000)
    headers: Dict[str, str] = Field(default_factory=dict)

    class Config:
        extra = "forbid"


class FetchResponse(BaseModel):
    url: str
    status_code: int
    content_type: str
    text_preview: str
    final_url: str


def invoke(payload: Dict[str, Any]) -> Dict[str, Any]:
    request = FetchRequest.parse_obj(payload)
    response = requests.get(
        str(request.url),
        headers=request.headers,
        timeout=request.timeout_ms / 1000.0,
    )
    response.raise_for_status()
    content_type = response.headers.get("Content-Type", "application/octet-stream")
    preview = response.text[:512]
    return FetchResponse(
        url=str(request.url),
        status_code=response.status_code,
        content_type=content_type,
        text_preview=preview,
        final_url=str(response.url),
    ).dict()


def invoke_json(payload_json: str) -> str:
    request = FetchRequest.parse_raw(payload_json)
    result = invoke(request.dict())
    return FetchResponse.parse_obj(result).json()
