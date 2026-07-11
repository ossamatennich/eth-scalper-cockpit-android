package com.ethscalper.cockpit;

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

public final class SecureAiStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "ETH_SCALPER_OPENAI_KEY_V230";
    private static final String PREFS = "ai_settings";
    private static final String PREF_KEY = "openai_key_encrypted";
    private static final String PREF_ENABLED = "ai_enabled";
    private static final String PREF_MODEL = "ai_model";
    private static final String DEFAULT_MODEL = "gpt-5-mini";

    private SecureAiStore() {}

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ENABLED, false) && hasKey(context);
    }

    public static void saveModel(Context context, String model) {
        String clean = model == null ? "" : model.trim();
        if (clean.isEmpty()) clean = DEFAULT_MODEL;
        prefs(context).edit().putString(PREF_MODEL, clean).apply();
    }

    public static String getModel(Context context) {
        String model = prefs(context).getString(PREF_MODEL, DEFAULT_MODEL);
        return model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim();
    }

    public static void saveKey(Context context, String key) {
        try {
            String clean = key == null ? "" : key.trim();
            if (clean.isEmpty()) return;

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());

            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));

            String packed = Base64.encodeToString(iv, Base64.NO_WRAP)
                    + "."
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);

            prefs(context).edit().putString(PREF_KEY, packed).apply();
        } catch (Exception ignored) {}
    }

    public static String getKey(Context context) {
        try {
            String packed = prefs(context).getString(PREF_KEY, "");
            if (packed == null || packed.trim().isEmpty() || !packed.contains(".")) return "";

            String[] parts = packed.split("\\.", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));

            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean hasKey(Context context) {
        String key = getKey(context);
        return key != null && key.trim().length() > 12;
    }

    public static String maskedKey(Context context) {
        String key = getKey(context);
        if (key == null || key.length() < 8) return "aucune clé";
        return "••••••••" + key.substring(Math.max(0, key.length() - 4));
    }

    public static void clear(Context context) {
        prefs(context).edit()
                .remove(PREF_KEY)
                .putBoolean(PREF_ENABLED, false)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();

        generator.init(spec);
        return generator.generateKey();
    }
}
