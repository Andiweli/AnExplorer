package dev.dworks.apps.anexplorer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;

/**
 * Tiny AAOS-only launcher that requests the normal shared-storage permission on Android 12
 * before entering AutomotiveSafeModeActivity.
 *
 * Renault's AAOS 12 is API 32. READ_EXTERNAL_STORAGE is still a runtime permission there even
 * when the app targets API 35. This permission does not disable scoped storage and does not
 * replace MANAGE_EXTERNAL_STORAGE, but it exposes shared media and gives us a clean, user-level
 * permission test without relying on an OEM-specific "All files access" settings screen.
 */
public class AutomotivePermissionBootstrapActivity extends Activity {

    private static final int REQUEST_SHARED_STORAGE = 4201;
    private boolean launchedBrowser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AutomotiveSafeApplication.log(this,
                "BOOTSTRAP onCreate api=" + Build.VERSION.SDK_INT
                        + " target=" + getApplicationInfo().targetSdkVersion);
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        continueAfterPermissionCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!launchedBrowser) {
            continueAfterPermissionCheck();
        }
    }

    private void continueAfterPermissionCheck() {
        if (launchedBrowser) {
            return;
        }

        if (needsSharedStoragePermission()) {
            AutomotiveSafeApplication.log(this,
                    "BOOTSTRAP requesting READ_EXTERNAL_STORAGE");
            requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_SHARED_STORAGE);
            return;
        }

        AutomotiveSafeApplication.log(this,
                "BOOTSTRAP permission state granted=" + hasSharedStoragePermission());
        launchBrowser();
    }

    private boolean needsSharedStoragePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
                && !hasSharedStoragePermission();
    }

    private boolean hasSharedStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
            return false;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_SHARED_STORAGE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            AutomotiveSafeApplication.log(this,
                    "BOOTSTRAP READ_EXTERNAL_STORAGE result granted=" + granted);
            launchBrowser();
        }
    }

    private void launchBrowser() {
        if (launchedBrowser) {
            return;
        }
        launchedBrowser = true;
        Intent intent = new Intent(this, AutomotiveSafeModeActivity.class);
        startActivity(intent);
        finish();
    }
}
