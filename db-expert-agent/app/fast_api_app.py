# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import os
import logging as stdlib_logging

import google.auth
import google.auth.exceptions
from a2a.server.apps import A2AFastAPIApplication
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import AgentCapabilities, AgentCard, AgentSkill
from google.adk.a2a.executor.a2a_agent_executor import A2aAgentExecutor
from google.adk.artifacts import GcsArtifactService, InMemoryArtifactService
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService

from app.agent import app as adk_app
from app.app_utils.telemetry import setup_telemetry
from app.app_utils.typing import Feedback

setup_telemetry()

_gcp_logger = None
try:
    from google.cloud import logging as google_cloud_logging
    _, _project_id = google.auth.default()
    _logging_client = google_cloud_logging.Client()
    _gcp_logger = _logging_client.logger(__name__)
except (google.auth.exceptions.DefaultCredentialsError, Exception):
    stdlib_logging.warning("GCP credentials not available — Cloud Logging disabled")

logs_bucket_name = os.environ.get("LOGS_BUCKET_NAME")
artifact_service = (
    GcsArtifactService(bucket_name=logs_bucket_name)
    if logs_bucket_name
    else InMemoryArtifactService()
)

runner = Runner(
    app=adk_app,
    artifact_service=artifact_service,
    session_service=InMemorySessionService(),
)

request_handler = DefaultRequestHandler(
    agent_executor=A2aAgentExecutor(runner=runner),
    task_store=InMemoryTaskStore(),
)

agent_card = AgentCard(
    name=adk_app.root_agent.name,
    description=adk_app.root_agent.description or "Database expert agent for Kubernetes clusters",
    url=os.getenv("APP_URL", "http://0.0.0.0:8000"),
    version=os.getenv("AGENT_VERSION", "0.1.0"),
    capabilities=AgentCapabilities(streaming=True),
    default_input_modes=["text/plain"],
    default_output_modes=["text/plain"],
    skills=[
        AgentSkill(
            id="query_databases",
            name="Query Databases",
            description="Discover and query PostgreSQL and MySQL/MariaDB databases running in a Kubernetes cluster.",
            tags=["database", "sql", "kubernetes"],
            examples=[
                "What databases are available?",
                "Show me all tables in the orders database.",
                "How many users signed up last week?",
            ],
        )
    ],
)

app = A2AFastAPIApplication(
    agent_card=agent_card,
    http_handler=request_handler,
).build(
    title="db-expert-agent",
    description="API for interacting with the Agent db-expert-agent",
)


@app.post("/feedback")
def collect_feedback(feedback: Feedback) -> dict[str, str]:
    """Collect and log feedback.

    Args:
        feedback: The feedback data to log

    Returns:
        Success message
    """
    if _gcp_logger:
        _gcp_logger.log_struct(feedback.model_dump(), severity="INFO")
    else:
        stdlib_logging.info("feedback: %s", feedback.model_dump())
    return {"status": "success"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
