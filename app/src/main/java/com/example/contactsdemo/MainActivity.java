package com.example.contactsdemo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int CONTACTS_PERMISSION_REQUEST = 41;
    private WebView webView;
    private boolean pendingContactsRequest = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new ContactsBridge(), "AndroidContacts");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private class ContactsBridge {
        @JavascriptInterface
        public void requestContacts() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED) {
                    sendContacts();
                } else {
                    pendingContactsRequest = true;
                    requestPermissions(
                        new String[]{Manifest.permission.READ_CONTACTS},
                        CONTACTS_PERMISSION_REQUEST
                    );
                }
            });
        }

        @JavascriptInterface
        public void sendContactsToTelegram(String botToken, String chatId, String textFile) {
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    if (botToken == null || botToken.trim().isEmpty()
                            || chatId == null || chatId.trim().isEmpty()) {
                        throw new IllegalArgumentException("أدخل توكن البوت وChat ID أولاً.");
                    }

                    String boundary = "----ContactsApp" + System.currentTimeMillis();
                    URL url = new URL("https://api.telegram.org/bot"
                            + botToken.trim() + "/sendDocument");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(20000);
                    connection.setRequestProperty(
                            "Content-Type", "multipart/form-data; boundary=" + boundary);

                    try (OutputStream out = connection.getOutputStream()) {
                        writeField(out, boundary, "chat_id", chatId.trim());
                        writeBytes(out, "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"document\"; filename=\"contacts.txt\"\r\n"
                                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n");
                        out.write(textFile.getBytes(StandardCharsets.UTF_8));
                        writeBytes(out, "\r\n--" + boundary + "--\r\n");
                        out.flush();
                    }

                    int code = connection.getResponseCode();
                    InputStream stream = (code >= 200 && code < 300)
                            ? connection.getInputStream() : connection.getErrorStream();
                    String response = readAll(stream);
                    JSONObject json = new JSONObject(response);
                    boolean ok = json.optBoolean("ok", false);
                    String message = ok ? "تم إرسال ملف جهات الاتصال إلى البوت بنجاح."
                            : json.optString("description", "فشل Telegram في استقبال الملف.");
                    notifyTelegramResult(ok, message);
                } catch (Exception e) {
                    String message = e.getMessage() == null
                            ? "تعذر الاتصال بتليجرام." : e.getMessage();
                    notifyTelegramResult(false, message);
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }).start();
        }
    }

    private void sendContacts() {
        JSONArray contacts = new JSONArray();
        Cursor cursor = getContentResolver().query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            },
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
        );

        if (cursor != null) {
            try {
                int nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
                while (cursor.moveToNext()) {
                    JSONObject item = new JSONObject();
                    item.put("name", nameIndex >= 0 ? cursor.getString(nameIndex) : "");
                    item.put("phone", numberIndex >= 0 ? cursor.getString(numberIndex) : "");
                    contacts.put(item);
                }
            } catch (Exception ignored) {
            } finally {
                cursor.close();
            }
        }

        String payload = contacts.toString();
        webView.evaluateJavascript(
            "window.onContactsReceived(" + JSONObject.quote(payload) + ");", null);
    }

    private void notifyTelegramResult(boolean ok, String message) {
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onTelegramResult("
                        + ok + "," + JSONObject.quote(message) + ");", null));
    }

    private static void writeField(OutputStream out, String boundary,
                                   String name, String value) throws Exception {
        writeBytes(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    private static void writeBytes(OutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CONTACTS_PERMISSION_REQUEST && pendingContactsRequest) {
            pendingContactsRequest = false;
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                sendContacts();
            } else {
                webView.evaluateJavascript(
                    "window.onContactsPermissionDenied && window.onContactsPermissionDenied();",
                    null);
            }
        }
    }
}
