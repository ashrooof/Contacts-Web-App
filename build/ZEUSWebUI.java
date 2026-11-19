package grant.bt.contact.transfer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
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
import java.util.Set;

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
          if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
          }
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
    Bridge(Activity a) { activity = a; }

    @JavascriptInterface public String pairedDevices() {
      StringBuilder out = new StringBuilder("[");
      try {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        Set<BluetoothDevice> devices = a == null ? null : a.getBondedDevices();
        if (devices != null) {
          boolean first = true;
          for (BluetoothDevice d : devices) {
            if (!first) out.append(",");
            first = false;
            out.append("{\"name\":\"").append(escape(safeName(d))).append("\",\"address\":\"")
               .append(escape(d.getAddress())).append("\",\"paired\":true}");
          }
        }
      } catch (Exception ignored) {}
      return out.append("]").toString();
    }

    @JavascriptInterface public String discoveredDevices() {
      StringBuilder out = new StringBuilder("[");
      try {
        Field f = activity.getClass().getDeclaredField("availableDevices");
        f.setAccessible(true);
        Object value = f.get(activity);
        if (value instanceof ArrayList) {
          boolean first = true;
          for (Object item : (ArrayList<?>) value) {
            if (!(item instanceof BluetoothDevice)) continue;
            BluetoothDevice d = (BluetoothDevice) item;
            if (!first) out.append(",");
            first = false;
            boolean paired = d.getBondState() == BluetoothDevice.BOND_BONDED;
            out.append("{\"name\":\"").append(escape(safeName(d))).append("\",\"address\":\"")
               .append(escape(d.getAddress())).append("\",\"paired\":").append(paired).append("}");
          }
        }
      } catch (Exception ignored) {}
      return out.append("]").toString();
    }

    @JavascriptInterface public boolean isDiscovering() {
      try {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        return a != null && a.isDiscovering();
      } catch (Exception ignored) { return false; }
    }

    @JavascriptInterface public void scan() {
      activity.runOnUiThread(() -> {
        try {
          Field f = activity.getClass().getDeclaredField("availableDevices");
          f.setAccessible(true);
          Object value = f.get(activity);
          if (value instanceof ArrayList) ((ArrayList<?>) value).clear();
          try {
            Method update = activity.getClass().getMethod("updateNearbyDeviceList");
            update.invoke(activity);
          } catch (Exception ignored) {}
          Method m = activity.getClass().getMethod("findDevices");
          m.invoke(activity);
        } catch (Exception ignored) {}
      });
    }

    @JavascriptInterface public void connect(String address) {
      if (address == null || !address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return;
      activity.runOnUiThread(() -> {
        try {
          BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
          BluetoothDevice d = a == null ? null : a.getRemoteDevice(address);
          if (d == null) return;
          if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
            Method m = activity.getClass().getMethod("connectToPhonebook", BluetoothDevice.class);
            m.invoke(activity, d);
          } else {
            Method m = activity.getClass().getMethod("pairDevice", BluetoothDevice.class);
            m.invoke(activity, d);
          }
        } catch (Exception ignored) {}
      });
    }

    @JavascriptInterface public void requestContactsPermission() {
      activity.runOnUiThread(() -> {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
          String read = "android.permission.READ_CONTACTS";
          String write = "android.permission.WRITE_CONTACTS";
          boolean rg = activity.checkSelfPermission(read) == android.content.pm.PackageManager.PERMISSION_GRANTED;
          boolean wg = activity.checkSelfPermission(write) == android.content.pm.PackageManager.PERMISSION_GRANTED;
          if (rg && wg) return;
          activity.requestPermissions(new String[] { read, write }, 2);
        } catch (Exception ignored) {}
      });
    }
    @JavascriptInterface public void closeApp() {
      activity.runOnUiThread(() -> activity.finish());
    }

    private static String safeName(BluetoothDevice d) {
      try {
        String n = d.getName();
        return n == null || n.trim().isEmpty() ? "Bluetooth Device" : n;
      } catch (Exception ignored) { return "Bluetooth Device"; }
    }
    private static String escape(String s) {
      return s.replace("\\", "\\\\").replace("\"", "\\\"")
              .replace("\n", " ").replace("\r", " ");
    }
  }
  public void requestContactPermissions() {
    if (Build.VERSION.SDK_INT < 23) return;
    try {
      String read = "android.permission.READ_CONTACTS";
      String write = "android.permission.WRITE_CONTACTS";
      boolean rg = checkSelfPermission(read) == android.content.pm.PackageManager.PERMISSION_GRANTED;
      boolean wg = checkSelfPermission(write) == android.content.pm.PackageManager.PERMISSION_GRANTED;
      if (rg && wg) { showSnackBar("صلاحية جهات الاتصال مفعّلة بالفعل"); return; }
      requestPermissions(new String[] { read, write }, 2);
    } catch (Exception ignored) { showSnackBar("تعذر طلب صلاحية جهات الاتصال"); }
  }
  private void showSnackBar(String message) {
    try { Method m = getClass().getMethod("showSnackBar", String.class); m.invoke(this, message); }
    catch (Exception ignored) {}
  }

}