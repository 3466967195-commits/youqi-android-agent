package com.wanggao.youqi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ModelCatalogClient {
    static List<String> fetch(String url, String apiKey, boolean gemini) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        if (gemini) connection.setRequestProperty("x-goog-api-key", apiKey);
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder body = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            String message = body.toString().trim();
            try {
                JSONObject error = new JSONObject(message).optJSONObject("error");
                if (error != null) message = error.optString("message", message);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("Models API " + status + ": " + message);
        }
        JSONObject root = new JSONObject(body.toString());
        JSONArray data = root.optJSONArray("data");
        if (data == null) data = root.optJSONArray("models");
        if (data == null) throw new IllegalStateException("模型接口响应中没有 data/models 列表");
        List<String> models = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", item.optString("name", ""));
            if (id.startsWith("models/")) id = id.substring(7);
            if (!id.isEmpty() && !models.contains(id)) models.add(id);
        }
        Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
        if (models.isEmpty()) throw new IllegalStateException("模型接口返回了空列表");
        return models;
    }

    private ModelCatalogClient() {
    }
}
