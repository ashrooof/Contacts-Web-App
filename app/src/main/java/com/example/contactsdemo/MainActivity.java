package com.example.contactsdemo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final int CONTACTS_PERMISSION_REQUEST = 41;
    private static final int LOCATION_PERMISSION_REQUEST = 42;
    private WebView webView;
    private boolean pendingContactsRequest = false;
    private boolean pendingDeviceInfoRequest = false;
    private boolean includeExtraInfo = false;

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
                    requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},
                            CONTACTS_PERMISSION_REQUEST);
                }
            });
        }

        @JavascriptInterface
        public void collectDeviceInfo(boolean includeLocation) {
            runOnUiThread(() -> {
                includeExtraInfo = includeLocation;
                if (!includeExtraInfo) {
                    deliverDeviceInfo(null);
                    return;
                }
                boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
                boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
                if (fine || coarse) {
                    deliverDeviceInfo(readLastKnownLocation());
                } else {
                    pendingDeviceInfoRequest = true;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, LOCATION_PERMISSION_REQUEST);
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
                    connection.setRequestProperty("Content-Type",
                            "multipart/form-data; boundary=" + boundary);
                    try (OutputStream out = connection.getOutputStream()) {
                        writeField(out, boundary, "chat_id", chatId.trim());
                        writeBytes(out, "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"document\"; filename=\"contacts-report.txt\"\r\n"
                                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n");
                        out.write(textFile.getBytes(StandardCharsets.UTF_8));
                        writeBytes(out, "\r\n--" + boundary + "--\r\n");
                        out.flush();
                    }
                    int code = connection.getResponseCode();
                    InputStream stream = (code >= 200 && code < 300)
                            ? connection.getInputStream() : connection.getErrorStream();
                    JSONObject json = new JSONObject(readAll(stream));
                    boolean ok = json.optBoolean("ok", false);
                    String message = ok ? "تم إرسال التقرير الذي وافقت عليه بنجاح."
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
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC");
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
        webView.evaluateJavascript("window.onContactsReceived("
                + JSONObject.quote(contacts.toString()) + ");", null);
    }

    private JSONObject readLastKnownLocation() {
        try {
            boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!fine && !coarse) return null;

            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            List<String> providers = manager.getProviders(true);
            Location best = null;
            for (String provider : providers) {
                try {
                    Location candidate = manager.getLastKnownLocation(provider);
                    if (candidate != null && (best == null
                            || candidate.getTime() > best.getTime())) best = candidate;
                } catch (SecurityException ignored) {
                }
            }
            if (best == null) return null;

            JSONObject location = new JSONObject();
            location.put("latitude", best.getLatitude());
            location.put("longitude", best.getLongitude());
            location.put("accuracy_meters", best.hasAccuracy() ? best.getAccuracy() : JSONObject.NULL);
            location.put("timestamp", best.getTime());
            location.put("source", "آخر موقع معروف محفوظ على الجهاز");
            return location;
        } catch (Exception e) {
            return null;
        }
    }

    private void deliverDeviceInfo(JSONObject location) {
        try {
            JSONObject info = new JSONObject();
            info.put("included", includeExtraInfo);
            if (includeExtraInfo) {
                info.put("manufacturer", Build.MANUFACTURER);
                info.put("model", Build.MODEL);
                info.put("android_version", Build.VERSION.RELEASE);
                info.put("android_sdk", Build.VERSION.SDK_INT);
                try {
                    PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
                    info.put("app_version", p.versionName == null ? "غير معروف" : p.versionName);
                } catch (Exception ignored) {
                    info.put("app_version", "غير معروف");
                }
                info.put("location", location == null ? JSONObject.NULL : location);
            }
            webView.evaluateJavascript("window.onDeviceInfoReceived("
                    + JSONObject.quote(info.toString()) + ");", null);
        } catch (Exception ignored) {
            webView.evaluateJavascript("window.onDeviceInfoReceived('{}');", null);
        }
    }

    private void notifyTelegramResult(boolean ok, String message) {
        runOnUiThread(() -> webView.evaluateJavascript("window.onTelegramResult("
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
        } else if (requestCode == LOCATION_PERMISSION_REQUEST && pendingDeviceInfoRequest) {
            pendingDeviceInfoRequest = false;
            deliverDeviceInfo(readLastKnownLocation());
        }
    }
}