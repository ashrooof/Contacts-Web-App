package grant.bt.contact.transfer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.util.Set;

public final class ZEUSWebUI {
  private ZEUSWebUI() {}
  public static void attach(Activity activity) {
    if (activity == null) return;
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
    settings.setDefaultTextEncodingName("UTF-8");
    web.setWebChromeClient(new WebChromeClient());
    web.addJavascriptInterface(new Bridge(activity), "ZEUS");
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
            String name = d.getName();
            if (name == null || name.trim().isEmpty()) name = "Bluetooth Device";
            out.append("{\"name\":\"").append(escape(name)).append("\",\"address\":\"")
               .append(escape(d.getAddress())).append("\"}");
          }
        }
      } catch (Exception ignored) {}
      return out.append("]").toString();
    }
    @JavascriptInterface public void scan() {
      activity.runOnUiThread(() -> {
        try {
          Method m = activity.getClass().getMethod("findDevices");
          m.invoke(activity);
        } catch (Exception ignored) {}
      });
    }
    @JavascriptInterface public void connect(String address) {
      activity.runOnUiThread(() -> {
        try {
          BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
          BluetoothDevice d = a == null ? null : a.getRemoteDevice(address);
          if (d == null) return;
          Method m = activity.getClass().getMethod("connectToPhonebook", BluetoothDevice.class);
          m.invoke(activity, d);
        } catch (Exception ignored) {}
      });
    }
    @JavascriptInterface public void closeApp() {
      activity.runOnUiThread(() -> activity.finish());
    }
    private static String escape(String s) {
      return s.replace("\\", "\\\\").replace("\"", "\\\"")
              .replace("\n", " ").replace("\r", " ");
    }
  }
}
