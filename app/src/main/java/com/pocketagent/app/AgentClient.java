package com.youqi.studio;

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
        void requestCommandApproval(String command, String workingDirectory,
                                    CompletableFuture<Boolean> decision);
        void onToolCall(String name, String detail);
        void onCommandResult(String command, String result);
    }

    private static final int MAX_TOOL_ROUNDS = 30;
    private static final String BASE_SYSTEM_PROMPT =
            "You are a coding agent running on the user's Android phone. Work autonomously toward the requested task. "
                    + "Inspect the project before editing. Use relative paths only. Use project file tools for reliable edits. "
                    + "Use run_command for git, search, builds, tests, scripts, and other terminal work when available. "
                    + "Never claim that an action succeeded before its tool result confirms it. Keep changes focused. "
                    + "Reply in the user's language and summarize completed work and verification.";

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final TermuxBridge termux;
    private final McpManager mcp;
    private volatile String personaPrompt = "";
    private JSONArray responsesHistory = new JSONArray();
    private JSONArray chatHistory = new JSONArray();

    AgentClient(TermuxBridge termux, McpManager mcp) {
        this.termux = termux;
        this.mcp = mcp;
    }

    boolean isBusy() {
        return busy.get();
    }

    void setPersonaPrompt(String prompt) {
        personaPrompt = prompt == null ? "" : prompt.trim();
        resetConversation();
    }

    private String systemPrompt() {
        return personaPrompt.isEmpty() ? BASE_SYSTEM_PROMPT : BASE_SYSTEM_PROMPT + "\n\n" + personaPrompt;
    }

    synchronized void resetConversation() {
        responsesHistory = new JSONArray();
        chatHistory = new JSONArray();
    }

    void send(String prompt, String configuredEndpoint, String model, String apiKey,
              ProjectStore project, Listener listener) {
        if (!busy.compareAndSet(false, true)) {
            listener.onError("Agent is already working");
            return;
        }
        new Thread(() -> {
            try {
                runAuto(prompt, configuredEndpoint, model, apiKey, project, listener);
            } catch (Exception error) {
                listener.onError(error.getMessage() == null ? error.toString() : error.getMessage());
            } finally {
                busy.set(false);
                listener.onStatus("Ready");
            }
        }, "PocketAgent-Network").start();
    }

    private void runAuto(String prompt, String configuredEndpoint, String model, String apiKey,
                         ProjectStore project, Listener listener) throws Exception {
        Endpoints endpoints = Endpoints.from(configuredEndpoint);
        if (endpoints.chatOnly) {
            listener.onStatus("Chat Completions");
            runChat(prompt, endpoints.chat, model, apiKey, project, listener);
            return;
        }
        listener.onStatus("Responses API");
        try {
            runResponses(prompt, endpoints.responses, model, apiKey, project, listener);
        } catch (ApiException error) {
            if (!error.isResponsesCompatibilityError()) throw error;
            responsesHistory = new JSONArray();
            listener.onStatus("Falling back to Chat Completions");
            runChat(prompt, endpoints.chat, model, apiKey, project, listener);
        }
    }

    private synchronized void runResponses(String prompt, String endpoint, String model, String apiKey,
                                           ProjectStore project, Listener listener) throws Exception {
        responsesHistory.put(new JSONObject().put("role", "user").put("content", prompt));
        JSONObject request = responsesRequest(model).put("input", responsesHistory);
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JSONObject response = post(endpoint, apiKey, request);
            JSONArray output = response.optJSONArray("output");
            if (output != null) {
                for (int i = 0; i < output.length(); i++) responsesHistory.put(output.get(i));
            }
            JSONArray calls = responseFunctionCalls(response);
            if (calls.length() == 0) {
                emitFinal(extractResponsesText(response), listener);
                return;
            }
            listener.onStatus("Using tools");
            JSONArray outputs = executeCalls(calls, project, listener, false);
            for (int i = 0; i < outputs.length(); i++) responsesHistory.put(outputs.get(i));
            trimResponsesHistory();
            request = responsesRequest(model).put("input", responsesHistory);
        }
        throw new IllegalStateException("Agent exceeded the 30-step tool limit");
    }

    private synchronized void runChat(String prompt, String endpoint, String model, String apiKey,
                                      ProjectStore project, Listener listener) throws Exception {
        if (chatHistory.length() == 0) {
            chatHistory.put(new JSONObject().put("role", "system").put("content", systemPrompt()));
        }
        chatHistory.put(new JSONObject().put("role", "user").put("content", prompt));
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JSONObject request = new JSONObject()
                    .put("model", model)
                    .put("messages", chatHistory)
                    .put("tools", chatTools());
            JSONObject response = post(endpoint, apiKey, request);
            JSONArray choices = response.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new IllegalStateException("Chat Completions response contains no choices");
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            chatHistory.put(message);
            JSONArray toolCalls = message.optJSONArray("tool_calls");
            if (toolCalls == null || toolCalls.length() == 0) {
                emitFinal(message.optString("content", ""), listener);
                trimChatHistory();
                return;
            }
            listener.onStatus("Using tools");
            JSONArray normalized = normalizeChatCalls(toolCalls);
            JSONArray outputs = executeCalls(normalized, project, listener, true);
            for (int i = 0; i < outputs.length(); i++) chatHistory.put(outputs.getJSONObject(i));
        }
        throw new IllegalStateException("Agent exceeded the 30-step tool limit");
    }

    private JSONArray executeCalls(JSONArray calls, ProjectStore project, Listener listener,
                                   boolean chatFormat) throws Exception {
        JSONArray outputs = new JSONArray();
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.getJSONObject(i);
            String result;
            try {
                result = executeTool(call, project, listener);
            } catch (Exception toolError) {
                result = new JSONObject().put("ok", false)
                        .put("error", toolError.getMessage() == null ? toolError.toString() : toolError.getMessage())
                        .toString();
            }
            if (chatFormat) {
                outputs.put(new JSONObject().put("role", "tool")
                        .put("tool_call_id", call.getString("call_id")).put("content", result));
            } else {
                outputs.put(new JSONObject().put("type", "function_call_output")
                        .put("call_id", call.getString("call_id")).put("output", result));
            }
        }
        return outputs;
    }

    private String executeTool(JSONObject call, ProjectStore project, Listener listener) throws Exception {
        String name = call.getString("name");
        JSONObject arguments = new JSONObject(call.optString("arguments", "{}"));
        listener.onToolCall(name, toolDetail(name, arguments));
        switch (name) {
            case "list_files":
                return new JSONObject().put("ok", true)
                        .put("entries", project.listFiles(arguments.optString("path", ""))).toString();
            case "read_file": {
                String path = arguments.getString("path");
                return new JSONObject().put("ok", true).put("path", path)
                        .put("content", project.readFile(path)).toString();
            }
            case "search_files":
                return new JSONObject().put("ok", true)
                        .put("matches", project.search(arguments.getString("query"),
                                arguments.optString("path", ""))).toString();
            case "write_file": {
                String path = ProjectStore.normalize(arguments.getString("path"));
                String content = arguments.getString("content");
                String oldContent = project.readIfExists(path);
                CompletableFuture<Boolean> decision = new CompletableFuture<>();
                listener.requestWriteApproval(path, oldContent, content, decision);
                if (!decision.get(10, TimeUnit.MINUTES)) {
                    return new JSONObject().put("ok", false).put("rejected", true)
                            .put("message", "User rejected the proposed write").toString();
                }
                project.writeFile(path, content);
                return new JSONObject().put("ok", true).put("path", path)
                        .put("bytes", content.getBytes(StandardCharsets.UTF_8).length).toString();
            }
            case "run_command": {
                String command = arguments.getString("command");
                String path = ProjectStore.normalize(arguments.optString("path", ""));
                CompletableFuture<Boolean> decision = new CompletableFuture<>();
                listener.requestCommandApproval(command, path, decision);
                if (!decision.get(10, TimeUnit.MINUTES)) {
                    return new JSONObject().put("ok", false).put("rejected", true)
                            .put("message", "User rejected command execution").toString();
                }
                String result = termux.run(command, project.getTreeUri(), path);
                listener.onCommandResult(command, result);
                return result;
            }
            default:
                // Route to MCP if prefixed
                if (name.startsWith("mcp__") && mcp != null) {
                    return mcp.callTool(name, arguments);
                }
                throw new IllegalArgumentException("Unknown tool: " + name);
        }
    }

    private static String toolDetail(String name, JSONObject arguments) {
        switch (name) {
            case "list_files": return arguments.optString("path", ".");
            case "read_file":
            case "write_file": return arguments.optString("path", "");
            case "search_files":
                return arguments.optString("query", "") + "  " + arguments.optString("path", ".");
            case "run_command": return "$ " + arguments.optString("command", "");
            default: return "";
        }
    }

    private JSONObject responsesRequest(String model) throws Exception {
        return new JSONObject().put("model", model).put("instructions", systemPrompt())
                .put("tools", responseTools());
    }

    private JSONArray responseTools() throws Exception {
        JSONArray result = new JSONArray();
        JSONArray definitions = toolDefinitions();
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject definition = definitions.getJSONObject(i);
            result.put(new JSONObject().put("type", "function")
                    .put("name", definition.getString("name"))
                    .put("description", definition.getString("description"))
                    .put("parameters", definition.getJSONObject("parameters"))
                    .put("strict", true));
        }
        return result;
    }

    private JSONArray chatTools() throws Exception {
        JSONArray result = new JSONArray();
        JSONArray definitions = toolDefinitions();
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject definition = definitions.getJSONObject(i);
            result.put(new JSONObject().put("type", "function").put("function", definition));
        }
        return result;
    }

    private JSONArray toolDefinitions() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(definition("list_files", "List direct children of a project directory",
                objectSchema(new JSONObject().put("path", stringSchema("Relative directory path; empty for root")),
                        new JSONArray().put("path"))));
        tools.put(definition("read_file", "Read a UTF-8 project file, up to 1 MB",
                objectSchema(new JSONObject().put("path", stringSchema("Relative file path")),
                        new JSONArray().put("path"))));
        tools.put(definition("search_files", "Search text across project files",
                objectSchema(new JSONObject()
                                .put("query", stringSchema("Text to search for"))
                                .put("path", stringSchema("Relative path; empty for all files")),
                        new JSONArray().put("query").put("path"))));
        tools.put(definition("write_file", "Create or fully replace a UTF-8 file after user approval",
                objectSchema(new JSONObject()
                                .put("path", stringSchema("Relative file path"))
                                .put("content", stringSchema("Complete new file content")),
                        new JSONArray().put("path").put("content"))));
        tools.put(definition("run_command", "Run a shell command in Termux after user approval",
                objectSchema(new JSONObject()
                                .put("command", stringSchema("Shell command to run"))
                                .put("path", stringSchema("Relative working directory; empty for project root")),
                        new JSONArray().put("command").put("path"))));
        // Merge MCP tools from enabled servers
        if (mcp != null) {
            for (McpServer.McpTool mcpTool : mcp.getAllTools()) {
                tools.put(definition(mcpTool.name, mcpTool.description, mcpTool.inputSchema));
            }
        }
        return tools;
    }

    private static JSONObject definition(String name, String description, JSONObject parameters) throws Exception {
        return new JSONObject().put("name", name).put("description", description)
                .put("parameters", parameters).put("strict", true);
    }

    private static JSONObject objectSchema(JSONObject properties, JSONArray required) throws Exception {
        return new JSONObject().put("type", "object").put("properties", properties)
                .put("required", required).put("additionalProperties", false);
    }

    private static JSONObject stringSchema(String description) throws Exception {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private static JSONArray responseFunctionCalls(JSONObject response) {
        JSONArray calls = new JSONArray();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return calls;
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item != null && "function_call".equals(item.optString("type"))) calls.put(item);
        }
        return calls;
    }

    private static JSONArray normalizeChatCalls(JSONArray toolCalls) throws Exception {
        JSONArray calls = new JSONArray();
        for (int i = 0; i < toolCalls.length(); i++) {
            JSONObject item = toolCalls.getJSONObject(i);
            JSONObject function = item.getJSONObject("function");
            calls.put(new JSONObject().put("call_id", item.getString("id"))
                    .put("name", function.getString("name"))
                    .put("arguments", function.optString("arguments", "{}")));
        }
        return calls;
    }

    private static String extractResponsesText(JSONObject response) {
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

    private static void emitFinal(String text, Listener listener) {
        listener.onAssistant(text == null || text.trim().isEmpty()
                ? "Completed without a text response." : text);
    }

    private synchronized void trimChatHistory() throws Exception {
        if (chatHistory.length() <= 60) return;
        JSONArray trimmed = new JSONArray();
        trimmed.put(chatHistory.getJSONObject(0));
        for (int i = Math.max(1, chatHistory.length() - 40); i < chatHistory.length(); i++) {
            trimmed.put(chatHistory.getJSONObject(i));
        }
        chatHistory = trimmed;
    }

    private synchronized void trimResponsesHistory() throws Exception {
        if (responsesHistory.length() <= 80) return;
        JSONArray trimmed = new JSONArray();
        for (int i = Math.max(0, responsesHistory.length() - 60); i < responsesHistory.length(); i++) {
            trimmed.put(responsesHistory.get(i));
        }
        responsesHistory = trimmed;
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
            throw new ApiException(status, endpoint, message);
        }
        return new JSONObject(response.toString());
    }

    private static final class ApiException extends Exception {
        final int status;

        ApiException(int status, String endpoint, String message) {
            super("API " + status + " at " + endpoint + ": " + message);
            this.status = status;
        }

        boolean isResponsesCompatibilityError() {
            if (status == 404 || status == 405) return true;
            String value = getMessage() == null ? "" : getMessage().toLowerCase();
            return status == 400 && ((value.contains("tool call") && value.contains("call_id"))
                    || value.contains("previous_response_id") || value.contains("unsupported response"));
        }
    }

    private static final class Endpoints {
        final String responses;
        final String chat;
        final boolean chatOnly;

        Endpoints(String responses, String chat, boolean chatOnly) {
            this.responses = responses;
            this.chat = chat;
            this.chatOnly = chatOnly;
        }

        static Endpoints from(String configured) {
            String value = configured == null ? "" : configured.trim();
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            if (value.isEmpty()) throw new IllegalArgumentException("API address is empty");
            if (value.endsWith("/chat/completions")) {
                return new Endpoints(value.substring(0, value.length() - "/chat/completions".length()) + "/responses",
                        value, true);
            }
            if (value.endsWith("/responses")) {
                String base = value.substring(0, value.length() - "/responses".length());
                return new Endpoints(value, base + "/chat/completions", false);
            }
            return new Endpoints(value + "/responses", value + "/chat/completions", false);
        }
    }
}
