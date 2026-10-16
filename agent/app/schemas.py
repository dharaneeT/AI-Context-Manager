
"""Request bodies for agent operations."""
from __future__ import annotations

from pydantic import BaseModel, Field


class RunCreate(BaseModel):
    task_id: int = Field(
        ge=1,
        description="The id of the AgentTask row in Spring Boot",
    )
    project_id: int = Field(ge=1)
    command: str = Field(min_length=3, max_length=4000)
    max_tokens: int | None = Field(
        default=None,
        ge=500,
        le=200000,
    )


class DiscoverRequest(BaseModel):
    project_id: int = Field(ge=1)
    command: str = Field(min_length=3, max_length=4000)
    max_tokens: int | None = Field(
        default=None,
        ge=500,
        le=200000,
    )
    include_markdown: bool = False
