package com.github.javiersantos.appupdater;

import android.content.Context;

import com.github.javiersantos.appupdater.enums.Display;
import com.github.javiersantos.appupdater.enums.UpdateFrom;

/**
 * Automotive compatibility shim.
 *
 * The original AnExplorer starts AppUpdater from DocumentsActivity.onCreate(). That library
 * assumes a phone/tablet app-store environment and is not appropriate for AAOS. Keep the
 * source API intact but intentionally perform no updater work for the automotive flavor.
 */
public final class AppUpdater {

    public AppUpdater(Context context) {
        // Intentionally no-op on AAOS.
    }

    public AppUpdater showEvery(int days) {
        return this;
    }

    public AppUpdater setUpdateFrom(UpdateFrom updateFrom) {
        return this;
    }

    public AppUpdater setDisplay(Display display) {
        return this;
    }

    public void start() {
        // Intentionally no-op on AAOS.
    }
}
