package com.example.tools;

import io.modelcontextprotocol.spec.McpSchema;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DatabaseQueryTool implements Tool {

    private static final Pattern ALLOWED_START = Pattern.compile(
        "^\\s*(SELECT|WITH|EXPLAIN)\\b.*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern DISALLOWED_KEYWORDS = Pattern.compile(
        "\\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|TRUNCATE|REPLACE|MERGE|CALL|EXEC|EXECUTE|GRANT|REVOKE)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern K8S_DNS = Pattern.compile(
        "^([^.]+)\\.([^.]+)\\.svc\\.cluster\\.local$",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getName() {
        return "execute_db_query";
    }

    @Override
    public String getDescription() {
        return "Execute a read-only SELECT query against a PostgreSQL or MySQL/MariaDB database. "
             + "Credentials are fetched automatically from the Kubernetes secret "
             + "'{service-name}-root-secret' — no username or password required. "
             + "Only SELECT, WITH (CTEs), and EXPLAIN statements are allowed. "
             + "Results are capped at 500 rows with a 30-second timeout.";
    }

    @Override
    public McpSchema.ToolAnnotations getAnnotations() {
        return new McpSchema.ToolAnnotations("Execute DB Query", true, null, null, null, null);
    }

    @Override
    public McpSchema.JsonSchema getInputSchema() {
        return new McpSchema.JsonSchema(
            "object",
            Map.of(
                "type", Map.of(
                    "type", "string",
                    "description", "Database type: 'postgres' or 'mysql'."
                ),
                "host", Map.of(
                    "type", "string",
                    "description", "Database host or Kubernetes service DNS name (e.g. mydb.default.svc.cluster.local). "
                                 + "Service name and namespace are parsed from the DNS name automatically."
                ),
                "port", Map.of(
                    "type", "integer",
                    "description", "Database port. Defaults to 5432 for postgres, 3306 for mysql."
                ),
                "database", Map.of(
                    "type", "string",
                    "description", "Database (schema) name to connect to."
                ),
                "service_name", Map.of(
                    "type", "string",
                    "description", "Kubernetes service name used to look up secret '{name}-root-secret'. "
                                 + "Inferred from host DNS when omitted."
                ),
                "namespace", Map.of(
                    "type", "string",
                    "description", "Kubernetes namespace where the secret lives. Inferred from host DNS when omitted."
                ),
                "query", Map.of(
                    "type", "string",
                    "description", "SQL SELECT query to execute. INSERT/UPDATE/DELETE/DDL are rejected."
                )
            ),
            List.of("type", "host", "database", "query"),
            null, null, null
        );
    }

    @Override
    public Object execute(Map<String, Object> parameters) throws Exception {
        String type     = requireString(parameters, "type");
        String host     = requireString(parameters, "host");
        String database = requireString(parameters, "database");
        String query    = requireString(parameters, "query");
        Object portObj  = parameters.get("port");

        validateQuery(query);

        int port = portObj != null ? ((Number) portObj).intValue() : defaultPort(type);
        String jdbcUrl = buildJdbcUrl(type, host, port, database);
        String[] creds = resolveCredentials(parameters, host);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, creds[0], creds[1])) {
            conn.setReadOnly(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.setMaxRows(500);
                stmt.setQueryTimeout(30);
                try (ResultSet rs = stmt.executeQuery(query)) {
                    return buildResult(rs);
                }
            }
        }
    }

    private String[] resolveCredentials(Map<String, Object> parameters, String host) throws Exception {
        String serviceName = (String) parameters.get("service_name");
        String namespace   = (String) parameters.get("namespace");

        if (serviceName == null || serviceName.isBlank()) {
            Matcher m = K8S_DNS.matcher(host.trim());
            if (m.matches()) {
                serviceName = m.group(1);
                if (namespace == null || namespace.isBlank()) {
                    namespace = m.group(2);
                }
            }
        }

        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException(
                "Cannot determine Kubernetes service name for credential lookup. "
                + "Provide a Kubernetes DNS host (name.namespace.svc.cluster.local) or pass 'service_name'."
            );
        }

        String secretName = serviceName + "-root-secret";
        Map<String, String> secretData = KubernetesSecretReader.readSecret(secretName, namespace);
        String[] creds = KubernetesSecretReader.extractCredentials(secretData);

        if (creds[0] == null) {
            throw new IllegalStateException(
                "No username found in secret '" + secretName + "'. "
                + "Expected one of: username, user, POSTGRES_USER, MYSQL_USER, PGUSER."
            );
        }

        return new String[]{creds[0], creds[1] != null ? creds[1] : ""};
    }

    private void validateQuery(String query) {
        if (!ALLOWED_START.matcher(query).matches()) {
            String preview = query.trim().substring(0, Math.min(60, query.trim().length()));
            throw new IllegalArgumentException(
                "Only SELECT, WITH (CTE), and EXPLAIN queries are allowed. Query started with: " + preview
            );
        }
        if (DISALLOWED_KEYWORDS.matcher(query).find()) {
            throw new IllegalArgumentException(
                "Query contains disallowed keywords (INSERT/UPDATE/DELETE/DDL). Only read-only queries are permitted."
            );
        }
    }

    private int defaultPort(String type) {
        return switch (type.toLowerCase()) {
            case "mysql", "mariadb" -> 3306;
            case "postgres", "postgresql" -> 5432;
            default -> throw new IllegalArgumentException(
                "Unsupported database type: '" + type + "'. Use 'postgres' or 'mysql'."
            );
        };
    }

    private String buildJdbcUrl(String type, String host, int port, String database) {
        return switch (type.toLowerCase()) {
            case "postgres", "postgresql" ->
                String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
            case "mysql", "mariadb" ->
                String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true", host, port, database);
            default -> throw new IllegalArgumentException(
                "Unsupported database type: '" + type + "'. Use 'postgres' or 'mysql'."
            );
        };
    }

    private Map<String, Object> buildResult(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnName(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnName(i), rs.getObject(i));
            }
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("row_count", rows.size());
        result.put("rows", rows);
        return result;
    }

    private String requireString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) {
            throw new IllegalArgumentException("Required parameter missing: " + key);
        }
        return val.toString();
    }
}
