package com.siny.mobilebasic.modules;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores app settings separately and encrypts the AutoSync token with Android Keystore AES-GCM. */
public final class SecureSettings {
    private static final String PREFS = "mobile_basic_settings";
    private static final String KEY_ALIAS_PREFIX = "com.siny.mobilebasic.token.";
    private static final String SERVER_URL = "autosync_server_url";
    private static final String UPDATE_URL = "autoupdate_manifest_url";
    private static final String ENCRYPTED_TOKEN = "autosync_token_gcm";
    private final SharedPreferences prefs;
    private final String keyAlias;
    private final String defaultServerUrl;
    private final String defaultUpdateUrl;

    public SecureSettings(Context context, String appId) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        keyAlias = KEY_ALIAS_PREFIX + appId;
        defaultServerUrl = assetValue(app, "autosync_server_url");
        defaultUpdateUrl = assetValue(app, "update_manifest_url");
    }

    public String serverUrl() { return prefs.getString(SERVER_URL, defaultServerUrl); }
    public String updateManifestUrl() { return prefs.getString(UPDATE_URL, defaultUpdateUrl); }

    private static String assetValue(Context context, String key) {
        try (InputStream input = context.getAssets().open("mobile-app.json");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            JSONObject config = new JSONObject(output.toString("UTF-8"));
            String value = config.optString(key, "").trim();
            if (value.isEmpty() && "autosync_server_url".equals(key)) {
                JSONObject sync = config.optJSONObject("sync");
                if (sync != null) value = sync.optString("base_url", "").trim();
            }
            return value;
        } catch (Exception ignored) {
            return "";
        }
    }
    public String autosyncToken() {
        String value = prefs.getString(ENCRYPTED_TOKEN, "");
        return value.isEmpty() ? "" : decrypt(value);
    }

    public void save(String serverUrl, String token, String updateManifestUrl) {
        String encrypted = token == null || token.isEmpty() ? "" : encrypt(token);
        prefs.edit().putString(SERVER_URL, serverUrl == null ? "" : serverUrl.trim())
                .putString(ENCRYPTED_TOKEN, encrypted)
                .putString(UPDATE_URL, updateManifestUrl == null ? "" : updateManifestUrl.trim())
                .apply();
    }

    public String pendingUpdatePath() { return prefs.getString("pending_update_path", ""); }
    public long pendingUpdateVersionCode() { return prefs.getLong("pending_update_version_code", 0L); }
    public void setPendingUpdate(String path, long versionCode) {
        prefs.edit().putString("pending_update_path", path).putLong("pending_update_version_code", versionCode).apply();
    }
    public void clearPendingUpdate() {
        prefs.edit().remove("pending_update_path").remove("pending_update_version_code").apply();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(keyAlias, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    private String encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer packed = ByteBuffer.allocate(4 + iv.length + body.length);
            packed.putInt(iv.length).put(iv).put(body);
            return Base64.encodeToString(packed.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the AutoSync credential", e);
        }
    }

    private String decrypt(String encoded) {
        try {
            byte[] packedBytes = Base64.decode(encoded, Base64.NO_WRAP);
            ByteBuffer packed = ByteBuffer.wrap(packedBytes);
            int ivLength = packed.getInt();
            if (ivLength < 12 || ivLength > 32 || packed.remaining() <= ivLength) throw new IllegalArgumentException("Invalid encrypted setting");
            byte[] iv = new byte[ivLength]; packed.get(iv);
            byte[] body = new byte[packed.remaining()]; packed.get(body);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(body), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt the AutoSync credential; re-enter it in Settings", e);
        }
    }
}
