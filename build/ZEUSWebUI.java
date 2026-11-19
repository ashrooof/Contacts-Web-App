package grant.bt.contact.transfer;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

public final class ZEUSWebUI {
    private ZEUSWebUI() {}

    public static void attach(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 17) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) content;
        if (root.findViewWithTag("ZEUS_INTERFACE_WEBVIEW") != null) return;

        WebView web = new WebView(activity);
        web.setTag("ZEUS_INTERFACE_WEBVIEW");
        web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setDefaultTextEncodingName("UTF-8");
        if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(activity), "ZEUS");
        web.setWebViewClient(new WebViewClient() {
            private boolean routeUrl(String url) {
                if (url == null) return true;
                if (url.startsWith("file:///android_asset/zeus/") || "about:blank".equals(url)) return false;
                try {
                    Uri uri = Uri.parse(url);
                    String scheme = uri.getScheme();
                    if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {}
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return routeUrl(url); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return routeUrl(request == null || request.getUrl() == null ? null : request.getUrl().toString());
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl("file:///android_asset/zeus/interface.html");
    }

    public static final class Bridge {
        private final Activity activity;
        private volatile String pendingAddress = "";
        private volatile String pairingMessage = "";
        private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
                try {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null || pendingAddress.isEmpty() || !pendingAddress.equals(device.getAddress())) return;
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        pendingAddress = "";
                        pairingMessage = "";
                        ZEUSPBAP.start(device);
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        pendingAddress = "";
                        pairingMessage = "لم يكتمل الاقتران. تأكد من قبول طلب الاقتران على الجهاز الآخر.";
                    }
                } catch (SecurityException ignored) {}
            }
        };

        Bridge(Activity a) {
            activity = a;
            try {
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                if (Build.VERSION.SDK_INT >= 33)
                    activity.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else activity.registerReceiver(bondReceiver, filter);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public void scan() {
            activity.runOnUiThread(() -> {
                clearNearby();
                if (!ensureScanPermissions()) return;
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null) return;
                    if (!adapter.isEnabled()) {
                        activity.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 73);
                        return;
                    }
                    if (adapter.isDiscovering()) adapter.cancelDiscovery();
                    Method find = activity.getClass().getMethod("findDevices");
                    find.invoke(activity);
                } catch (SecurityException denied) {
                    pairingMessage = "إذن Bluetooth غير متاح. اسمح بالإذن عند طلبه ثم أعد البحث.";
                } catch (Exception error) {
                    pairingMessage = "تعذّر تشغيل البحث عن الأجهزة القريبة.";
                }
            });
        }

        private boolean ensureScanPermissions() {
            if (Build.VERSION.SDK_INT < 23) return true;
            try {
                int target = activity.getApplicationInfo().targetSdkVersion;
                if (Build.VERSION.SDK_INT >= 31 && target >= 31) {
                    ArrayList<String> missing = new ArrayList<>();
                    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                        missing.add(Manifest.permission.BLUETOOTH_SCAN);
                    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                        missing.add(Manifest.permission.BLUETOOTH_CONNECT);
                    if (!missing.isEmpty()) {
                        activity.requestPermissions(missing.toArray(new String[0]), 41);
                        return false;
                    }
                } else if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 41);
                    return false;
                }
            } catch (Exception ignored) { return false; }
            return true;
        }

        private void clearNearby() {
            try {
                Field field = activity.getClass().getDeclaredField("availableDevices");
                field.setAccessible(true);
                Object value = field.get(activity);
                if (value instanceof ArrayList) ((ArrayList<?>) value).clear();
                Method update = activity.getClass().getMethod("updateNearbyDeviceList");
                update.invoke(activity);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface public String discoveredDevices() {
            StringBuilder out = new StringBuilder("[");
            try {
                Field field = activity.getClass().getDeclaredField("availableDevices");
                field.setAccessible(true);
                Object value = field.get(activity);
                if (value instanceof ArrayList) {
                    boolean first = true;
                    for (Object item : (ArrayList<?>) value) {
                        if (!(item instanceof BluetoothDevice)) continue;
                        BluetoothDevice d = (BluetoothDevice) item;
                        if (!first) out.append(",");
                        first = false;
                        out.append("{\"name\":\"").append(escape(safeName(d))).append("\",\"address\":\"")
                           .append(escape(d.getAddress())).append("\",\"paired\":")
                           .append(d.getBondState() == BluetoothDevice.BOND_BONDED).append("}");
                    }
                }
            } catch (Exception ignored) {}
            return out.append("]").toString();
        }

        @JavascriptInterface public boolean isDiscovering() {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                return adapter != null && adapter.isDiscovering();
            } catch (Exception ignored) { return false; }
        }

        @JavascriptInterface public String connect(String address) {
            if (address == null || !address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return "error";
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) return "error";
                BluetoothDevice device = adapter.getRemoteDevice(address);
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    pendingAddress = "";
                    pairingMessage = "";
                    ZEUSPBAP.start(device);
                    return "connecting";
                }
                pendingAddress = address;
                pairingMessage = "في انتظار قبول الاقتران على الجهاز الآخر...";
                activity.runOnUiThread(() -> {
                    try {
                        if (adapter.isDiscovering()) adapter.cancelDiscovery();
                        if (!device.createBond()) {
                            pendingAddress = "";
                            pairingMessage = "تعذّر إرسال طلب الاقتران.";
                        }
                    } catch (Exception error) {
                        pendingAddress = "";
                        pairingMessage = "تعذّر بدء الاقتران. تحقق من قرب الجهاز.";
                    }
                });
                return "pairing";
            } catch (SecurityException denied) {
                pairingMessage = "إذن Bluetooth غير متاح. أعد البحث واسمح بإذن Bluetooth.";
                return "error";
            } catch (Exception error) {
                pairingMessage = "تعذّر الوصول إلى الجهاز المحدد.";
                return "error";
            }
        }

        @JavascriptInterface public String transferStatus() {
            if (!pendingAddress.isEmpty()) {
                try {
                    org.json.JSONObject result = new org.json.JSONObject();
                    result.put("state", "pairing");
                    result.put("message", pairingMessage);
                    result.put("count", 0);
                    return result.toString();
                } catch (Exception ignored) {}
            }
            return ZEUSPBAP.statusJson();
        }

        @JavascriptInterface public String contacts() { return ZEUSPBAP.contactsJson(); }

        @JavascriptInterface public void cancelTransfer() {
            pendingAddress = "";
            pairingMessage = "";
            ZEUSPBAP.cancel();
        }

        @JavascriptInterface public void closeApp() {
            activity.runOnUiThread(() -> activity.finish());
        }

        private static String safeName(BluetoothDevice device) {
            try {
                String name = device.getName();
                return name == null || name.trim().isEmpty() ? "Bluetooth Device" : name;
            } catch (Exception ignored) { return "Bluetooth Device"; }
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", " ").replace("\r", " ");
        }
    }
}
