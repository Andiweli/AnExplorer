package dev.dworks.apps.anexplorer;

/**
 * AAOS entry point for AnExplorer.
 *
 * Modern AndroidX ComponentActivity owns an invalidateMenu() lifecycle hook. The legacy
 * AnExplorer DocumentsActivity also defines invalidateMenu(), so AndroidX can dispatch into
 * the legacy method while super.onCreate() is still running, before DocumentsActivity has
 * initialized its State. That causes an immediate startup NPE in getCurrentRoot().
 *
 * Keep the legacy implementation for normal app operation, but ignore only the premature
 * AndroidX callback until DocumentsActivity has created its State.
 */
public class AutomotiveDocumentsActivity extends DocumentsActivity {

    @Override
    public void invalidateMenu() {
        if (super.getDisplayState() == null) {
            return;
        }
        super.invalidateMenu();
    }
}
