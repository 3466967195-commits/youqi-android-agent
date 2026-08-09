package com.pocketagent.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class AgentClient {
    interface Listener {
        void onStatus(String status);
        void onAssistant(String text);
        void onError(String message);
        void requestWriteApproval(String path, String oldContent, String newContent,
                                  CompletableFuture<Boolean> decision);
    }

    private static final int MAX_TOOL_ROUNDS = 20;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile String previousResponseId;

    boolean isBusy() {
        return busy.get();
    }

    void resetConversation() {
        previousResponseId = null;
    }

    void send(String prompt, String endpoint, String model, String apiKey,
              ProjectStore project, Listener listener) {
        if (!busy.compareAndSet(false, true)) {
            listener.onError("Agent is already working");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                runAgent(prompt, endpoint, model, apiKey, project, listener);
            } catch (Exception error) {
                listener.onError(error.getMessage() == null ? error.toString() : error.getMessage());
            } finally {
                busy.set(false);
                listener.onStatus("Ready");
            }
        }, "PocketAgent-Network");
        worker.start();
    }

    private void runAgent(String prompt, String endpoint, String model, String apiKey,
                          ProjectStore project, Listener listener) throws Exception {
        listener.onStatus("Thinking");
        JSONObject request = baseRequest(model);
        request.put("input", prompt);
        if (previousResponseId != null) request.put("previous_response_id", previousResponseId);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JSONObject response = post(endpoint, apiKey, request);
            String responseId = response.optString("id", "");
            if (!responseId.isEmpty()) previousResponseId = responseId;

            JSONArray calls = functionCalls(response);
            if (calls.length() == 0) {
                String text = extractText(response);
                listener.onAssistant(text.trim().isEmpty() ? "Completed without a text response." : text);
                return;
            }

            listener.onStatus("Using project tools");
            JSONArray outputs = new JSONArray();
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i);
                String result;
                try {
                    result = executeTool(call, project, listener);
                } catch (Exception toolError) {
                    result = new JSONObject()
                            .put("ok", false)
                            .put("error", toolError.getMessage() == null ? toolError.toString() : toolError.getMessage())
                            .toString();
                }
                outputs.put(new JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", call.getString("call_id"))
                        .put("output", result));
            }
            request = baseRequest(model)
                    .put("previous_response_id", previousResponseId)
                    .put("input", outputs);
        }
        throw new IllegalStateException("Agent exceeded the 20-step tool limit");
    }

    private String executeTool(JSONObject call, ProjectStore project, Listener listener) throws Exception {
        String name = call.getString("name");
        JSONObject arguments = new JSONObject(call.optString("arguments", "{}"));
        switch (name) {
            case "list_files": {
                String path = arguments.optString("path", "");
                return new JSONObject().put("ok", true).put("entries", project.listFiles(path)).toString();
            }
            case "read_file": {
                String path = arguments.getString("path");
                return new JSONObject().put("ok", true).put("path", path)
                        .put("content", project.readFile(path)).toString();
            }
            case "search_files": {
                String query = arguments.getString("query");
                String path = arguments.optString("path", "");
                return new JSONObject().put("ok", true)
                        .put("matches", project.search(query, path)).toString();
            }
            case "write_file": {
                String path = ProjectStore.normalize(arguments.getString("path"));
                String content = arguments.getString("content");
                String oldContent = project.readIfExists(path);
                CompletableFuture<Boolean> decision = new CompletableFuture<>();
                listener.requestWriteApproval(path, oldContent, content, decision);
                boolean approved = decision.get(10, TimeUnit.MINUTES);
                if (!approved) {
                    return new JSONObject().put("ok", false).put("rejected", true)
                            .put("message", "User rejected the proposed write").toString();
                }
                project.writeFile(path, content);
                return new JSONObject().put("ok", true).put("path", path)
                        .put("bytes", content.getBytes(StandardCharsets.UTF_8).length).toString();
            }
            default:
                throw new IllegalArgumentException("Unknown tool: " + name);
        }
    }

    private static JSONObject baseRequest(String model) throws Exception {
        return new JSONObject()
                .put("model", model)
                .put("instructions", "You are a coding agent working in a user-authorized Android project folder. "
                        + "Inspect files before changing them. Use relative paths only. Keep edits focused. "
                        + "Never claim that a write succeeded until the write_file tool confirms it. "
                        + "There is no shell tool in this version. Reply in the user's language.")
                .put("tools", tools());
    }

    private static JSONArray tools() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("list_files", "List direct children of a project directory",
                new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("path",
                                new JSONObject().put("type", "string").put("description", "Relative directory path; empty for root")))
                        .put("required", new JSONArray().put("path")).put("additionalProperties", false)));
        tools.put(tool("read_file", "Read a UTF-8 text file, up to 1 MB",
                new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("path",
                                new JSONObject().put("type", "string").put("description", "Relative file path")))
                        .put("required", new JSONArray().put("path")).put("additionalProperties", false)));
        tools.put(tool("search_files", "Search text across project files",
                new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("query", new JSONObject().put("type", "string"))
                                .put("path", new JSONObject().put("type", "string").put("description", "Relative path; empty for all files")))
                        .put("required", new JSONArray().put("query").put("path")).put("additionalProperties", false)));
        tools.put(tool("write_file", "Create or fully replace a UTF-8 text file after user approval",
                new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("path", new JSONObject().put("type", "string"))
                                .put("content", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("path").put("content")).put("additionalProperties", false)));
        return tools;
    }

    private static JSONObject tool(String name, String description, JSONObject parameters) throws Exception {
        return new JSONObject().put("type", "function").put("name", name)
                .put("description", description).put("parameters", parameters).put("strict", true);
    }

    private static JSONArray functionCalls(JSONObject response) {
        JSONArray calls = new JSONArray();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return calls;
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item != null && "function_call".equals(item.optString("type"))) calls.put(item);
        }
        return calls;
    }

    private static String extractText(JSONObject response) {
        String direct = response.optString("output_text", "");
        if (!direct.trim().isEmpty()) return direct;
        StringBuilder text = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "";
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) {
                    if (text.length() > 0) text.append('\n');
                    text.append(part.optString("text"));
                }
            }
        }
        return text.toString();
    }

    private static JSONObject post(String endpoint, String apiKey, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(180_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line).append('\n');
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String message = response.toString().trim();
            try {
                JSONObject error = new JSONObject(message).optJSONObject("error");
                if (error != null) message = error.optString("message", message);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("API " + status + ": " + message);
        }
        return new JSONObject(response.toString());
    }
}
