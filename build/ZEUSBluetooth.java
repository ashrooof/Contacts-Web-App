package grant.bt.contact.transfer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;

public final class ZEUSBluetooth {
    private ZEUSBluetooth() {}

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void call(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        m.invoke(target);
    }

    public static void loadPaired(Activity activity) {
        if (activity == null) return;
        try {
            BluetoothAdapter adapter = (BluetoothAdapter) field(activity, "bluetoothAdapter");
            ArrayList<BluetoothDevice> devices = (ArrayList<BluetoothDevice>) field(activity, "availableDevices");
            if (adapter == null || devices == null) return;
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice candidate : bonded) {
                    boolean exists = false;
                    for (BluetoothDevice current : devices) {
                        if (current != null && candidate.getAddress().equalsIgnoreCase(current.getAddress())) {
                            exists = true; break;
                        }
                    }
                    if (!exists) devices.add(candidate);
                }
            }
            call(activity, "updateNearbyDeviceList");
        } catch (SecurityException denied) {
            notify(activity, "Bluetooth permission is required to show paired devices");
        } catch (Exception ignored) {}
    }

    public static void refresh(Activity activity) {
        if (activity == null) return;
        loadPaired(activity);
        try {
            BluetoothAdapter adapter = (BluetoothAdapter) field(activity, "bluetoothAdapter");
            if (adapter != null && adapter.isEnabled()) call(activity, "findDevices");
            else notify(activity, "Turn on Bluetooth to search for devices");
        } catch (SecurityException denied) {
            notify(activity, "Bluetooth permission is required to search");
        } catch (Exception ignored) {}
    }

    private static void notify(Activity activity, String message) {
        try {
            Method m = activity.getClass().getMethod("showSnackBar", String.class);
            m.invoke(activity, message);
        } catch (Exception ignored) {}
    }
}
