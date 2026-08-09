package com.youqi.studio;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecurePrefs {
    static final String MODE_ASK = "ask";
    static final String MODE_STANDARD = "standard";
    static final String MODE_FULL = "full";
    private static final String ALIAS = "pocket_agent_api_key";
    private static final String PREFS = "pocket_agent_settings";
    private final SharedPreferences preferences;

    SecurePrefs(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String getProvider() {
        String saved = preferences.getString("provider", "");
        if (!saved.isEmpty()) return saved;
        return ProviderCatalog.infer(preferences.getString("endpoint", "https://api.openai.com/v1")).id;
    }

    String getEndpoint() {
        return getEndpoint(getProvider());
    }

    String getEndpoint(String providerId) {
        String saved = preferences.getString("endpoint." + providerId, "");
        if (!saved.isEmpty()) return saved;
        String legacy = preferences.getString("endpoint", "");
        if (!legacy.isEmpty() && ProviderCatalog.infer(legacy).id.equals(providerId)) return legacy;
        return ProviderCatalog.byId(providerId).baseUrl;
    }

    String getModel() {
        return getModel(getProvider());
    }

    String getModel(String providerId) {
        String saved = preferences.getString("model." + providerId, "");
        if (!saved.isEmpty()) return saved;
        String legacyEndpoint = preferences.getString("endpoint", "");
        if (ProviderCatalog.infer(legacyEndpoint).id.equals(providerId)) {
            return preferences.getString("model", "");
        }
        return providerId.equals("openai") ? "gpt-5.3-codex" : "";
    }

    void saveConnection(String providerId, String endpoint, String model, String apiKey) throws Exception {
        SharedPreferences.Editor editor = preferences.edit()
                .putString("provider", providerId)
                .putString("endpoint." + providerId, endpoint.trim())
                .putString("model." + providerId, model.trim());
        if (!apiKey.trim().isEmpty()) {
            editor.putString("api_key." + providerId, encrypt(apiKey.trim()));
        }
        editor.apply();
    }

    boolean hasApiKey() {
        return hasApiKey(getProvider());
    }

    boolean hasApiKey(String providerId) {
        if (preferences.contains("api_key." + providerId)) return true;
        String legacyEndpoint = preferences.getString("endpoint", "");
        return preferences.contains("api_key") && ProviderCatalog.infer(legacyEndpoint).id.equals(providerId);
    }

    String getExecutionMode() {
        return preferences.getString("execution_mode", MODE_STANDARD);
    }

    String getBackendUrl() {
        String saved = preferences.getString("backend_url", "");
        return saved.isEmpty() ? BuildConfig.BACKEND_URL : saved;
    }

    String getAuthToken() throws Exception {
        String value = preferences.getString("auth_token", "");
        return value.isEmpty() ? "" : decrypt(value);
    }

    String getDisplayName() {
        return preferences.getString("display_name", "");
    }

    void saveSession(String backendUrl, String token, String displayName) throws Exception {
        preferences.edit().putString("backend_url", AuthClient.normalizeBaseUrl(backendUrl))
                .putString("auth_token", encrypt(token)).putString("display_name", displayName).apply();
    }

    void clearSession() {
        preferences.edit().remove("auth_token").remove("display_name").apply();
    }

    void setExecutionMode(String mode) {
        if (!MODE_ASK.equals(mode) && !MODE_STANDARD.equals(mode) && !MODE_FULL.equals(mode)) {
            mode = MODE_STANDARD;
        }
        preferences.edit().putString("execution_mode", mode).apply();
    }

    String getApiKey() throws Exception {
        return getApiKey(getProvider());
    }

    String getApiKey(String providerId) throws Exception {
        String encoded = preferences.getString("api_key." + providerId, "");
        if (encoded.isEmpty()) {
            String legacyEndpoint = preferences.getString("endpoint", "");
            if (ProviderCatalog.infer(legacyEndpoint).id.equals(providerId)) {
                encoded = preferences.getString("api_key", "");
            }
        }
        return encoded.isEmpty() ? "" : decrypt(encoded);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Invalid encrypted key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
