package grant.bt.contact.transfer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import java.util.Set;

public final class ZEUSBluetooth {
    private ZEUSBluetooth() {}

    public static void loadPaired(DeviceListActivity activity) {
        if (activity == null || activity.bluetoothAdapter == null || activity.availableDevices == null) return;
        try {
            Set<BluetoothDevice> bonded = activity.bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) {
                    if (device != null && !DeviceListActivity.isDeviceOnthelist(activity.availableDevices, device)) {
                        activity.availableDevices.add(device);
                    }
                }
            }
            activity.updateNearbyDeviceList();
        } catch (SecurityException ignored) {
            activity.showSnackBar("Bluetooth permission is required to show paired devices");
        }
    }

    public static void refresh(DeviceListActivity activity) {
        if (activity == null) return;
        loadPaired(activity);
        try {
            if (activity.bluetoothAdapter != null && activity.bluetoothAdapter.isEnabled()) {
                activity.findDevices();
            } else {
                activity.showSnackBar("Turn on Bluetooth to search for devices");
            }
        } catch (SecurityException ignored) {
            activity.showSnackBar("Bluetooth permission is required to search");
        }
    }
}
