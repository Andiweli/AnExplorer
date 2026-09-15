package dev.dworks.apps.anexplorer;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import dev.dworks.apps.anexplorer.fragment.HomeFragment;
import dev.dworks.apps.anexplorer.misc.PermissionUtil;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.model.DocumentInfo;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.provider.ExternalStorageProvider;

/**
 * AAOS entry point for AnExplorer.
 *
 * The original app predates the current AndroidX lifecycle. On modern AndroidX,
 * ComponentActivity can call invalidateMenu() while super.onCreate() is still running,
 * before DocumentsActivity has initialized its own State. On Automotive this resulted in
 * an immediate startup NPE. This wrapper keeps the original behavior once initialization
 * is complete, while making the early lifecycle and storage-permission path defensive.
 */
public class AutomotiveDocumentsActivity extends DocumentsActivity {

    private boolean storagePromptShown;
    private Thread.UncaughtExceptionHandler previousExceptionHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Renault/AAOS does not expose adb/logcat to us. Install the recorder before the
        // legacy activity enters super.onCreate() so even very early UI/provider failures
        // leave a useful stack trace. Writing the report is best-effort and never replaces
        // Android's normal crash handling.
        installCrashRecorder();
        super.onCreate(savedInstanceState);
    }

    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current == this::handleUncaughtException) {
            return;
        }
        previousExceptionHandler = current;
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                writeCrashReport(thread, throwable);
                if (previousExceptionHandler != null) {
                    previousExceptionHandler.uncaughtException(thread, throwable);
                }
            }
        });
    }

    private void handleUncaughtException(Thread thread, Throwable throwable) {
        writeCrashReport(thread, throwable);
        if (previousExceptionHandler != null) {
            previousExceptionHandler.uncaughtException(thread, throwable);
        }
    }

    private void writeCrashReport(Thread thread, Throwable throwable) {
        final String report = buildCrashReport(thread, throwable);
        final String fileName = "AnExplorer-AAOS-crash-" + System.currentTimeMillis() + ".txt";

        // Always try app-private external storage first.
        try {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                writeFile(new File(dir, fileName), report);
            }
        } catch (Throwable ignored) {
        }

        // MediaStore lets a modern app create its own file in Downloads without broad storage
        // permission. This gives us a report that can be opened with the vehicle's file manager.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            OutputStream output = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    output = getContentResolver().openOutputStream(uri, "w");
                    if (output != null) {
                        output.write(report.getBytes("UTF-8"));
                        output.flush();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (output != null) {
                    try {
                        output.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private String buildCrashReport(Thread thread, Throwable throwable) {
        StringWriter stack = new StringWriter();
        PrintWriter printer = new PrintWriter(stack);
        throwable.printStackTrace(printer);
        printer.flush();

        StringBuilder result = new StringBuilder();
        result.append("AnExplorer AAOS crash report\n");
        result.append("Package: ").append(getPackageName()).append('\n');
        result.append("Version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        result.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        result.append("Device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        result.append("Thread: ").append(thread != null ? thread.getName() : "unknown").append("\n\n");
        result.append(stack.toString());
        return result.toString();
    }

    private void writeFile(File file, String report) throws Exception {
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file, false);
            output.write(report.getBytes("UTF-8"));
            output.flush();
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }

    @Override
    public void invalidateMenu() {
        if (super.getDisplayState() == null) {
            return;
        }
        super.invalidateMenu();
    }

    @Override
    public RootInfo getCurrentRoot() {
        State state = super.getDisplayState();
        if (state == null || state.stack == null) {
            return null;
        }
        try {
            return super.getCurrentRoot();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Override
    public DocumentInfo getCurrentDirectory() {
        State state = super.getDisplayState();
        if (state == null || state.stack == null) {
            return null;
        }
        return super.getCurrentDirectory();
    }

    @Override
    public boolean isCreateSupported() {
        if (super.getDisplayState() == null) {
            return false;
        }
        return super.isCreateSupported();
    }

    /**
     * Do not jump into an OEM Settings activity automatically during the first frame.
     * Several AAOS implementations either do not expose MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
     * or immediately close that screen. The user gets a stable app first and may explicitly
     * request the permission from the snackbar.
     */
    @Override
    protected void requestStoragePermissions() {
        if (PermissionUtil.hasStoragePermission(this)) {
            again();
            return;
        }

        if (storagePromptShown) {
            return;
        }
        storagePromptShown = true;

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!Utils.isActivityAlive(AutomotiveDocumentsActivity.this)
                        || PermissionUtil.hasStoragePermission(AutomotiveDocumentsActivity.this)) {
                    return;
                }
                Utils.showRetrySnackBar(
                        AutomotiveDocumentsActivity.this,
                        "Dateizugriff aktivieren, um alle Ordner anzuzeigen.",
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                AutomotiveDocumentsActivity.super.requestStoragePermissions();
                            }
                        });
            }
        }, 1200L);
    }

    /**
     * Refresh storage roots defensively after the permission screen returns. The legacy
     * implementation dereferenced getCurrentRoot() without checking for a provider that has
     * not finished publishing its roots yet.
     */
    @Override
    public void again() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        try {
            RootsCache.updateRoots(this, ExternalStorageProvider.AUTHORITY);
        } catch (RuntimeException ignored) {
            // Some OEM provider implementations are not available during the first frame.
        }
        final RootsCache roots = DocumentsApplication.getRootsCache(this);
        if (roots == null) {
            return;
        }
        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!Utils.isActivityAlive(AutomotiveDocumentsActivity.this)) {
                    return;
                }

                roots.updateAsync();
                final RootInfo root = getCurrentRoot();
                if (root != null && root.isHome()) {
                    HomeFragment homeFragment = HomeFragment.get(getFragmentManager());
                    if (homeFragment != null) {
                        homeFragment.reloadData();
                    }
                }
            }
        }, 500L);
    }
}
