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
replace_once(
    usb,
    '''            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String deviceName = usbDevice.getDeviceName();
            if (UsbStorageProvider.ACTION_USB_PERMISSION.equals(action)) {
''',
    '''            UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (usbDevice == null) {
                return;
            }
            if (UsbStorageProvider.ACTION_USB_PERMISSION.equals(action)) {
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

# The original source enumerated storage through hidden StorageManager APIs and reflected over
# private framework fields. Hidden-API enforcement on current Android/AAOS can block that path.
# Replace only the actively used getStorageMounts() implementation with public SDK APIs.
storage = Path("app/src/main/java/dev/dworks/apps/anexplorer/misc/StorageUtils.java")
storage_text = storage.read_text(encoding="utf-8")
modern_marker = "private File findExternalStorageRoot(File appSpecificDir)"
if modern_marker not in storage_text:
    method_start = storage_text.index("    public List<StorageVolume> getStorageMounts() {")
    method_end = storage_text.index("    private DiskInfo getDiskInfo", method_start)
    modern_method = '''    /**
     * Enumerates app-visible external storage volumes using only public Android SDK APIs.
     *
     * The legacy implementation reflected into StorageManager#getVolumeList() and private
     * StorageVolume fields. That is unreliable once hidden-API enforcement is active. The
     * app-specific external directories give us stable mount roots on API 23+, while the
     * public platform StorageVolume objects provide labels, UUIDs and state on API 24+.
     */
    public List<StorageVolume> getStorageMounts() {
        final List<StorageVolume> mounts = new ArrayList<>();
        final File[] appSpecificDirs = mContext.getExternalFilesDirs(null);
        if (appSpecificDirs == null) {
            return mounts;
        }

        List<android.os.storage.StorageVolume> platformVolumes = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mStorageManager != null) {
            platformVolumes = mStorageManager.getStorageVolumes();
        }

        for (int index = 0; index < appSpecificDirs.length; index++) {
            final File appSpecificDir = appSpecificDirs[index];
            final File root = findExternalStorageRoot(appSpecificDir);
            if (root == null) {
                continue;
            }

            final boolean primary = index == 0 || samePath(root, Environment.getExternalStorageDirectory());
            final android.os.storage.StorageVolume platformVolume =
                    findPlatformStorageVolume(platformVolumes, root, primary);

            final boolean emulated;
            final boolean removable;
            final String description;
            final String uuid;
            final String state;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && platformVolume != null) {
                emulated = platformVolume.isEmulated();
                removable = platformVolume.isRemovable();
                description = platformVolume.getDescription(mContext);
                uuid = platformVolume.getUuid();
                state = platformVolume.getState();
            } else {
                boolean localEmulated = primary;
                boolean localRemovable = !primary;
                try {
                    localEmulated = Environment.isExternalStorageEmulated(root);
                    localRemovable = Environment.isExternalStorageRemovable(root);
                } catch (IllegalArgumentException ignored) {
                    // Some vendor builds do not expose every secondary path to Environment.
                }
                emulated = localEmulated;
                removable = localRemovable;
                description = primary
                        ? mContext.getString(R.string.root_internal_storage)
                        : root.getName();
                uuid = primary ? null : root.getName();
                String localState;
                try {
                    localState = Environment.getExternalStorageState(root);
                } catch (IllegalArgumentException ignored) {
                    localState = Environment.MEDIA_UNKNOWN;
                }
                state = localState;
            }

            final int storageId = primary
                    ? StorageVolume.STORAGE_ID_PRIMARY
                    : VolumeInfo.buildStableMtpStorageId(uuid);
            final StorageVolume volume = new StorageVolume(
                    storageId,
                    root,
                    description,
                    primary,
                    removable,
                    emulated,
                    0,
                    false,
                    0);
            volume.mId = primary ? "primary" : (uuid != null ? uuid : root.getName());
            volume.mFsUuid = uuid;
            volume.mUuid = uuid;
            volume.mUserLabel = description;
            volume.mState = state;
            mounts.add(volume);
        }
        return mounts;
    }

    private File findExternalStorageRoot(File appSpecificDir) {
        File current = appSpecificDir;
        while (current != null) {
            if ("Android".equals(current.getName())) {
                return current.getParentFile();
            }
            current = current.getParentFile();
        }
        return null;
    }

    private boolean samePath(File first, File second) {
        if (first == null || second == null) {
            return false;
        }
        try {
            return first.getCanonicalFile().equals(second.getCanonicalFile());
        } catch (Exception ignored) {
            return first.getAbsolutePath().equals(second.getAbsolutePath());
        }
    }

    private android.os.storage.StorageVolume findPlatformStorageVolume(
            List<android.os.storage.StorageVolume> platformVolumes,
            File root,
            boolean primary) {
        if (platformVolumes == null) {
            return null;
        }
        for (android.os.storage.StorageVolume volume : platformVolumes) {
            if (primary && volume.isPrimary()) {
                return volume;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                final File directory = volume.getDirectory();
                if (directory != null && samePath(root, directory)) {
                    return volume;
                }
            }
            if (!primary) {
                final String uuid = volume.getUuid();
                if (uuid != null && uuid.equalsIgnoreCase(root.getName())) {
                    return volume;
                }
            }
        }
        return null;
    }

'''
    storage.write_text(
        storage_text[:method_start] + modern_method + storage_text[method_end:],
        encoding="utf-8",
    )

print("Source modernization replacements applied successfully.")
