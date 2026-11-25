package com.siny.mobilebasic.assistant;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Sends a finger-held WAV clip to NAVER CLOVA CSR. */
public final class ClovaSpeechClient {
    private static final String ENDPOINT = "https://naveropenapi.apigw.ntruss.com/recog/v1?lang=Kor";

    public String transcribe(byte[] wav, String keyId, String key) throws IOException {
        if (wav == null || wav.length < 44) throw new IOException("녹음된 음성이 없습니다.");
        if (empty(keyId) || empty(key)) throw new IOException("NAVER 음성 인식 API 설정이 필요합니다.");
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("X-NCP-APIGW-API-KEY-ID", keyId.trim());
            connection.setRequestProperty("X-NCP-APIGW-API-KEY", key.trim());
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setFixedLengthStreamingMode(wav.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(wav); }
            int status = connection.getResponseCode();
            String body = read(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) throw new IOException("NAVER 음성 인식 연결에 실패했습니다 (HTTP " + status + ").");
            String text;
            try { text = new JSONObject(body).optString("text", "").trim(); }
            catch (JSONException e) { throw new IOException("NAVER 음성 인식 응답을 읽지 못했습니다.", e); }
            if (text.isEmpty()) throw new IOException("말씀을 알아듣지 못했어요. 마이크를 누른 채 다시 말씀해 주세요.");
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 256 * 1024) throw new IOException("NAVER 응답이 너무 큽니다.");
                out.write(buffer, 0, count);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
}
