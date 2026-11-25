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

/** Stores credentials outside APK assets and encrypts them with Android Keystore AES-GCM. */
public final class SecureSettings {
    private static final String PREFS = "mobile_basic_settings";
    private static final String KEY_ALIAS_PREFIX = "com.siny.mobilebasic.token.";
    private static final String SERVER_URL = "autosync_server_url";
    private static final String UPDATE_URL = "autoupdate_manifest_url";
    private static final String ENCRYPTED_TOKEN = "autosync_token_gcm";
    private static final String CLOVA_KEY = "ma_clova_api_key_gcm";
    private static final String NAVER_KEY_ID = "ma_naver_api_key_id_gcm";
    private static final String NAVER_KEY = "ma_naver_api_key_gcm";
    private final SharedPreferences prefs;
    private final String keyAlias;
    private final String clovaKeyAlias;
    private final String naverIdKeyAlias;
    private final String naverKeyAlias;
    private final String defaultServerUrl;
    private final String defaultUpdateUrl;

    public SecureSettings(Context context, String appId) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        keyAlias = KEY_ALIAS_PREFIX + appId;
        clovaKeyAlias = KEY_ALIAS_PREFIX + appId + ".clova";
        naverIdKeyAlias = KEY_ALIAS_PREFIX + appId + ".naver-id";
        naverKeyAlias = KEY_ALIAS_PREFIX + appId + ".naver-key";
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
        return value.isEmpty() ? "" : decrypt(keyAlias, value);
    }

    public String clovaApiKey() { return decryptSetting(CLOVA_KEY, clovaKeyAlias); }
    public String naverApiKeyId() { return decryptSetting(NAVER_KEY_ID, naverIdKeyAlias); }
    public String naverApiKey() { return decryptSetting(NAVER_KEY, naverKeyAlias); }

    private String decryptSetting(String preference, String alias) {
        String value = prefs.getString(preference, "");
        return value.isEmpty() ? "" : decrypt(alias, value);
    }

    public void save(String serverUrl, String token, String updateManifestUrl) {
        String encrypted = token == null || token.isEmpty() ? "" : encrypt(keyAlias, token);
        prefs.edit().putString(SERVER_URL, serverUrl == null ? "" : serverUrl.trim())
                .putString(ENCRYPTED_TOKEN, encrypted)
                .putString(UPDATE_URL, updateManifestUrl == null ? "" : updateManifestUrl.trim())
                .apply();
    }

    public void saveAssistant(String clovaApiKey, String naverApiKeyId, String naverApiKey) {
        String clova = clovaApiKey == null || clovaApiKey.isEmpty() ? "" : encrypt(clovaKeyAlias, clovaApiKey);
        String naverId = naverApiKeyId == null || naverApiKeyId.isEmpty() ? "" : encrypt(naverIdKeyAlias, naverApiKeyId);
        String naver = naverApiKey == null || naverApiKey.isEmpty() ? "" : encrypt(naverKeyAlias, naverApiKey);
        prefs.edit().putString(CLOVA_KEY, clova)
                .putString(NAVER_KEY_ID, naverId)
                .putString(NAVER_KEY, naver)
                .apply();
    }

    public String pendingUpdatePath() { return prefs.getString("pending_update_path", ""); }
    public long pendingUpdateVersionCode() { return prefs.getLong("pending_update_version_code", 0L); }
    public void setPendingUpdate(String path, long versionCode) {
        prefs.edit().putString("pending_update_path", path).putLong("pending_update_version_code", versionCode)
                .putBoolean("pending_update_install_attempted", false)
                .putBoolean("pending_update_awaiting_permission", false).apply();
    }
    public boolean pendingUpdateInstallAttempted() { return prefs.getBoolean("pending_update_install_attempted", false); }
    public boolean pendingUpdateAwaitingPermission() { return prefs.getBoolean("pending_update_awaiting_permission", false); }
    public void markPendingUpdateAwaitingPermission() {
        prefs.edit().putBoolean("pending_update_install_attempted", true)
                .putBoolean("pending_update_awaiting_permission", true).apply();
    }
    public void markPendingUpdateInstallAttempted() { prefs.edit().putBoolean("pending_update_install_attempted", true).apply(); }
    public void resetPendingUpdateInstallAttempted() {
        prefs.edit().putBoolean("pending_update_install_attempted", false)
                .putBoolean("pending_update_awaiting_permission", false).apply();
    }
    public void clearPendingUpdate() {
        prefs.edit().remove("pending_update_path").remove("pending_update_version_code")
                .remove("pending_update_install_attempted").remove("pending_update_awaiting_permission").apply();
    }

    private SecretKey key(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(alias, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    private String encrypt(String alias, String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(alias));
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer packed = ByteBuffer.allocate(4 + iv.length + body.length);
            packed.putInt(iv.length).put(iv).put(body);
            return Base64.encodeToString(packed.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the AutoSync credential", e);
        }
    }

    private String decrypt(String alias, String encoded) {
        try {
            byte[] packedBytes = Base64.decode(encoded, Base64.NO_WRAP);
            ByteBuffer packed = ByteBuffer.wrap(packedBytes);
            int ivLength = packed.getInt();
            if (ivLength < 12 || ivLength > 32 || packed.remaining() <= ivLength) throw new IllegalArgumentException("Invalid encrypted setting");
            byte[] iv = new byte[ivLength]; packed.get(iv);
            byte[] body = new byte[packed.remaining()]; packed.get(body);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(alias), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(body), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt the AutoSync credential; re-enter it in Settings", e);
        }
    }
}
