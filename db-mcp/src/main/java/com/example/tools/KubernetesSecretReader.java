package com.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class KubernetesSecretReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] USERNAME_KEYS = {"username", "user", "POSTGRES_USER", "MYSQL_USER", "PGUSER"};
    private static final String[] PASSWORD_KEYS = {"password", "POSTGRES_PASSWORD", "MYSQL_ROOT_PASSWORD", "PGPASSWORD"};

    public static Map<String, String> readSecret(String secretName, String namespace)
            throws IOException, InterruptedException {
        List<String> cmd = (namespace != null && !namespace.isBlank())
            ? List.of("kubectl", "get", "secret", secretName, "-n", namespace.trim(), "-o", "json")
            : List.of("kubectl", "get", "secret", secretName, "-o", "json");

        String output = runCommand(cmd);
        JsonNode root = MAPPER.readTree(output);
        JsonNode data = root.path("data");

        Map<String, String> decoded = new LinkedHashMap<>();
        data.fields().forEachRemaining(e ->
            decoded.put(e.getKey(), new String(Base64.getDecoder().decode(e.getValue().asText())))
        );
        return decoded;
    }

    public static String[] extractCredentials(Map<String, String> secretData) {
        String username = null;
        for (String key : USERNAME_KEYS) {
            if (secretData.containsKey(key)) {
                username = secretData.get(key);
                break;
            }
        }

        String password = null;
        for (String key : PASSWORD_KEYS) {
            if (secretData.containsKey(key)) {
                password = secretData.get(key);
                break;
            }
        }

        return new String[]{username, password};
    }

    private static String runCommand(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes());

        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("kubectl timed out fetching secret");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("kubectl get secret failed (exit " + exitCode + "): " + output.trim());
        }
        return output;
    }
}
