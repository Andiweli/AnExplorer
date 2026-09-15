package dev.dworks.apps.anexplorer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * AAOS-only storage bootstrap.
 *
 * Android 11+ scoped storage is not disabled by READ_EXTERNAL_STORAGE. A real file manager needs
 * MANAGE_EXTERNAL_STORAGE in order to enumerate normal shared-storage files through direct file
 * paths. This activity therefore verifies Environment.isExternalStorageManager() before entering
 * AutomotiveSafeModeActivity and sends the user to the system's per-app "All files access" page.
 *
 * The normal runtime permission path is retained only for Android 6-10 devices.
 */
public class AutomotivePermissionBootstrapActivity extends Activity {

    private static final int REQUEST_SHARED_STORAGE = 4201;

    private boolean launchedBrowser;
    private boolean settingsLaunchAttempted;
    private TextView statusView;
    private Button accessButton;
    private Button continueButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AutomotiveSafeApplication.log(this,
                "BOOTSTRAP onCreate api=" + Build.VERSION.SDK_INT
                        + " target=" + getApplicationInfo().targetSdkVersion
                        + " allFiles=" + hasAllFilesAccess());
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        setContentView(createContentView());
        updateUiAndContinue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null && !launchedBrowser) {
            AutomotiveSafeApplication.log(this,
                    "BOOTSTRAP onResume allFiles=" + hasAllFilesAccess()
                            + " readPermission=" + hasLegacyReadPermission()
                            + " settingsAttempted=" + settingsLaunchAttempted);
            updateUiAndContinue();
        }
    }

    private View createContentView() {
        final int background = Color.rgb(18, 18, 18);
        final int panel = Color.rgb(31, 31, 31);
        final int accent = Color.rgb(255, 61, 23);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(48), dp(30), dp(48), dp(30));
        root.setBackgroundColor(background);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(30), dp(24), dp(30), dp(24));
        card.setBackgroundColor(panel);

        TextView title = new TextView(this);
        title.setText("AnExplorer · Speicherzugriff");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(210, 210, 210));
        statusView.setTextSize(17f);
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusView.setPadding(0, dp(18), 0, dp(24));
        card.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        accessButton = new Button(this);
        accessButton.setText("ALLE DATEIEN ERLAUBEN");
        accessButton.setTextColor(Color.WHITE);
        accessButton.setTextSize(16f);
        accessButton.setMinHeight(dp(58));
        accessButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(accent));
        accessButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openStorageAccessSettings();
            }
        });
        card.addView(accessButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        continueButton = new Button(this);
        continueButton.setText("TROTZDEM ÖFFNEN");
        continueButton.setTextColor(Color.WHITE);
        continueButton.setTextSize(14f);
        continueButton.setMinHeight(dp(52));
        LinearLayout.LayoutParams continueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        continueParams.topMargin = dp(12);
        continueButton.setLayoutParams(continueParams);
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AutomotiveSafeApplication.log(AutomotivePermissionBootstrapActivity.this,
                        "BOOTSTRAP user continues without broad storage access");
                launchBrowser();
            }
        });
        card.addView(continueButton);

        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private void updateUiAndContinue() {
        if (launchedBrowser) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasAllFilesAccess()) {
                AutomotiveSafeApplication.log(this,
                        "BOOTSTRAP MANAGE_EXTERNAL_STORAGE granted");
                launchBrowser();
                return;
            }

            statusView.setText(
                    "Für einen vollständigen Dateimanager muss Android den Zugriff „Alle Dateien "
                            + "verwalten“ für AnExplorer freigeben.\n\n"
                            + "Aktueller Status: NICHT ERLAUBT");
            accessButton.setVisibility(View.VISIBLE);
            continueButton.setVisibility(View.VISIBLE);

            if (!settingsLaunchAttempted) {
                settingsLaunchAttempted = true;
                statusView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && !hasAllFilesAccess()) {
                            openStorageAccessSettings();
                        }
                    }
                }, 500L);
            }
            return;
        }

        if (needsLegacyStoragePermission()) {
            statusView.setText("Bitte Dateien-/Medienzugriff erlauben.");
            accessButton.setVisibility(View.GONE);
            continueButton.setVisibility(View.GONE);
            AutomotiveSafeApplication.log(this,
                    "BOOTSTRAP requesting READ_EXTERNAL_STORAGE");
            requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_SHARED_STORAGE);
            return;
        }

        launchBrowser();
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable error) {
            AutomotiveSafeApplication.log(this,
                    "BOOTSTRAP isExternalStorageManager failed: "
                            + error.getClass().getName() + ": " + error.getMessage());
            return false;
        }
    }

    private void openStorageAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            updateUiAndContinue();
            return;
        }

        AutomotiveSafeApplication.log(this,
                "BOOTSTRAP opening MANAGE_APP_ALL_FILES_ACCESS settings");

        try {
            Intent perApp = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            perApp.setData(Uri.parse("package:" + getPackageName()));
            if (perApp.resolveActivity(getPackageManager()) != null) {
                AutomotiveSafeApplication.log(this,
                        "BOOTSTRAP per-app all-files settings resolved");
                startActivity(perApp);
                return;
            }
            AutomotiveSafeApplication.log(this,
                    "BOOTSTRAP per-app all-files settings NOT resolved");
        } catch (Throwable error) {
            AutomotiveSafeApplication.log(this,
                    "BOOTSTRAP per-app all-files settings failed: "
                            + error.getClass().getName() + ": " + error.getMessage());
        }

        try {
            Intent global = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            if (global.resolveActivity(getPackageManager()) != null) {
                AutomotiveSafeApplication.log(this,
                        "BOOTSTRAP global all-files settings resolved");
                startActivity(global);
                return;
            }
            AutomotiveSafeApplication.log(this,
                    "BOOTSTRAP global all-files settings NOT resolved");
        } catch (Throwable error) {
            AutomotiveSafeApplication.log(this,
                    "BOOTSTRAP global all-files settings failed: "
                            + error.getClass().getName() + ": " + error.getMessage());
        }

        Toast.makeText(this,
                "Das Renault-AAOS stellt die Android-Seite für „Alle Dateien verwalten“ nicht bereit. "
                        + "Der Diagnose-Log liegt in Download/AnExplorer-AAOS-startup.txt.",
                Toast.LENGTH_LONG).show();
        statusView.setText(
                "Renault-AAOS stellt die Systemseite „Alle Dateien verwalten“ nicht bereit.\n\n"
                        + "Der Diagnose-Log wurde nach Download geschrieben.");
    }

    private boolean needsLegacyStoragePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !hasLegacyReadPermission();
    }

    private boolean hasLegacyReadPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
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
        AutomotiveSafeApplication.log(this,
                "BOOTSTRAP launching browser allFiles=" + hasAllFilesAccess());
        Intent intent = new Intent(this, AutomotiveSafeModeActivity.class);
        startActivity(intent);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
