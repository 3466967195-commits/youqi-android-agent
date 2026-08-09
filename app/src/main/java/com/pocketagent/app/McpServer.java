package com.youqi.studio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a configured MCP server, either bundled or user-added.
 */
final class McpServer {
    String id;
    String name;
    String command;       // e.g. "npx" or "node"
    String args;          // e.g. "-y @anthropic/mcp-server-filesystem /sdcard"
    String transportUrl;  // for HTTP/SSE transport, e.g. "http://127.0.0.1:9876/sse"
    boolean isHttp;       // true = HTTP transport, false = stdio (via Termux session)
    boolean enabled;

    // Runtime state (not persisted)
    transient List<McpTool> tools = new ArrayList<>();

    static McpServer fromJson(JSONObject obj) {
        McpServer s = new McpServer();
        s.id = obj.optString("id", "");
        s.name = obj.optString("name", "");
        s.command = obj.optString("command", "");
        s.args = obj.optString("args", "");
        s.transportUrl = obj.optString("transportUrl", "");
        s.isHttp = obj.optBoolean("isHttp", false);
        s.enabled = obj.optBoolean("enabled", true);
        return s;
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("name", name);
            obj.put("command", command);
            obj.put("args", args);
            obj.put("transportUrl", transportUrl);
            obj.put("isHttp", isHttp);
            obj.put("enabled", enabled);
        } catch (Exception ignored) {}
        return obj;
    }

    static class McpTool {
        String name;
        String description;
        JSONObject inputSchema;

        McpTool(String name, String description, JSONObject inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        JSONObject toToolDef() {
            JSONObject def = new JSONObject();
            try {
                def.put("type", "function");
                JSONObject func = new JSONObject();
                func.put("name", "mcp__" + name);
                func.put("description", description);
                func.put("parameters", inputSchema != null ? inputSchema : new JSONObject());
                def.put("function", func);
            } catch (Exception ignored) {}
            return def;
        }
    }
}
