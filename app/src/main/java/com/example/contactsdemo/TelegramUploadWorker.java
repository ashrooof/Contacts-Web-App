package com.ashrof.bluetoothcollector;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TelegramUploadWorker extends Worker {
    public TelegramUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        String fileName = getInputData().getString("queue_file");
        String token = BuildConfig.TELEGRAM_BOT_TOKEN;
        String chatId = BuildConfig.TELEGRAM_CHAT_ID;
        if (fileName == null || fileName.contains("/") || fileName.contains("\\")) return Result.failure();
        if (token == null || token.trim().isEmpty() || chatId == null || chatId.trim().isEmpty()) {
            return Result.failure();
        }
        HttpURLConnection connection = null;
        try {
            if (!getApplicationContext().getSharedPreferences("privacy", Context.MODE_PRIVATE).getBoolean("telegram_consent", false)) {
                EncryptedQueue.delete(getApplicationContext(), fileName);
                return Result.failure();
            }
            byte[] document = EncryptedQueue.read(getApplicationContext(), fileName);
            if (!getApplicationContext().getSharedPreferences("privacy", Context.MODE_PRIVATE).getBoolean("telegram_consent", false)) {
                EncryptedQueue.delete(getApplicationContext(), fileName);
                return Result.failure();
            }
            String boundary = "----BTContacts" + System.currentTimeMillis();
            URL url = new URL("https://api.telegram.org/bot" + token + "/sendDocument");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream raw = new BufferedOutputStream(connection.getOutputStream())) {
                writeField(raw, boundary, "chat_id", chatId);
                writeAscii(raw, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"contacts.txt\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n");
                raw.write(document);
                writeAscii(raw, "\r\n--" + boundary + "--\r\n");
                raw.flush();
            }
            int code = connection.getResponseCode();
            InputStream response = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readText(response);
            if (code >= 200 && code < 300 && body.contains("\"ok\":true")) {
                EncryptedQueue.delete(getApplicationContext(), fileName);
                return Result.success();
            }
            if (code == 429 || code >= 500 || code == 408) return Result.retry();
            return Result.failure();
        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            return Result.retry();
        } catch (Exception e) {
            return Result.retry();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        writeAscii(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void writeAscii(OutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String readText(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = new BufferedInputStream(in); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048]; int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
