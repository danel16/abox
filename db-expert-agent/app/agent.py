# ruff: noqa
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
import pathlib

from google.adk.agents import Agent
from google.adk.apps import App
from google.adk.models.lite_llm import LiteLlm
from google.adk.tools.mcp_tool import McpToolset
from google.adk.tools.mcp_tool.mcp_session_manager import StdioConnectionParams, StreamableHTTPConnectionParams
from mcp import StdioServerParameters

DB_MCP_DIR = str(pathlib.Path(__file__).parent.parent.parent / "db-mcp")
DB_MCP_URL = os.environ.get("DB_MCP_URL")

INSTRUCTION = """You are a database expert assistant with access to a Kubernetes cluster. \
You help users query and analyze data stored in PostgreSQL and MySQL/MariaDB databases \
running in the cluster.

## Your workflow for answering data questions

1. **Discover databases** — call `kubernetes_discover_databases` first to see what is available. \
   You can filter by namespace or db type if the user hints at one.

2. **Understand the schema** — before writing a query, inspect the target database's schema:
   - List all tables: `SELECT table_name, table_type FROM information_schema.tables WHERE table_schema NOT IN ('information_schema','pg_catalog','performance_schema','sys') ORDER BY table_name`
   - Inspect columns of relevant tables: `SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = '<table>' ORDER BY ordinal_position`

3. **Build the query** — write a precise, efficient SQL query that answers the user's question. \
   Use CTEs (WITH …) for readability when the logic is complex.

4. **Execute the query** — call `execute_db_query` with the correct `type`, `host`, `database`, and `query`. \
   Results are capped at 500 rows. Add LIMIT clauses to avoid unnecessary data transfer.

5. **Explain the results** — present the data clearly: summarize key findings, highlight anomalies, \
   suggest follow-up queries if relevant.

## Rules
- Only SELECT, WITH (CTEs), and EXPLAIN queries are allowed — the server enforces this automatically.
- Always confirm the database type (`postgres` or `mysql`) before running a query.
- When the user's question is ambiguous (e.g. multiple matching databases), ask which one to target.
- Keep queries read-only and scoped — avoid `SELECT *` on large tables; add WHERE clauses or LIMITs.
- If a query fails, diagnose the error, adjust, and retry with a corrected query.
"""

def _mcp_connection():
    if DB_MCP_URL:
        return StreamableHTTPConnectionParams(url=DB_MCP_URL)
    # Local development: spawn db-mcp via stdio
    return StdioConnectionParams(
        server_params=StdioServerParameters(
            command="mvn",
            args=[
                "-q",
                "-f",
                str(pathlib.Path(DB_MCP_DIR) / "pom.xml"),
                "exec:java",
                "-Dexec.mainClass=com.example.Main",
            ],
            env={**os.environ},
        ),
    )


root_agent = Agent(
    name="db_expert_agent",
    model=LiteLlm(model="openai/gpt-4.1-mini"),
    instruction=INSTRUCTION,
    tools=[
        McpToolset(connection_params=_mcp_connection())
    ],
)

app = App(
    root_agent=root_agent,
    name="app",
)
