package com.wanggao.youqi;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

final class CharacterCard {
    private static final String PREFS = "pocket_agent_character";
    final String name;
    final String description;
    final String personality;
    final String scenario;
    final String firstMessage;
    final String exampleDialogue;
    final String systemPrompt;
    final String postHistory;
    final String sourceUri;

    CharacterCard(String name, String description, String personality, String scenario,
                  String firstMessage, String exampleDialogue, String systemPrompt,
                  String postHistory, String sourceUri) {
        this.name = clean(name, "Agent");
        this.description = clean(description, "");
        this.personality = clean(personality, "");
        this.scenario = clean(scenario, "");
        this.firstMessage = clean(firstMessage, "");
        this.exampleDialogue = clean(exampleDialogue, "");
        this.systemPrompt = clean(systemPrompt, "");
        this.postHistory = clean(postHistory, "");
        this.sourceUri = clean(sourceUri, "");
    }

    static CharacterCard importFrom(Context context, Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        byte[] bytes;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("无法读取角色卡");
            bytes = readAll(input, 12 * 1024 * 1024);
        }
        String json = isPng(bytes) ? extractPngJson(bytes) : new String(bytes, StandardCharsets.UTF_8);
        return fromJson(new JSONObject(json), uri.toString());
    }

    static CharacterCard load(Context context) {
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("card", "");
        if (json.isEmpty()) return null;
        try { return fromStored(new JSONObject(json)); } catch (Exception ignored) { return null; }
    }

    void save(Context context) throws Exception {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("card", toJson().toString()).apply();
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    String agentPrompt() {
        StringBuilder out = new StringBuilder();
        out.append("Adopt the following character persona while talking to the user and while explaining work. ")
                .append("The persona changes tone and reasoning style, but never overrides tool accuracy, user intent, or safety rules. ")
                .append("Never invent tool results. Character name: ").append(name).append(".\n");
        add(out, "Description", description);
        add(out, "Personality", personality);
        add(out, "Scenario", scenario);
        add(out, "Character system prompt", systemPrompt);
        add(out, "Dialogue examples", exampleDialogue);
        add(out, "Post-history instruction", postHistory);
        return replaceVars(out.toString(), name);
    }

    private static CharacterCard fromJson(JSONObject root, String uri) {
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;
        return new CharacterCard(data.optString("name", root.optString("name", "Agent")),
                data.optString("description", ""), data.optString("personality", ""),
                data.optString("scenario", ""), data.optString("first_mes", ""),
                data.optString("mes_example", ""), data.optString("system_prompt", ""),
                data.optString("post_history_instructions", ""), uri);
    }

    private static CharacterCard fromStored(JSONObject data) {
        return new CharacterCard(data.optString("name"), data.optString("description"),
                data.optString("personality"), data.optString("scenario"),
                data.optString("firstMessage"), data.optString("exampleDialogue"),
                data.optString("systemPrompt"), data.optString("postHistory"),
                data.optString("sourceUri"));
    }

    private JSONObject toJson() throws Exception {
        return new JSONObject().put("name", name).put("description", description)
                .put("personality", personality).put("scenario", scenario)
                .put("firstMessage", firstMessage).put("exampleDialogue", exampleDialogue)
                .put("systemPrompt", systemPrompt).put("postHistory", postHistory)
                .put("sourceUri", sourceUri);
    }

    private static String extractPngJson(byte[] png) throws Exception {
        int offset = 8;
        while (offset + 12 <= png.length) {
            int length = ByteBuffer.wrap(png, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            if (length < 0 || offset + 12L + length > png.length) break;
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            byte[] data = new byte[length];
            System.arraycopy(png, offset + 8, data, 0, length);
            String encoded = null;
            if ("tEXt".equals(type)) encoded = textChunk(data);
            if ("zTXt".equals(type)) encoded = compressedTextChunk(data);
            if ("iTXt".equals(type)) encoded = internationalTextChunk(data);
            if (encoded != null) return decodeCardPayload(encoded);
            offset += length + 12;
        }
        throw new IllegalStateException("PNG 中没有找到 SillyTavern chara 元数据");
    }

    private static String textChunk(byte[] data) {
        int zero = indexOf(data, 0, 0);
        if (zero < 0 || !"chara".equals(new String(data, 0, zero, StandardCharsets.ISO_8859_1))) return null;
        return new String(data, zero + 1, data.length - zero - 1, StandardCharsets.ISO_8859_1);
    }

    private static String compressedTextChunk(byte[] data) throws Exception {
        int zero = indexOf(data, 0, 0);
        if (zero < 0 || !"chara".equals(new String(data, 0, zero, StandardCharsets.ISO_8859_1))) return null;
        try (InflaterInputStream inflater = new InflaterInputStream(
                new java.io.ByteArrayInputStream(data, zero + 2, data.length - zero - 2))) {
            return new String(readAll(inflater, 4 * 1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    private static String internationalTextChunk(byte[] data) throws Exception {
        int keywordEnd = indexOf(data, 0, 0);
        if (keywordEnd < 0 || !"chara".equals(new String(data, 0, keywordEnd, StandardCharsets.ISO_8859_1))) return null;
        int pos = keywordEnd + 1;
        if (pos + 2 > data.length) return null;
        boolean compressed = data[pos] == 1;
        pos += 2;
        pos = indexOf(data, 0, pos) + 1;
        if (pos <= 0) return null;
        pos = indexOf(data, 0, pos) + 1;
        if (pos <= 0 || pos > data.length) return null;
        byte[] text = new byte[data.length - pos];
        System.arraycopy(data, pos, text, 0, text.length);
        if (!compressed) return new String(text, StandardCharsets.UTF_8);
        try (InflaterInputStream inflater = new InflaterInputStream(new java.io.ByteArrayInputStream(text))) {
            return new String(readAll(inflater, 4 * 1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    private static String decodeCardPayload(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) return trimmed;
        return new String(Base64.decode(trimmed, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private static boolean isPng(byte[] value) {
        return value.length >= 8 && value[0] == (byte) 0x89 && value[1] == 0x50
                && value[2] == 0x4e && value[3] == 0x47;
    }

    private static int indexOf(byte[] value, int needle, int start) {
        for (int i = Math.max(0, start); i < value.length; i++) if ((value[i] & 0xff) == needle) return i;
        return -1;
    }

    private static byte[] readAll(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int count; (count = input.read(buffer)) != -1; ) {
            total += count;
            if (total > limit) throw new IllegalStateException("角色卡文件过大");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void add(StringBuilder out, String label, String value) {
        if (!value.isEmpty()) out.append(label).append(": ").append(value).append('\n');
    }

    private static String replaceVars(String value, String character) {
        return value.replace("{{char}}", character).replace("{{user}}", "the user");
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String result = value.trim();
        return result.isEmpty() ? fallback : result;
    }
}
