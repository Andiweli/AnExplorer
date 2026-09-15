/*
 * Copyright (C) 2014 Hari Krishna Dulipudi
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.dworks.apps.anexplorer;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.net.Uri;
import android.os.RemoteException;
import android.text.TextUtils;
import android.text.format.DateUtils;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.collection.ArrayMap;
import androidx.core.content.ContextCompat;

import com.cloudrail.si.CloudRail;

import dev.dworks.apps.anexplorer.misc.AnalyticsManager;
import dev.dworks.apps.anexplorer.misc.ContentProviderClientCompat;
import dev.dworks.apps.anexplorer.misc.CrashReportingManager;
import dev.dworks.apps.anexplorer.misc.RootsCache;
import dev.dworks.apps.anexplorer.misc.SAFManager;
import dev.dworks.apps.anexplorer.misc.ThumbnailCache;
import dev.dworks.apps.anexplorer.misc.Utils;
import dev.dworks.apps.anexplorer.setting.SettingsActivity;

public class DocumentsApplication extends AppFlavour {
    private static final long PROVIDER_ANR_TIMEOUT = 20 * DateUtils.SECOND_IN_MILLIS;
    private static final String AAOS_MIGRATION_PREFS = "aaos_source_migration";
    private static final String AAOS_TRANSIENT_RESET_V1 = "transient_reset_v1";
    private static DocumentsApplication sInstance;

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    private RootsCache mRoots;
    private final ArrayMap<Integer, Long> mSizes = new ArrayMap<>();
    private SAFManager mSAFManager;
    private ThumbnailCache mThumbnailCache;
    private static boolean isTelevision;
    private static boolean isWatch;

    public static RootsCache getRootsCache(Context context) {
        return ((DocumentsApplication) context.getApplicationContext()).mRoots;
    }

    public static ArrayMap<Integer, Long> getFolderSizes() {
        return getInstance().mSizes;
    }

    public static SAFManager getSAFManager(Context context) {
        return ((DocumentsApplication) context.getApplicationContext()).mSAFManager;
    }

    public static ThumbnailCache getThumbnailCache(Context context) {
        final DocumentsApplication app = (DocumentsApplication) context.getApplicationContext();
        return app.mThumbnailCache;
    }

    public static ThumbnailCache getThumbnailsCache(Context context, Point size) {
        return getThumbnailCache(context);
    }

    public static ContentProviderClient acquireUnstableProviderOrThrow(
            ContentResolver resolver, String authority) throws RemoteException {
        final ContentProviderClient client =
                ContentProviderClientCompat.acquireUnstableContentProviderClient(resolver, authority);
        if (client == null) {
            throw new RemoteException("Failed to acquire provider for " + authority);
        }
        ContentProviderClientCompat.setDetectNotResponding(client, PROVIDER_ANR_TIMEOUT);
        return client;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (!BuildConfig.DEBUG) {
            AnalyticsManager.intialize(getApplicationContext());
        }

        sInstance = this;

        // The Play-distributed AAOS source build upgrades installations that previously ran
        // the newer closed/binary 6.0.8 code while this public source tree is substantially
        // older. Its recents.db contains parcelled DocumentStack/resume data and potentially a
        // newer SQLite user_version. Restoring that transient data with the old source can fail
        // immediately after the first frame. Reset only transient navigation/history state once;
        // settings, bookmarks and network/cloud connections remain untouched.
        migrateAutomotiveTransientState();

        final ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        final int memoryClassBytes = am.getMemoryClass() * 1024 * 1024;

        // The reconstructed source can be built without private CloudRail credentials.
        // Cloud functions that still use CloudRail are initialized only when a key is supplied.
        if (!TextUtils.isEmpty(BuildConfig.LICENSE_KEY)) {
            CloudRail.setAppKey(BuildConfig.LICENSE_KEY);
        }

        CrashReportingManager.enable(getApplicationContext(), true);

        mRoots = new RootsCache(this);
        mRoots.updateAsync();
        mSAFManager = new SAFManager(this);
        mThumbnailCache = new ThumbnailCache(memoryClassBytes / 4);

        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        packageFilter.addDataScheme("package");
        ContextCompat.registerReceiver(
                this,
                mCacheReceiver,
                packageFilter,
                ContextCompat.RECEIVER_EXPORTED);

        final IntentFilter localeFilter = new IntentFilter();
        localeFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        ContextCompat.registerReceiver(
                this,
                mCacheReceiver,
                localeFilter,
                ContextCompat.RECEIVER_EXPORTED);

        isTelevision = Utils.isTelevision(this);
        isWatch = Utils.isWatch(this);
        if (isTelevision
                && Integer.valueOf(SettingsActivity.getThemeStyle())
                != AppCompatDelegate.MODE_NIGHT_YES) {
            SettingsActivity.setThemeStyle(AppCompatDelegate.MODE_NIGHT_YES);
        }
    }

    private void migrateAutomotiveTransientState() {
        if (!BuildConfig.FLAVOR.toLowerCase().contains("automotive")) {
            return;
        }

        final SharedPreferences migration =
                getSharedPreferences(AAOS_MIGRATION_PREFS, Context.MODE_PRIVATE);
        if (migration.getBoolean(AAOS_TRANSIENT_RESET_V1, false)) {
            return;
        }

        try {
            // Database is recreated lazily by RecentsProvider with this source tree's schema.
            deleteDatabase("recents.db");
        } catch (RuntimeException ignored) {
            // Startup must never fail because cleanup of disposable history failed.
        }

        try {
            // Do not let an accumulated phone-oriented "rate this app" counter inject legacy
            // overlay UI into the first AAOS runs after migration.
            getSharedPreferences("app_rate_prefs", Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (RuntimeException ignored) {
        }

        // commit() is intentional: mark the migration before async provider/root work starts.
        migration.edit().putBoolean(AAOS_TRANSIENT_RESET_V1, true).commit();
    }

    public static synchronized DocumentsApplication getInstance() {
        return sInstance;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        mThumbnailCache.onTrimMemory(level);
    }

    private final BroadcastReceiver mCacheReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final Uri data = intent.getData();
            if (data != null) {
                final String authority = data.getAuthority();
                mRoots.updateAuthorityAsync(authority);
            } else {
                mRoots.updateAsync();
            }
        }
    };

    public static boolean isSpecialDevice() {
        return isTelevision() || isWatch();
    }

    public static boolean isTelevision() {
        return isTelevision;
    }

    public static boolean isWatch() {
        return isWatch;
    }
}