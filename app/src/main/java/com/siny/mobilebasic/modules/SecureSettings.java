package com.siny.mobilebasic.modules;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

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
    private static final String GITHUB_UPDATE_REPOSITORY = "github_update_repository";
    private static final String ENCRYPTED_GITHUB_UPDATE_TOKEN = "github_update_token_gcm";
    private static final String GEMINI_PROXY_URL = "gemini_proxy_url";
    private static final String ENCRYPTED_TOKEN = "autosync_token_gcm";
    private static final String ENCRYPTED_GEMINI_API_KEY = "gemini_api_key_gcm";
    private static final String ENCRYPTED_CLOVA_STUDIO_API_KEY = "clova_studio_api_key_gcm";
    private static final String ENCRYPTED_NAVER_CLIENT_ID = "naver_api_client_id_gcm";
    private static final String ENCRYPTED_NAVER_CLIENT_SECRET = "naver_api_client_secret_gcm";
    private final SharedPreferences prefs;
    private final String keyAlias;

    public SecureSettings(Context context, String appId) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        keyAlias = KEY_ALIAS_PREFIX + appId;
    }

    public String serverUrl() { return prefs.getString(SERVER_URL, ""); }
    public String updateManifestUrl() { return prefs.getString(UPDATE_URL, ""); }
    public String githubUpdateRepository() { return prefs.getString(GITHUB_UPDATE_REPOSITORY, ""); }
    public String geminiProxyUrl() { return prefs.getString(GEMINI_PROXY_URL, ""); }
    public void setUpdateManifestUrlIfEmpty(String value) {
        if (value == null || value.trim().isEmpty() || !updateManifestUrl().trim().isEmpty()) return;
        prefs.edit().putString(UPDATE_URL, value.trim()).apply();
    }
    public void setGithubUpdateRepositoryIfEmpty(String value) {
        if (value == null || value.trim().isEmpty() || !githubUpdateRepository().trim().isEmpty()) return;
        prefs.edit().putString(GITHUB_UPDATE_REPOSITORY, value.trim()).apply();
    }
    public void migrateGithubUpdateRepository(String previousDefault, String currentDefault) {
        if (previousDefault == null || currentDefault == null || currentDefault.trim().isEmpty()) return;
        String current = githubUpdateRepository().trim();
        if (current.isEmpty() || current.equalsIgnoreCase(previousDefault.trim())) {
            prefs.edit().putString(GITHUB_UPDATE_REPOSITORY, currentDefault.trim())
                    .remove(ENCRYPTED_GITHUB_UPDATE_TOKEN).apply();
        }
    }
    public String githubUpdateToken() {
        String value = prefs.getString(ENCRYPTED_GITHUB_UPDATE_TOKEN, "");
        return value.isEmpty() ? "" : decrypt(value);
    }
    public void saveGithubUpdateConfig(String repository, String token, boolean clearToken) {
        String current = clearToken ? "" : token == null || token.isEmpty()
                ? githubUpdateToken() : token.trim();
        String encrypted = current.isEmpty() ? "" : encrypt(current);
        prefs.edit().putString(GITHUB_UPDATE_REPOSITORY,
                repository == null ? "" : repository.trim())
                .putString(ENCRYPTED_GITHUB_UPDATE_TOKEN, encrypted).apply();
    }
    public String autosyncToken() {
        String value = prefs.getString(ENCRYPTED_TOKEN, "");
        return value.isEmpty() ? "" : decrypt(value);
    }
    public String geminiApiKey() {
        String value = prefs.getString(ENCRYPTED_GEMINI_API_KEY, "");
        return value.isEmpty() ? "" : decrypt(value);
    }
    public String clovaStudioApiKey() {
        String value = prefs.getString(ENCRYPTED_CLOVA_STUDIO_API_KEY, "");
        return value.isEmpty() ? "" : decrypt(value);
    }
    public void saveClovaStudioApiKey(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        prefs.edit().putString(ENCRYPTED_CLOVA_STUDIO_API_KEY,
                value.isEmpty() ? "" : encrypt(value)).apply();
    }
    public String naverApiClientId() {
        String value = prefs.getString(ENCRYPTED_NAVER_CLIENT_ID, "");
        return value.isEmpty() ? "" : decrypt(value);
    }
    public String naverApiClientSecret() {
        String value = prefs.getString(ENCRYPTED_NAVER_CLIENT_SECRET, "");
        return value.isEmpty() ? "" : decrypt(value);
    }

    public void save(String serverUrl, String token, String updateManifestUrl, String geminiProxyUrl) {
        String encrypted = token == null || token.isEmpty() ? "" : encrypt(token);
        prefs.edit().putString(SERVER_URL, serverUrl == null ? "" : serverUrl.trim())
                .putString(ENCRYPTED_TOKEN, encrypted)
                .putString(UPDATE_URL, updateManifestUrl == null ? "" : updateManifestUrl.trim())
                .putString(GEMINI_PROXY_URL, geminiProxyUrl == null ? "" : geminiProxyUrl.trim())
                .apply();
    }

    public void save(String serverUrl, String token, String updateManifestUrl, String geminiProxyUrl,
                     String geminiApiKey) {
        save(serverUrl, token, updateManifestUrl, geminiProxyUrl, geminiApiKey,
                naverApiClientId(), naverApiClientSecret());
    }

    public void save(String serverUrl, String token, String updateManifestUrl, String geminiProxyUrl,
                     String geminiApiKey, String naverClientId, String naverClientSecret) {
        String encrypted = token == null || token.isEmpty() ? "" : encrypt(token);
        String encryptedGeminiKey = geminiApiKey == null || geminiApiKey.isEmpty()
                ? "" : encrypt(geminiApiKey.trim());
        String encryptedNaverClientId = naverClientId == null || naverClientId.isEmpty()
                ? "" : encrypt(naverClientId.trim());
        String encryptedNaverClientSecret = naverClientSecret == null || naverClientSecret.isEmpty()
                ? "" : encrypt(naverClientSecret.trim());
        prefs.edit().putString(SERVER_URL, serverUrl == null ? "" : serverUrl.trim())
                .putString(ENCRYPTED_TOKEN, encrypted)
                .putString(UPDATE_URL, updateManifestUrl == null ? "" : updateManifestUrl.trim())
                .putString(GEMINI_PROXY_URL, geminiProxyUrl == null ? "" : geminiProxyUrl.trim())
                .putString(ENCRYPTED_GEMINI_API_KEY, encryptedGeminiKey)
                .putString(ENCRYPTED_NAVER_CLIENT_ID, encryptedNaverClientId)
                .putString(ENCRYPTED_NAVER_CLIENT_SECRET, encryptedNaverClientSecret)
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
            throw new IllegalStateException("Could not encrypt the app credential", e);
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
