package dev.dworks.apps.anexplorer;

import android.os.Build;
import android.os.Handler;
import android.view.View;

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

        RootsCache.updateRoots(this, ExternalStorageProvider.AUTHORITY);
        final RootsCache roots = DocumentsApplication.getRootsCache(this);
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
