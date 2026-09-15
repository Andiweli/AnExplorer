package dev.dworks.apps.anexplorer;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Minimal Application used by the Automotive safe-mode flavor.
 *
 * It deliberately does not inherit from DocumentsApplication. That keeps the legacy root cache,
 * document providers, updater integration and storage discovery out of the AAOS startup path.
 * The only startup responsibility here is durable diagnostics.
 */
public class AutomotiveSafeApplication extends Application {

    private static final String STARTUP_LOG = "anexplorer-startup.log";
    private static final String PUBLIC_STARTUP_LOG = "AnExplorer-AAOS-startup.txt";
    private static final Object LOG_LOCK = new Object();

    private Thread.UncaughtExceptionHandler previousExceptionHandler;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        installCrashRecorder();
        log(this, "00 Application.attachBaseContext");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        log(this, "01 Application.onCreate");
    }

    private void installCrashRecorder() {
        previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    writeCrashReport(AutomotiveSafeApplication.this, thread, throwable);
                } catch (Throwable ignored) {
                }

                if (previousExceptionHandler != null) {
                    previousExceptionHandler.uncaughtException(thread, throwable);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    public static void log(Context context, String message) {
        if (context == null || message == null) {
            return;
        }

        String line = timestamp() + "  " + message + "\n";
        synchronized (LOG_LOCK) {
            File internalLog = new File(context.getFilesDir(), STARTUP_LOG);
            appendBestEffort(internalLog, line);

            try {
                File externalDir = context.getExternalFilesDir(null);
                if (externalDir != null) {
                    appendBestEffort(new File(externalDir, STARTUP_LOG), line);
                }
            } catch (Throwable ignored) {
            }

            // The Renault AAOS installation cannot browse the app-private Android/data directory.
            // Keep a continuously updated copy in the public Download folder instead. On Android
            // 10+ this uses MediaStore.Downloads, so creating our own diagnostic file does not
            // require MANAGE_EXTERNAL_STORAGE or administrator rights.
            mirrorInternalLogToPublicDownloads(context, internalLog);
        }
    }

    public static File getExternalStartupLog(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            return dir != null ? new File(dir, STARTUP_LOG) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean exportStartupLogToDownloads(Context context) {
        File source = new File(context.getFilesDir(), STARTUP_LOG);
        if (!source.isFile()) {
            source = getExternalStartupLog(context);
        }
        if (source == null || !source.isFile()) {
            return false;
        }

        String text = readBestEffort(source);
        if (text == null) {
            return false;
        }

        // Always refresh the predictable public filename first, then also create a timestamped
        // snapshot so a user can preserve a specific test run.
        boolean publicCopy = writeOrReplacePublicStartupLog(context, text);
        String fileName = "AnExplorer-AAOS-startup-" + System.currentTimeMillis() + ".txt";
        boolean snapshot = writeTextToDownloads(context, fileName, text);
        return publicCopy || snapshot;
    }

    private static void mirrorInternalLogToPublicDownloads(Context context, File internalLog) {
        try {
            String text = readBestEffort(internalLog);
            if (text != null) {
                writeOrReplacePublicStartupLog(context, text);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean writeOrReplacePublicStartupLog(Context context, String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                File downloads = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists() && !downloads.mkdirs()) {
                    return false;
                }
                writeBestEffort(new File(downloads, PUBLIC_STARTUP_LOG), text);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        Cursor cursor = null;
        OutputStream output = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/";
            Uri itemUri = null;

            String[] projection = new String[]{MediaStore.Downloads._ID};
            String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                    + MediaStore.Downloads.RELATIVE_PATH + "=?";
            String[] args = new String[]{PUBLIC_STARTUP_LOG, relativePath};
            cursor = resolver.query(collection, projection, selection, args, null);
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                itemUri = ContentUris.withAppendedId(collection, id);
            }

            if (itemUri == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, PUBLIC_STARTUP_LOG);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
                itemUri = resolver.insert(collection, values);
            }

            if (itemUri == null) {
                return false;
            }

            output = resolver.openOutputStream(itemUri, "wt");
            if (output == null) {
                return false;
            }
            output.write(text.getBytes("UTF-8"));
            output.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeCrashReport(Context context, Thread thread, Throwable throwable) {
        StringWriter stack = new StringWriter();
        PrintWriter printer = new PrintWriter(stack);
        throwable.printStackTrace(printer);
        printer.flush();

        StringBuilder report = new StringBuilder();
        report.append("AnExplorer AAOS safe-mode crash report\n");
        report.append("Package: ").append(context.getPackageName()).append('\n');
        report.append("Version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        report.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        report.append("Device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        report.append("Thread: ")
                .append(thread != null ? thread.getName() : "unknown")
                .append("\n\n");
        report.append(stack.toString());

        log(context, "FATAL " + throwable.getClass().getName() + ": " + throwable.getMessage());

        String fileName = "AnExplorer-AAOS-crash-" + System.currentTimeMillis() + ".txt";
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                writeBestEffort(new File(dir, fileName), report.toString());
            }
        } catch (Throwable ignored) {
        }

        writeTextToDownloads(context, fileName, report.toString());
    }

    private static boolean writeTextToDownloads(Context context, String fileName, String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                File downloads = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists() && !downloads.mkdirs()) {
                    return false;
                }
                writeBestEffort(new File(downloads, fileName), text);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        OutputStream output = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return false;
            }
            output = resolver.openOutputStream(uri, "w");
            if (output == null) {
                return false;
            }
            output.write(text.getBytes("UTF-8"));
            output.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void appendBestEffort(File file, String text) {
        FileOutputStream output = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            output = new FileOutputStream(file, true);
            output.write(text.getBytes("UTF-8"));
            output.flush();
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

    private static void writeBestEffort(File file, String text) {
        FileOutputStream output = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            output = new FileOutputStream(file, false);
            output.write(text.getBytes("UTF-8"));
            output.flush();
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

    private static String readBestEffort(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
            return text.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
