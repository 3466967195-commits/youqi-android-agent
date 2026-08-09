package com.pocketagent.app;

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
    private static final String ALIAS = "pocket_agent_api_key";
    private static final String PREFS = "pocket_agent_settings";
    private final SharedPreferences preferences;

    SecurePrefs(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String getEndpoint() {
        return preferences.getString("endpoint", "https://api.openai.com/v1/responses");
    }

    String getModel() {
        return preferences.getString("model", "gpt-5.3-codex");
    }

    void saveConnection(String endpoint, String model, String apiKey) throws Exception {
        SharedPreferences.Editor editor = preferences.edit()
                .putString("endpoint", endpoint.trim())
                .putString("model", model.trim());
        if (!apiKey.trim().isEmpty()) {
            editor.putString("api_key", encrypt(apiKey.trim()));
        }
        editor.apply();
    }

    boolean hasApiKey() {
        return preferences.contains("api_key");
    }

    String getApiKey() throws Exception {
        String encoded = preferences.getString("api_key", "");
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
