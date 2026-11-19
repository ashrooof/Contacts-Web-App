package com.example.contactsdemo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int CONTACTS_PERMISSION_REQUEST = 41;
    private static final int CAMERA_PERMISSION_REQUEST = 42;
    private WebView webView;
    private boolean pendingContacts;
    private boolean pendingCamera;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                    ArrayList<String> allowed = new ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && camera) allowed.add(resource);
                    }
                    if (allowed.size() == request.getResources().length && !allowed.isEmpty())
                        request.grant(allowed.toArray(new String[0]));
                    else request.deny();
                });
            }
        });
        webView.addJavascriptInterface(new Bridge(), "AndroidApp");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private class Bridge {
        @JavascriptInterface public void requestContacts() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) readContacts();
                else { pendingContacts = true; requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CONTACTS_PERMISSION_REQUEST); }
            });
        }

        @JavascriptInterface public void requestCameraPermission() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraPermissionResult(true);
                else { pendingCamera = true; requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST); }
            });
        }

        @JavascriptInterface public void sendApprovedBundle(String token, String chatId, String contactsText, String mediaJson) {
            new Thread(() -> {
                try {
                    if (token == null || token.trim().isEmpty() || chatId == null || chatId.trim().isEmpty())
                        throw new IllegalArgumentException("أدخل Bot Token وChat ID.");
                    JSONArray media = new JSONArray(mediaJson);
                    progress(5, "جارٍ إرسال تقرير الجلسة...");
                    postTelegram(token.trim(), "sendDocument", fields(chatId), "document", "session-report.txt",
                            "text/plain", contactsText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    progress(25, "تم إرسال التقرير — جارٍ إرسال الصور كألبوم...");
                    postTelegramAlbum(token.trim(), chatId, media);
                    progress(100, "اكتمل الإرسال");
                    result(true, "تم إرسال التقرير وألبوم الصور.");
                } catch (Exception e) {
                    result(false, e.getMessage() == null ? "فشل الإرسال. راجع الاتصال وبيانات البوت." : e.getMessage());
                }
            }).start();
        }
    }

    private void postTelegramAlbum(String token, String chatId, JSONArray media) throws Exception {
        if (media.length() < 2 || media.length() > 10) throw new IllegalArgumentException("عدد الصور غير صالح.");
        String boundary = "----ContactsAlbum" + System.nanoTime();
        java.net.HttpURLConnection connection = null;
        try {
            JSONArray album = new JSONArray();
            ArrayList<byte[]> files = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            for (int i = 0; i < media.length(); i++) {
                JSONObject item = media.getJSONObject(i);
                String field = "photo" + i;
                JSONObject entry = new JSONObject();
                entry.put("type", "photo");
                entry.put("media", "attach://" + field);
                album.put(entry);
                files.add(android.util.Base64.decode(item.getString("data"), android.util.Base64.DEFAULT));
                names.add(item.optString("name", "photo-" + (i + 1) + ".jpg"));
            }
            connection = (java.net.HttpURLConnection) new java.net.URL(
                    "https://api.telegram.org/bot" + token + "/sendMediaGroup").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = connection.getOutputStream()) {
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + chatId + "\r\n");
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"media\"\r\n\r\n" + album.toString() + "\r\n");
                for (int i = 0; i < files.size(); i++) {
                    write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo" + i +
                            "\"; filename=\"" + names.get(i) + "\"\r\nContent-Type: image/jpeg\r\n\r\n");
                    out.write(files.get(i));
                    write(out, "\r\n");
                }
                write(out, "--" + boundary + "--\r\n");
            }
            int code = connection.getResponseCode();
            java.io.InputStream input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder body = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject response = new JSONObject(body.toString());
            if (code < 200 || code >= 300 || !response.optBoolean("ok", false))
                throw new IllegalStateException(response.optString("description", "Telegram album upload failed."));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Map<String,String> fields(String chat) {
        Map<String,String> map = new HashMap<>(); map.put("chat_id", chat); return map;
    }

    private void postTelegram(String token, String method, Map<String,String> fields,
                              String fileField, String filename, String mime, byte[] bytes) throws Exception {
        String boundary = "----ContactsApp" + System.nanoTime();
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(
                    "https://api.telegram.org/bot" + token + "/" + method).openConnection();
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = connection.getOutputStream()) {
                for (Map.Entry<String,String> f : fields.entrySet()) write(out, "--" + boundary +
                        "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n" + f.getValue() + "\r\n");
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" +
                        fileField + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
                out.write(bytes); write(out, "\r\n--" + boundary + "--\r\n");
            }
            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder body = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONObject response = new JSONObject(body.toString());
            if (code < 200 || code >= 300 || !response.optBoolean("ok", false))
                throw new IllegalStateException(response.optString("description", "Telegram request failed."));
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static void write(OutputStream out, String value) throws Exception {
        out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void readContacts() {
        JSONArray contacts = new JSONArray();
        Cursor cursor = getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC");
        if (cursor != null) try {
            int name = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int phone = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER);
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("name", name >= 0 ? cursor.getString(name) : "");
                item.put("phone", phone >= 0 ? cursor.getString(phone) : "");
                contacts.put(item);
            }
        } catch (Exception ignored) { } finally { cursor.close(); }
        webView.evaluateJavascript("window.onContactsReceived && window.onContactsReceived(" +
                JSONObject.quote(contacts.toString()) + ")", null);
    }

    private void cameraPermissionResult(boolean granted) {
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onCameraPermissionResult && window.onCameraPermissionResult(" + granted + ")", null));
    }
    private void progress(int percent, String label) {
        runOnUiThread(() -> webView.evaluateJavascript("window.onUploadProgress && window.onUploadProgress(" +
                percent + "," + JSONObject.quote(label) + ")", null));
    }

    private void result(boolean ok, String message) {
        runOnUiThread(() -> webView.evaluateJavascript("window.onTelegramResult && window.onTelegramResult(" +
                ok + "," + JSONObject.quote(message) + ")", null));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == CONTACTS_PERMISSION_REQUEST && pendingContacts) {
            pendingContacts = false;
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) readContacts();
            else webView.evaluateJavascript("window.onContactsPermissionDenied && window.onContactsPermissionDenied()", null);
        } else if (requestCode == CAMERA_PERMISSION_REQUEST && pendingCamera) {
            pendingCamera = false;
            cameraPermissionResult(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED);
        }
    }
}