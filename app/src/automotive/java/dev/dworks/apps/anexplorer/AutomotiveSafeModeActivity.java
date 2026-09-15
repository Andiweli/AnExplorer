package dev.dworks.apps.anexplorer;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Minimal, independent AAOS launcher used to isolate Renault startup/storage issues.
 *
 * This activity intentionally does not use DocumentsActivity, DocumentsUI, RootsCache,
 * DocumentsProvider or any of the old navigation state. It is a read-only local file browser
 * built only from Android framework classes. Once this path is proven stable on the vehicle,
 * normal file operations can be added back one by one.
 */
public class AutomotiveSafeModeActivity extends Activity {

    private static final int REQUEST_STORAGE = 1201;
    private static final String STATE_PATH = "safe_mode_path";
    private static final String OP_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage";

    private final List<File> entries = new ArrayList<File>();

    private TextView pathView;
    private TextView statusView;
    private Button upButton;
    private Button permissionButton;
    private ListView listView;
    private FileListAdapter adapter;
    private File rootDirectory;
    private File currentDirectory;
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AutomotiveSafeApplication.log(this, "02 AutomotiveSafeModeActivity.onCreate begin");
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        probeStorageManager();
        rootDirectory = resolveRootDirectory();

        String restoredPath = savedInstanceState != null
                ? savedInstanceState.getString(STATE_PATH)
                : null;
        if (restoredPath != null) {
            File restored = new File(restoredPath);
            currentDirectory = isInsideRoot(restored) ? restored : rootDirectory;
        } else {
            currentDirectory = rootDirectory;
        }

        setContentView(createContentView());
        AutomotiveSafeApplication.log(this, "05 UI created");

        logStorageAccessState("create");
        updatePermissionUi();
        loadDirectory(currentDirectory);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pathView != null) {
            logStorageAccessState("resume");
            updatePermissionUi();
            loadDirectory(currentDirectory);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentDirectory != null) {
            outState.putString(STATE_PATH, currentDirectory.getAbsolutePath());
        }
    }

    @Override
    public void onBackPressed() {
        if (currentDirectory != null && rootDirectory != null
                && !sameFile(currentDirectory, rootDirectory)) {
            File parent = currentDirectory.getParentFile();
            if (parent != null && isInsideRoot(parent)) {
                loadDirectory(parent);
                return;
            }
        }
        super.onBackPressed();
    }

    private void probeStorageManager() {
        try {
            StorageManager manager = (StorageManager) getSystemService(STORAGE_SERVICE);
            if (manager == null) {
                AutomotiveSafeApplication.log(this, "03 StorageManager unavailable");
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                int volumeCount = manager.getStorageVolumes().size();
                AutomotiveSafeApplication.log(this,
                        "03 StorageManager OK volumes=" + volumeCount);
            } else {
                AutomotiveSafeApplication.log(this, "03 StorageManager OK");
            }
        } catch (Throwable error) {
            AutomotiveSafeApplication.log(this,
                    "03 StorageManager failed: " + error.getClass().getName()
                            + ": " + error.getMessage());
        }
    }

    private void logStorageAccessState(String reason) {
        boolean allFiles = hasBroadStorageAccess();
        String appOp = getManageStorageAppOpMode();
        String rootState = rootDirectory == null
                ? "root=null"
                : "root=" + rootDirectory.getAbsolutePath()
                        + " exists=" + rootDirectory.exists()
                        + " canRead=" + rootDirectory.canRead()
                        + " canWrite=" + rootDirectory.canWrite();

        AutomotiveSafeApplication.log(this,
                "ACCESS " + reason
                        + " api=" + Build.VERSION.SDK_INT
                        + " allFiles=" + allFiles
                        + " appOp=" + appOp
                        + " " + rootState);
    }

    private String getManageStorageAppOpMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "legacy";
        }

        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (appOps == null) {
                return "no-service";
            }
            int mode = appOps.unsafeCheckOpNoThrow(
                    OP_MANAGE_EXTERNAL_STORAGE,
                    Process.myUid(),
                    getPackageName());
            return String.valueOf(mode);
        } catch (Throwable error) {
            return "error:" + error.getClass().getSimpleName();
        }
    }

    private File resolveRootDirectory() {
        File root = null;
        try {
            root = Environment.getExternalStorageDirectory();
        } catch (Throwable error) {
            AutomotiveSafeApplication.log(this,
                    "04 Environment root failed: " + error.getClass().getName());
        }

        if (root == null || !root.exists()) {
            try {
                File external = getExternalFilesDir(null);
                if (external != null) {
                    root = external;
                }
            } catch (Throwable ignored) {
            }
        }

        if (root == null) {
            root = getFilesDir();
        }

        AutomotiveSafeApplication.log(this,
                "04 Root selected: " + root.getAbsolutePath()
                        + " broadAccess=" + hasBroadStorageAccess());
        return root;
    }

    private View createContentView() {
        final int background = Color.rgb(18, 18, 18);
        final int panel = Color.rgb(31, 31, 31);
        final int primaryText = Color.WHITE;
        final int secondaryText = Color.rgb(190, 190, 190);
        final int accent = Color.rgb(255, 61, 23);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(10), dp(6), dp(10), dp(6));
        toolbar.setBackgroundColor(panel);

        TextView title = new TextView(this);
        title.setText("AnExplorer · AAOS Safe Mode");
        title.setTextColor(primaryText);
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1f));

        upButton = makeToolbarButton("AUF", accent);
        upButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (currentDirectory == null || rootDirectory == null) {
                    return;
                }
                File parent = currentDirectory.getParentFile();
                if (parent != null && isInsideRoot(parent)) {
                    loadDirectory(parent);
                }
            }
        });
        toolbar.addView(upButton);

        Button refreshButton = makeToolbarButton("NEU LADEN", accent);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                logStorageAccessState("manual-refresh");
                loadDirectory(currentDirectory);
            }
        });
        toolbar.addView(refreshButton);

        permissionButton = makeToolbarButton("ZUGRIFF", accent);
        permissionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestStorageAccessManually();
            }
        });
        toolbar.addView(permissionButton);

        Button logButton = makeToolbarButton("LOG", accent);
        logButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                logStorageAccessState("log-export");
                boolean exported = AutomotiveSafeApplication.exportStartupLogToDownloads(
                        AutomotiveSafeModeActivity.this);
                Toast.makeText(
                        AutomotiveSafeModeActivity.this,
                        exported ? "Startdiagnose nach Downloads exportiert."
                                : "Startdiagnose konnte nicht exportiert werden.",
                        Toast.LENGTH_LONG).show();
            }
        });
        toolbar.addView(logButton);

        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pathView = new TextView(this);
        pathView.setTextColor(primaryText);
        pathView.setTextSize(17f);
        pathView.setSingleLine(false);
        pathView.setPadding(dp(6), dp(12), dp(6), dp(6));
        root.addView(pathView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(secondaryText);
        statusView.setTextSize(15f);
        statusView.setPadding(dp(6), dp(2), dp(6), dp(10));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setBackgroundColor(background);
        listView.setCacheColorHint(background);
        adapter = new FileListAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            File entry = entries.get(position);
            if (entry.isDirectory()) {
                loadDirectory(entry);
            } else {
                Toast.makeText(
                        AutomotiveSafeModeActivity.this,
                        entry.getName() + "\n" + entry.getAbsolutePath(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private Button makeToolbarButton(String text, int accent) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14f);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(dp(52));
        button.setMinWidth(dp(92));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        params.setMargins(dp(6), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void updatePermissionUi() {
        boolean granted = hasBroadStorageAccess();
        permissionButton.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    private void setStatus(String text, boolean warning) {
        statusView.setText(text);
        statusView.setTextColor(warning
                ? Color.rgb(255, 190, 90)
                : Color.rgb(190, 190, 190));
    }

    private boolean hasBroadStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestStorageAccessManually() {
        AutomotiveSafeApplication.log(this,
                "PERMISSION user requested storage access currentAllFiles="
                        + hasBroadStorageAccess()
                        + " appOp=" + getManageStorageAppOpMode());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent appIntent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                appIntent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(appIntent);
                return;
            } catch (Throwable error) {
                AutomotiveSafeApplication.log(this,
                        "PERMISSION per-app settings failed: "
                                + error.getClass().getName() + ": " + error.getMessage());
            }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            } catch (Throwable error) {
                AutomotiveSafeApplication.log(this,
                        "PERMISSION global settings failed: "
                                + error.getClass().getName() + ": " + error.getMessage());
                Toast.makeText(this,
                        "Die AAOS-Einstellungen stellen den Dateizugriff nicht bereit.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE) {
            logStorageAccessState("permission-result");
            updatePermissionUi();
            loadDirectory(currentDirectory);
        }
    }

    private void loadDirectory(final File directory) {
        if (directory == null) {
            return;
        }

        final File normalized = directory.getAbsoluteFile();
        if (!isInsideRoot(normalized)) {
            return;
        }

        currentDirectory = normalized;
        pathView.setText(normalized.getAbsolutePath());
        upButton.setEnabled(!sameFile(normalized, rootDirectory));
        setStatus(hasBroadStorageAccess()
                        ? "Lese Verzeichnis … · Vollzugriff aktiv"
                        : "EINGESCHRÄNKTER ZUGRIFF · Inhalte können fehlen · ZUGRIFF drücken",
                !hasBroadStorageAccess());
        final int generation = ++loadGeneration;

        AutomotiveSafeApplication.log(this,
                "LOAD begin path=" + normalized.getAbsolutePath()
                        + " allFiles=" + hasBroadStorageAccess()
                        + " exists=" + normalized.exists()
                        + " dir=" + normalized.isDirectory()
                        + " canRead=" + normalized.canRead()
                        + " canWrite=" + normalized.canWrite());

        new Thread(new Runnable() {
            @Override
            public void run() {
                File[] files = null;
                Throwable failure = null;
                try {
                    files = normalized.listFiles();
                } catch (Throwable error) {
                    failure = error;
                }

                final File[] result = files;
                final Throwable error = failure;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != loadGeneration) {
                            return;
                        }
                        applyDirectoryResult(normalized, result, error);
                    }
                });
            }
        }, "AnExplorer-SafeMode-List").start();
    }

    private void applyDirectoryResult(File directory, File[] files, Throwable error) {
        entries.clear();

        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    if (left.isDirectory() != right.isDirectory()) {
                        return left.isDirectory() ? -1 : 1;
                    }
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });
            entries.addAll(Arrays.asList(files));
        }

        adapter.notifyDataSetChanged();

        boolean allFiles = hasBroadStorageAccess();
        boolean androidRestricted = isAndroidProtectedDirectory(directory);

        if (error != null) {
            setStatus("Lesefehler: " + error.getClass().getSimpleName()
                    + (error.getMessage() != null ? " · " + error.getMessage() : ""), true);
            AutomotiveSafeApplication.log(this,
                    "LOAD failed " + directory.getAbsolutePath() + " "
                            + error.getClass().getName() + ": " + error.getMessage());
        } else if (files == null) {
            if (androidRestricted) {
                setStatus(
                        "ANDROID-SCHUTZ · Inhalte unter Android/data bzw. Android/obb anderer Apps "
                                + "sind ab Android 11 systemseitig gesperrt.",
                        true);
            } else if (!allFiles) {
                setStatus(
                        "KEIN VOLLZUGRIFF · Android blendet Inhalte aus. Bitte ZUGRIFF drücken "
                                + "und 'Alle Dateien verwalten' erlauben.",
                        true);
            } else {
                setStatus("Dieses Verzeichnis kann auf dem Fahrzeug nicht gelesen werden.", true);
            }
            AutomotiveSafeApplication.log(this,
                    "LOAD returned null path=" + directory.getAbsolutePath()
                            + " allFiles=" + allFiles
                            + " androidRestricted=" + androidRestricted);
        } else if (androidRestricted) {
            setStatus(
                    entries.size() + " sichtbare Einträge · ANDROID-SCHUTZ: Inhalte von "
                            + "Android/data bzw. Android/obb anderer Apps können nicht gelesen werden.",
                    true);
            AutomotiveSafeApplication.log(this,
                    "LOAD Android protected path=" + directory.getAbsolutePath()
                            + " visibleEntries=" + entries.size()
                            + " allFiles=" + allFiles);
        } else if (!allFiles) {
            setStatus(
                    entries.size() + " sichtbare Einträge · KEIN VOLLZUGRIFF: Android kann Dateien "
                            + "ausblenden. Bitte ZUGRIFF drücken.",
                    true);
            AutomotiveSafeApplication.log(this,
                    "LOAD scoped path=" + directory.getAbsolutePath()
                            + " visibleEntries=" + entries.size());
        } else if (entries.isEmpty()) {
            setStatus("0 Einträge · Vollzugriff aktiv · Verzeichnis leer oder OEM-seitig geschützt.",
                    true);
            AutomotiveSafeApplication.log(this,
                    "LOAD empty despite allFiles path=" + directory.getAbsolutePath()
                            + " canRead=" + directory.canRead());
        } else {
            setStatus(entries.size() + " Einträge · Vollzugriff aktiv · Safe Mode nur lesend", false);
            AutomotiveSafeApplication.log(this,
                    "06 File list created path=" + directory.getAbsolutePath()
                            + " entries=" + entries.size()
                            + " allFiles=true");
            AutomotiveSafeApplication.log(this, "STARTUP COMPLETE");
        }

        updatePermissionUi();
    }

    private boolean isAndroidProtectedDirectory(File directory) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || directory == null
                || rootDirectory == null) {
            return false;
        }

        try {
            String rootPath = rootDirectory.getCanonicalPath();
            String path = directory.getCanonicalPath();
            String dataRoot = rootPath + File.separator + "Android" + File.separator + "data";
            String obbRoot = rootPath + File.separator + "Android" + File.separator + "obb";

            if (path.equals(dataRoot) || path.equals(obbRoot)) {
                return true;
            }

            String ownData = dataRoot + File.separator + getPackageName();
            String ownObb = obbRoot + File.separator + getPackageName();

            boolean inData = path.startsWith(dataRoot + File.separator)
                    && !(path.equals(ownData) || path.startsWith(ownData + File.separator));
            boolean inObb = path.startsWith(obbRoot + File.separator)
                    && !(path.equals(ownObb) || path.startsWith(ownObb + File.separator));
            return inData || inObb;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isInsideRoot(File file) {
        if (file == null || rootDirectory == null) {
            return false;
        }
        try {
            String rootPath = rootDirectory.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        } catch (Throwable ignored) {
            String rootPath = rootDirectory.getAbsolutePath();
            String filePath = file.getAbsolutePath();
            return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
        }
    }

    private boolean sameFile(File left, File right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Throwable ignored) {
            return left.getAbsoluteFile().equals(right.getAbsoluteFile());
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class FileListAdapter extends BaseAdapter {

        private final DateFormat dateFormat = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT);

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(AutomotiveSafeModeActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(6), dp(16), dp(6));
                row.setMinimumHeight(dp(62));

                TextView name = new TextView(AutomotiveSafeModeActivity.this);
                name.setTextColor(Color.WHITE);
                name.setTextSize(18f);
                name.setSingleLine(true);
                row.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView detail = new TextView(AutomotiveSafeModeActivity.this);
                detail.setTextColor(Color.rgb(175, 175, 175));
                detail.setTextSize(14f);
                detail.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                row.addView(detail, new LinearLayout.LayoutParams(
                        dp(300), ViewGroup.LayoutParams.WRAP_CONTENT));

                holder = new RowHolder(name, detail);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            File file = entries.get(position);
            holder.name.setText((file.isDirectory() ? "[DIR]  " : "[FILE] ") + file.getName());
            if (file.isDirectory()) {
                holder.detail.setText("Ordner");
            } else {
                holder.detail.setText(formatSize(file.length()) + " · "
                        + dateFormat.format(new Date(file.lastModified())));
            }
            return convertView;
        }
    }

    private static final class RowHolder {
        final TextView name;
        final TextView detail;

        RowHolder(TextView name, TextView detail) {
            this.name = name;
            this.detail = detail;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        if (value < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f KB", value);
        }
        value /= 1024.0;
        if (value < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f MB", value);
        }
        value /= 1024.0;
        return String.format(java.util.Locale.US, "%.1f GB", value);
    }
}
