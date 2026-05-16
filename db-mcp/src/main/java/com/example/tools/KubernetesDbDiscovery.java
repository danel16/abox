package com.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class KubernetesDbDiscovery implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<Integer, String> PORT_TO_DB_TYPE = new HashMap<>();
    static {
        PORT_TO_DB_TYPE.put(5432, "PostgreSQL");
        PORT_TO_DB_TYPE.put(5433, "PostgreSQL");
        PORT_TO_DB_TYPE.put(3306, "MySQL/MariaDB");
        PORT_TO_DB_TYPE.put(3307, "MySQL/MariaDB");
    }

    private static final Set<String> DB_KEYWORDS = new HashSet<>(Arrays.asList(
        "postgres", "postgresql", "mysql", "mariadb", "psql", "db", "database"
    ));

    @Override
    public String getName() {
        return "kubernetes_discover_databases";
    }

    @Override
    public String getDescription() {
        return "Search for relational database instances (PostgreSQL, MySQL/MariaDB) running in the "
             + "Kubernetes cluster by inspecting services, their ports, and labels. Returns discovered "
             + "databases with type, namespace, host, and port details.";
    }

    @Override
    public McpSchema.ToolAnnotations getAnnotations() {
        return new McpSchema.ToolAnnotations("K8s DB Discovery", true, null, null, null, null);
    }

    @Override
    public McpSchema.JsonSchema getInputSchema() {
        return new McpSchema.JsonSchema(
            "object",
            Map.of(
                "namespace", Map.of(
                    "type", "string",
                    "description", "Kubernetes namespace to search. Omit or leave empty to search all namespaces."
                ),
                "type_filter", Map.of(
                    "type", "string",
                    "description", "Filter by database type: 'postgres' or 'mysql'."
                )
            ),
            List.of(),
            null, null, null
        );
    }

    @Override
    public Object execute(Map<String, Object> parameters) throws Exception {
        String namespace = (String) parameters.get("namespace");
        String typeFilter = (String) parameters.get("type_filter");

        List<String> cmd = new ArrayList<>(Arrays.asList("kubectl", "get", "services"));
        if (namespace != null && !namespace.isBlank()) {
            cmd.add("-n");
            cmd.add(namespace.trim());
        } else {
            cmd.add("--all-namespaces");
        }
        cmd.add("-o");
        cmd.add("json");

        String output = runCommand(cmd);
        JsonNode root = MAPPER.readTree(output);
        JsonNode items = root.path("items");

        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode svc : items) {
            JsonNode metadata = svc.path("metadata");
            JsonNode spec = svc.path("spec");

            String name = metadata.path("name").asText();
            String svcNamespace = metadata.path("namespace").asText();
            JsonNode labelsNode = metadata.path("labels");

            List<String> detectedTypes = new ArrayList<>();
            List<Map<String, Object>> portsList = new ArrayList<>();

            for (JsonNode port : spec.path("ports")) {
                int portNum = port.path("port").asInt();
                String dbType = PORT_TO_DB_TYPE.get(portNum);
                if (dbType != null && !detectedTypes.contains(dbType)) {
                    detectedTypes.add(dbType);
                }
                Map<String, Object> portInfo = new LinkedHashMap<>();
                portInfo.put("port", portNum);
                if (!port.path("name").isMissingNode()) {
                    portInfo.put("name", port.path("name").asText());
                }
                if (dbType != null) {
                    portInfo.put("detected_type", dbType);
                }
                portsList.add(portInfo);
            }

            boolean nameMatch = DB_KEYWORDS.stream().anyMatch(kw -> name.toLowerCase().contains(kw));
            boolean labelMatch = false;
            if (!labelsNode.isMissingNode()) {
                Iterator<Map.Entry<String, JsonNode>> fields = labelsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String combined = (entry.getKey() + " " + entry.getValue().asText()).toLowerCase();
                    if (DB_KEYWORDS.stream().anyMatch(combined::contains)) {
                        labelMatch = true;
                        break;
                    }
                }
            }

            // Only include services that look like a relational DB
            if (detectedTypes.isEmpty() && !nameMatch && !labelMatch) {
                continue;
            }

            String primaryType = detectedTypes.isEmpty() ? inferTypeFromName(name) : detectedTypes.get(0);

            // Skip if we couldn't identify a relational DB type at all
            if (primaryType.equals("Unknown")) {
                continue;
            }

            if (typeFilter != null && !typeFilter.isBlank()) {
                if (!primaryType.toLowerCase().contains(typeFilter.toLowerCase())
                        && !name.toLowerCase().contains(typeFilter.toLowerCase())) {
                    continue;
                }
            }

            Map<String, Object> dbInfo = new LinkedHashMap<>();
            dbInfo.put("name", name);
            dbInfo.put("namespace", svcNamespace);
            dbInfo.put("type", primaryType);
            dbInfo.put("host", name + "." + svcNamespace + ".svc.cluster.local");
            if (!portsList.isEmpty()) {
                dbInfo.put("primary_port", portsList.get(0).get("port"));
            }
            dbInfo.put("ports", portsList);

            String clusterIP = spec.path("clusterIP").asText(null);
            if (clusterIP != null && !clusterIP.equals("None") && !clusterIP.isBlank()) {
                dbInfo.put("cluster_ip", clusterIP);
            }

            if (!labelsNode.isMissingNode()) {
                Map<String, String> labels = new LinkedHashMap<>();
                labelsNode.fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
                if (!labels.isEmpty()) {
                    dbInfo.put("labels", labels);
                }
            }

            results.add(dbInfo);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", results.size());
        response.put("databases", results);
        return response;
    }

    private String inferTypeFromName(String name) {
        String lower = name.toLowerCase();
        if (lower.contains("postgres") || lower.contains("psql")) return "PostgreSQL";
        if (lower.contains("mysql"))   return "MySQL";
        if (lower.contains("mariadb")) return "MariaDB";
        return "Unknown";
    }

    private String runCommand(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read output before waitFor to avoid deadlock on large stdout buffers
        String output = new String(process.getInputStream().readAllBytes());

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("kubectl command timed out after 30 seconds");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("kubectl failed (exit " + exitCode + "): " + output);
        }
        return output;
    }
}
