from pathlib import Path

# One-time transformer for legacy source areas that are safer to patch mechanically than
# to replace wholesale through the GitHub API. Re-running is intentionally idempotent.


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Expected source fragment not found in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


usb = Path("app/src/main/java/dev/dworks/apps/anexplorer/provider/UsbStorageProvider.java")
replace_once(
    usb,
    'import androidx.collection.ArrayMap;\n',
    'import androidx.collection.ArrayMap;\nimport androidx.core.content.ContextCompat;\n',
)
replace_once(
    usb,
    'private static final String ACTION_USB_PERMISSION = "dev.dworks.apps.anexplorer.action.USB_PERMISSION";',
    'private static final String ACTION_USB_PERMISSION = BuildConfig.APPLICATION_ID + ".action.USB_PERMISSION";',
)
replace_once(
    usb,
    '''        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        context.registerReceiver(mUsbReceiver, filter);
''',
    '''        // Keep the app-private USB permission callback separate from system USB broadcasts.
        // Android 13+ requires an explicit exported/not-exported choice for dynamic receivers.
        IntentFilter permissionFilter = new IntentFilter(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(
                context,
                mUsbReceiver,
                permissionFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        IntentFilter systemFilter = new IntentFilter();
        systemFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        systemFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(
                context,
                mUsbReceiver,
                systemFilter,
                ContextCompat.RECEIVER_EXPORTED);
''',
)
replace_once(
    usb,
    '''    public void requestPermission(UsbDevice device){
        PendingIntent permissionIntent = PendingIntent.getBroadcast(getContext(), 0, new Intent(
                ACTION_USB_PERMISSION), 0);
        usbManager.requestPermission(device, permissionIntent);
    }
''',
    '''    public void requestPermission(UsbDevice device){
        Intent permissionResult = new Intent(ACTION_USB_PERMISSION)
                .setPackage(getContext().getPackageName());
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                getContext(),
                0,
                permissionResult,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        usbManager.requestPermission(device, permissionIntent);
    }
''',
)

external = Path("app/src/main/java/dev/dworks/apps/anexplorer/provider/ExternalStorageProvider.java")
replace_once(
    external,
    '''        updateSettings();
        for (File file : parent.listFiles()) {
            includeFile(result, null, file);
        }
        return result;
''',
    '''        updateSettings();
        final File[] children = parent.listFiles();
        if (children == null) {
            // The directory may temporarily be unavailable or access may not yet have been
            // granted. Return an empty cursor instead of crashing the provider process.
            return result;
        }
        for (File file : children) {
            includeFile(result, null, file);
        }
        return result;
''',
)

settings = Path("app/src/main/java/dev/dworks/apps/anexplorer/setting/SettingsActivity.java")
replace_once(
    settings,
    'return PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_PIN, "") != "";',
    'return !TextUtils.isEmpty(PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_PIN, ""));',
)

print("Source modernization replacements applied successfully.")
