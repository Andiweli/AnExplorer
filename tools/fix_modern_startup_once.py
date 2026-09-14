from pathlib import Path

path = Path("app/src/main/java/dev/dworks/apps/anexplorer/DocumentsActivity.java")
text = path.read_text(encoding="utf-8")

replacements = [
    (
'''    public RootInfo getCurrentRoot() {
        if (mState.stack.root != null) {
            return mState.stack.root;
        } else {
            return mState.action == ACTION_BROWSE ? mRoots.getDefaultRoot() : mRoots.getStorageRoot();
        }
    }
''',
'''    public RootInfo getCurrentRoot() {
        // Modern AndroidX may invalidate the options menu from inside
        // FragmentActivity/ComponentActivity.super.onCreate(), before this activity has
        // finished restoring its own state and root cache. The legacy implementation
        // assumed these fields were always ready and crashed during cold start.
        if (mState == null || mState.stack == null || mRoots == null) {
            return null;
        }
        if (mState.stack.root != null) {
            return mState.stack.root;
        }
        return mState.action == ACTION_BROWSE ? mRoots.getDefaultRoot() : mRoots.getStorageRoot();
    }
'''),
    (
'''    public boolean isCreateSupported() {
        final DocumentInfo cwd = getCurrentDirectory();
        if (mState.action == ACTION_OPEN_TREE) {
            return cwd != null && cwd.isCreateSupported();
        } else if (mState.action == ACTION_CREATE || mState.action == ACTION_GET_CONTENT) {
            return false;
        } else {
            return cwd != null && cwd.isCreateSupported();
        }
    }
''',
'''    public boolean isCreateSupported() {
        if (mState == null || mState.stack == null) {
            return false;
        }
        final DocumentInfo cwd = getCurrentDirectory();
        if (mState.action == ACTION_OPEN_TREE) {
            return cwd != null && cwd.isCreateSupported();
        } else if (mState.action == ACTION_CREATE || mState.action == ACTION_GET_CONTENT) {
            return false;
        } else {
            return cwd != null && cwd.isCreateSupported();
        }
    }
'''),
    (
'''    public void invalidateMenu(){
        supportInvalidateOptionsMenu();
        mActionMenu.setVisibility(!isSpecialDevice() && showActionMenu() ? View.VISIBLE : View.GONE);
    }
''',
'''    public void invalidateMenu(){
        // ComponentActivity can call this while super.onCreate() is still running.
        // At that point the legacy activity has not inflated its toolbar/FAB or restored
        // mState yet, so menu work must be deferred until initialization is complete.
        if (mState == null || mRoots == null || mActionMenu == null) {
            return;
        }
        supportInvalidateOptionsMenu();
        mActionMenu.setVisibility(!isSpecialDevice() && showActionMenu() ? View.VISIBLE : View.GONE);
    }
'''),
    (
'''    private boolean showActionMenu() {
        final RootInfo root = getCurrentRoot();
        return !RootInfo.isOtherRoot(root) &&
                isCreateSupported() &&
                (null != root && (!root.isRootedStorage() || Utils.isRooted()))
                && mState.currentSearch == null;
    }
''',
'''    private boolean showActionMenu() {
        if (mState == null || mActionMenu == null) {
            return false;
        }
        final RootInfo root = getCurrentRoot();
        if (root == null) {
            return false;
        }
        return !RootInfo.isOtherRoot(root) &&
                isCreateSupported() &&
                (!root.isRootedStorage() || Utils.isRooted()) &&
                mState.currentSearch == null;
    }
'''),
    (
'''                    final RootInfo root = getCurrentRoot();
                    if(root.isHome()){
                        HomeFragment homeFragment = HomeFragment.get(getFragmentManager());
''',
'''                    final RootInfo root = getCurrentRoot();
                    if(root != null && root.isHome()){
                        HomeFragment homeFragment = HomeFragment.get(getFragmentManager());
'''),
    (
'''    private void checkLatestVersion() {
        UpdateFrom updateFrom = Utils.isGoogleBuild() ? UpdateFrom.GOOGLE_PLAY : UpdateFrom.AMAZON;
''',
'''    private void checkLatestVersion() {
        // The legacy updater infers the store from old distribution flavor names.
        // Our AAOS flavor is neither an Amazon nor a Google-Play distribution flavor,
        // and an update dialog during vehicle startup is undesirable. Keep it disabled
        // until update handling is explicitly reimplemented for the automotive build.
        if ("automotive".equals(BuildConfig.FLAVOR)) {
            return;
        }
        UpdateFrom updateFrom = Utils.isGoogleBuild() ? UpdateFrom.GOOGLE_PLAY : UpdateFrom.AMAZON;
'''),
]

for old, new in replacements:
    if new in text:
        continue
    if old not in text:
        raise RuntimeError("Expected source fragment not found:\n" + old[:220])
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Modern AndroidX startup hardening applied.")
