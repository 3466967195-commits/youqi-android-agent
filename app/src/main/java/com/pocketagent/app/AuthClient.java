package com.wanggao.youqi;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class AuthClient {
    static JSONObject register(String baseUrl, String username, String displayName, String password) throws Exception {
        return request(baseUrl, "/api/auth/register", "POST", "",
                new JSONObject().put("username", username).put("display_name", displayName).put("password", password));
    }

    static JSONObject login(String baseUrl, String username, String password) throws Exception {
        return request(baseUrl, "/api/auth/login", "POST", "",
                new JSONObject().put("username", username).put("password", password));
    }

    static JSONObject me(String baseUrl, String token) throws Exception {
        return request(baseUrl, "/api/auth/me", "GET", token, null);
    }

    private static JSONObject request(String baseUrl, String path, String method, String token, JSONObject body) throws Exception {
        String base = normalizeBaseUrl(baseUrl);
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) text.append(line);
            }
        }
        connection.disconnect();
        JSONObject response = text.length() == 0 ? new JSONObject() : new JSONObject(text.toString());
        if (status < 200 || status >= 300) throw new AuthException(status, response.optString("message", "账号服务请求失败"));
        return response;
    }

    static String normalizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        if (!result.startsWith("https://") && !result.startsWith("http://127.0.0.1") && !result.startsWith("http://localhost")) {
            throw new IllegalArgumentException("账号服务器必须使用 HTTPS");
        }
        return result;
    }

    static final class AuthException extends Exception {
        final int status;
        AuthException(int status, String message) { super(message); this.status = status; }
    }

    private AuthClient() { }
}
