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
