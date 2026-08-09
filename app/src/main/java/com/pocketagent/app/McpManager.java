package com.youqi.studio;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages MCP server lifecycle, tool discovery, and tool invocation.
 *
 * Supports two transports:
 * - HTTP/SSE: MCP server running on localhost, app communicates via HTTP POST to /message endpoint
 * - Termux session: starts a persistent process via Termux and communicates over a local socket
 *
 * For simplicity MVP: we start MCP servers as Termux background processes with HTTP transport,
 * then communicate via localhost HTTP.
 */
final class McpManager {
    private static final String TAG = "McpManager";
    private static final String PREFS_NAME = "mcp_servers";
    private static final int HTTP_PORT_START = 9876;

    private final Context context;
    private final TermuxBridge termux;
    private final ConcurrentHashMap<String, McpServer> servers = new ConcurrentHashMap<>();
    private int nextHttpPort = HTTP_PORT_START;

    McpManager(Context context, TermuxBridge termux) {
        this.context = context.getApplicationContext();
        this.termux = termux;
        loadConfig();
    }

    // ---- Config persistence ----

    private void loadConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString("servers", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                McpServer s = McpServer.fromJson(arr.getJSONObject(i));
                servers.put(s.id, s);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load MCP config", e);
        }
    }

    private void saveConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (McpServer s : servers.values()) {
            arr.put(s.toJson());
        }
        prefs.edit().putString("servers", arr.toString()).apply();
    }

    // ---- Server management ----

    List<McpServer> listServers() {
        List<McpServer> list = new ArrayList<>(servers.values());
        Collections.sort(list, (a, b) -> a.name.compareTo(b.name));
        return list;
    }

    McpServer addServer(String name, String command, String args) {
        McpServer s = new McpServer();
        s.id = UUID.randomUUID().toString().substring(0, 8);
        s.name = name;
        s.command = command;
        s.args = args;
        s.isHttp = false;
        s.enabled = true;
        servers.put(s.id, s);
        saveConfig();
        return s;
    }

    void removeServer(String id) {
        McpServer s = servers.remove(id);
        if (s != null) stopServer(s);
        saveConfig();
    }

    void setEnabled(String id, boolean enabled) {
        McpServer s = servers.get(id);
        if (s != null) {
            s.enabled = enabled;
            if (!enabled) stopServer(s);
            saveConfig();
        }
    }

    // ---- Lifecycle ----

    /** Start an MCP server and discover its tools. */
    boolean startServer(McpServer s) throws Exception {
        if (!s.enabled || !termux.isInstalled() || !termux.hasPermission()) {
            return false;
        }
        int port = nextHttpPort++;
        s.transportUrl = "http://127.0.0.1:" + port + "/mcp";

        // Start the MCP server as a background process in Termux
        // For npx-based servers, use SSE transport if available, otherwise stdio
        String startCmd = String.format(
            "nohup %s %s --port %d > /dev/null 2>&1 & echo PID:$!",
            s.command, s.args, port
        );
        // Fallback: start with node directly for known mcp servers
        String result = termux.run(startCmd, null, "");
        Log.d(TAG, "MCP server start result: " + result);

        // Give the server a moment to start
        Thread.sleep(1500);

        // Discover tools via initialize + tools/list
        discoverTools(s);
        saveConfig();
        return !s.tools.isEmpty();
    }

    private void discoverTools(McpServer s) throws Exception {
        if (s.transportUrl == null || s.transportUrl.isEmpty()) return;

        // MCP initialize
        JSONObject initReq = new JSONObject();
        initReq.put("jsonrpc", "2.0");
        initReq.put("id", 1);
        initReq.put("method", "initialize");
        initReq.put("params", new JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject()
                        .put("name", "YouQi")
                        .put("version", "1.0.0")));

        String initResp = mcpHttpPost(s.transportUrl, initReq);
        if (initResp == null) {
            Log.w(TAG, "MCP initialize failed for " + s.name);
            return;
        }

        // tools/list
        JSONObject listReq = new JSONObject();
        listReq.put("jsonrpc", "2.0");
        listReq.put("id", 2);
        listReq.put("method", "tools/list");
        listReq.put("params", new JSONObject());

        String listResp = mcpHttpPost(s.transportUrl, listReq);
        if (listResp == null) return;

        JSONObject respObj = new JSONObject(listResp);
        JSONArray tools = respObj.optJSONObject("result").optJSONArray("tools");
        if (tools == null) return;

        s.tools.clear();
        String prefix = s.id + "__";
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            s.tools.add(new McpServer.McpTool(
                    prefix + tool.optString("name", "unknown"),
                    tool.optString("description", ""),
                    tool.optJSONObject("inputSchema")
            ));
        }
        Log.d(TAG, "Discovered " + s.tools.size() + " tools from " + s.name);
    }

    void stopServer(McpServer s) {
        // Kill the background process (we track by port)
        if (s.transportUrl != null && !s.transportUrl.isEmpty()) {
            try {
                String port = s.transportUrl.replaceAll(".*:(\\d+).*", "$1");
                termux.run("pkill -f '--port " + port + "' 2>/dev/null; true", null, "");
            } catch (Exception ignored) {}
        }
        s.tools.clear();
        s.transportUrl = null;
    }

    void stopAll() {
        for (McpServer s : servers.values()) {
            stopServer(s);
        }
    }

    // ---- Tool listing ----

    /** Get all tools from all enabled servers. */
    List<McpServer.McpTool> getAllTools() {
        List<McpServer.McpTool> all = new ArrayList<>();
        for (McpServer s : servers.values()) {
            if (s.enabled) {
                all.addAll(s.tools);
            }
        }
        return all;
    }

    /** Get JSON tool definitions for the LLM. */
    JSONArray getToolDefinitions() {
        JSONArray arr = new JSONArray();
        for (McpServer.McpTool tool : getAllTools()) {
            arr.put(tool.toToolDef());
        }
        return arr;
    }

    // ---- Tool invocation ----

    /** Call an MCP tool by name and return the result text. */
    String callTool(String fullName, JSONObject arguments) throws Exception {
        // fullName = "serverId__toolName"
        String serverId = null;
        String toolName = fullName;
        for (McpServer s : servers.values()) {
            String prefix = s.id + "__";
            if (fullName.startsWith(prefix)) {
                serverId = s.id;
                toolName = fullName.substring(prefix.length());
                break;
            }
        }
        if (serverId == null) {
            return "Error: MCP server not found for tool: " + fullName;
        }

        McpServer s = servers.get(serverId);
        if (s == null || !s.enabled) {
            return "Error: MCP server " + serverId + " is not available";
        }

        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", System.currentTimeMillis() % 100000);
        req.put("method", "tools/call");
        req.put("params", new JSONObject()
                .put("name", toolName)
                .put("arguments", arguments != null ? arguments : new JSONObject()));

        String resp = mcpHttpPost(s.transportUrl, req);
        if (resp == null) return "Error: MCP call timed out";

        JSONObject respObj = new JSONObject(resp);
        if (respObj.has("error")) {
            return "MCP error: " + respObj.optJSONObject("error").optString("message", "unknown");
        }
        JSONArray content = respObj.optJSONObject("result").optJSONArray("content");
        if (content == null) return respObj.optJSONObject("result").toString();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.getJSONObject(i);
            if ("text".equals(item.optString("type"))) {
                sb.append(item.optString("text", ""));
            } else {
                sb.append(item.toString());
            }
        }
        return sb.toString();
    }

    // ---- HTTP transport ----

    private String mcpHttpPost(String baseUrl, JSONObject payload) {
        try {
            URL url = new URL(baseUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            if (code != 200 && code != 202) {
                Log.w(TAG, "MCP HTTP " + code + " for " + baseUrl);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                // Handle SSE format: skip "data: " prefix, skip empty lines and event: lines
                if (line.startsWith("data: ")) {
                    sb.append(line.substring(6));
                } else if (!line.startsWith("event:") && !line.isEmpty()) {
                    sb.append(line);
                }
            }
            reader.close();
            conn.disconnect();
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            Log.e(TAG, "MCP HTTP error", e);
            return null;
        }
    }
}
