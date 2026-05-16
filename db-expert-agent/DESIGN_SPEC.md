# DESIGN_SPEC.md — DB Expert Agent

## Overview

A conversational database expert agent that helps users query and analyze data stored in PostgreSQL and MySQL/MariaDB databases running in a Kubernetes cluster. The agent automatically discovers available databases, inspects their schema, builds optimized SQL queries, executes them via the `db-mcp` MCP server, and explains the results in plain language.

## Example Use Cases

| User asks | Agent does |
|-----------|-----------|
| "What databases are running in the cluster?" | Calls `kubernetes_discover_databases`, lists results |
| "Show me the top 10 customers by revenue" | Discovers DBs → inspects schema → builds SELECT → executes → summarizes |
| "How many orders were placed last week?" | Discovers DBs → identifies orders table → runs aggregation query |
| "Delete all cancelled orders" | Declines: only read-only queries are allowed |
| "Explain what columns the users table has" | Queries `information_schema.columns`, presents schema clearly |

## Tools Required

| Tool | Source | Purpose |
|------|--------|---------|
| `kubernetes_discover_databases` | db-mcp MCP server (Java, stdio) | Discovers PostgreSQL/MySQL services in the k8s cluster |
| `execute_db_query` | db-mcp MCP server (Java, stdio) | Executes read-only SQL queries against discovered databases |

**MCP server**: `db-mcp` — launched via `mvn -q -f <path>/pom.xml exec:java -Dexec.mainClass=com.example.Main`

## Constraints & Safety Rules

- **Read-only only**: The MCP server enforces this — INSERT/UPDATE/DELETE/DDL are rejected at the server level. The agent instruction also explicitly declines such requests before even calling a tool.
- **No invented credentials**: The agent must never hardcode or guess DB credentials. Credentials are resolved from Kubernetes secrets (`{service-name}-root-secret`) by the MCP server.
- **Schema inspection before querying**: The agent must inspect `information_schema` to understand table/column structure before writing non-trivial queries.
- **Row limit**: `execute_db_query` caps results at 500 rows. The agent should add LIMIT clauses proactively.
- **Disambiguation**: If multiple matching databases exist, ask the user which one to target.

## Success Criteria

1. Agent correctly describes its capabilities when asked.
2. Agent refuses write queries (INSERT/UPDATE/DELETE/DDL) gracefully.
3. Agent calls `kubernetes_discover_databases` before attempting to query data.
4. Agent inspects schema before building non-trivial queries.
5. Agent explains query results clearly in plain language.

## Reference Samples

- `data-science` — ADK sample for SQL/code execution patterns (similar workflow: discover → query → explain)
