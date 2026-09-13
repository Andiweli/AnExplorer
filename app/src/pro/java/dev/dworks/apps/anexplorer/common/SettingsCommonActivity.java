package dev.dworks.apps.anexplorer.common;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Shared settings activity base for the reconstructed source build.
 *
 * Android Automotive systems can reserve space at the left/top edges for the vehicle UI.
 * The old preference activity did not consume those insets, so settings could render below
 * the car's system overlay. Apply the real system-bar/cutout insets only on AAOS devices.
 */
public abstract class SettingsCommonActivity extends AppCompatPreferenceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyAutomotiveSafeInsets();
    }

    private void applyAutomotiveSafeInsets() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return;
        }

        final View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        final int initialLeft = content.getPaddingLeft();
        final int initialTop = content.getPaddingTop();
        final int initialRight = content.getPaddingRight();
        final int initialBottom = content.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(
                    initialLeft + safe.left,
                    initialTop + safe.top,
                    initialRight + safe.right,
                    initialBottom + safe.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
