/*
 * Copyright (C) 2014 Hari Krishna Dulipudi
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

package dev.dworks.apps.anexplorer.misc;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import androidx.core.content.res.ResourcesCompat;
import android.text.TextUtils;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.libcore.util.Objects;

import static android.R.attr.label;
import static dev.dworks.apps.anexplorer.misc.DiskInfo.FLAG_SD;
import static dev.dworks.apps.anexplorer.misc.DiskInfo.FLAG_USB;

public final class StorageUtils {
    private static final String TAG = "StorageUtils";

    // partition types
    public static final int PARTITION_SYSTEM = 1;
    public static final int PARTITION_DATA = 2;
    public static final int PARTITION_CACHE = 3;
    public static final int PARTITION_RAM = 4;
    public static final int PARTITION_EXTERNAL = 5;
    public static final int PARTITION_EMMC = 6;
    public static final int PARTITION_ESTORAGE = 7;
    
	private StorageManager mStorageManager;
	private ActivityManager activityManager;
    private Context mContext;
    public StorageUtils(Context context){
        mContext = context;
        mStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    public List<VolumeInfo> getVolumes() {
        List<VolumeInfo> mounts = new ArrayList<VolumeInfo>();
        List<Object> vi = null;
        try {
            Method getVolumeList = StorageManager.class.getDeclaredMethod("getVolumes");
            vi = (List<Object>)getVolumeList.invoke(mStorageManager);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if(null == vi){
            return mounts;
        }
        for (Object object : vi) {
            String id = getString(object, "id");
            int type = getInteger(object, "type");
            DiskInfo disk = getDiskInfo(object);
            String partGuid = getString(object, "id");

            int mountFlags = getInteger(object, "mountFlags");
            int mountUserId = getInteger(object, "mountUserId"); // -1
            int state = getInteger(object, "state");
            String fsType = getString(object, "fsType");
            String fsUuid = getString(object, "fsUuid");
            String fsLabel = getString(object, "fsLabel");
            String path = getString(object, "path");
            String internalPath = getString(object, "internalPath");

            VolumeInfo volumeInfo = new VolumeInfo(id, type, disk, partGuid);
            volumeInfo.mountFlags = mountFlags;
            volumeInfo.mountUserId = mountUserId;
            volumeInfo.state = state;
            volumeInfo.fsType = fsType;
            volumeInfo.fsUuid = fsUuid;
            volumeInfo.fsLabel = fsLabel;
            volumeInfo.path = path;
            volumeInfo.internalPath = internalPath;

            mounts.add(volumeInfo);
        }
        return mounts;
    }

    /**
     * Enumerates app-visible external storage volumes using only public Android SDK APIs.
     *
     * The legacy implementation reflected into StorageManager#getVolumeList() and private
     * StorageVolume fields. That is unreliable once hidden-API enforcement is active. The
     * app-specific external directories give us stable mount roots on API 23+, while the
     * public platform StorageVolume objects provide labels, UUIDs and state on API 24+.
     */
    public List<StorageVolume> getStorageMounts() {
        final List<StorageVolume> mounts = new ArrayList<>();
        final File[] appSpecificDirs = mContext.getExternalFilesDirs(null);
        if (appSpecificDirs == null) {
            return mounts;
        }

        List<android.os.storage.StorageVolume> platformVolumes = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mStorageManager != null) {
            platformVolumes = mStorageManager.getStorageVolumes();
        }

        for (int index = 0; index < appSpecificDirs.length; index++) {
            final File appSpecificDir = appSpecificDirs[index];
            final File root = findExternalStorageRoot(appSpecificDir);
            if (root == null) {
                continue;
            }

            final boolean primary = index == 0 || samePath(root, Environment.getExternalStorageDirectory());
            final android.os.storage.StorageVolume platformVolume =
                    findPlatformStorageVolume(platformVolumes, root, primary);

            final boolean emulated;
            final boolean removable;
            final String description;
            final String uuid;
            final String state;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && platformVolume != null) {
                emulated = platformVolume.isEmulated();
                removable = platformVolume.isRemovable();
                description = platformVolume.getDescription(mContext);
                uuid = platformVolume.getUuid();
                state = platformVolume.getState();
            } else {
                boolean localEmulated = primary;
                boolean localRemovable = !primary;
                try {
                    localEmulated = Environment.isExternalStorageEmulated(root);
                    localRemovable = Environment.isExternalStorageRemovable(root);
                } catch (IllegalArgumentException ignored) {
                    // Some vendor builds do not expose every secondary path to Environment.
                }
                emulated = localEmulated;
                removable = localRemovable;
                description = primary
                        ? mContext.getString(R.string.root_internal_storage)
                        : root.getName();
                uuid = primary ? null : root.getName();
                String localState;
                try {
                    localState = Environment.getExternalStorageState(root);
                } catch (IllegalArgumentException ignored) {
                    localState = Environment.MEDIA_UNKNOWN;
                }
                state = localState;
            }

            final int storageId = primary
                    ? StorageVolume.STORAGE_ID_PRIMARY
                    : VolumeInfo.buildStableMtpStorageId(uuid);
            final StorageVolume volume = new StorageVolume(
                    storageId,
                    root,
                    description,
                    primary,
                    removable,
                    emulated,
                    0,
                    false,
                    0);
            volume.mId = primary ? "primary" : (uuid != null ? uuid : root.getName());
            volume.mFsUuid = uuid;
            volume.mUuid = uuid;
            volume.mUserLabel = description;
            volume.mState = state;
            mounts.add(volume);
        }
        return mounts;
    }

    private File findExternalStorageRoot(File appSpecificDir) {
        File current = appSpecificDir;
        while (current != null) {
            if ("Android".equals(current.getName())) {
                return current.getParentFile();
            }
            current = current.getParentFile();
        }
        return null;
    }

    private boolean samePath(File first, File second) {
        if (first == null || second == null) {
            return false;
        }
        try {
            return first.getCanonicalFile().equals(second.getCanonicalFile());
        } catch (Exception ignored) {
            return first.getAbsolutePath().equals(second.getAbsolutePath());
        }
    }

    private android.os.storage.StorageVolume findPlatformStorageVolume(
            List<android.os.storage.StorageVolume> platformVolumes,
            File root,
            boolean primary) {
        if (platformVolumes == null) {
            return null;
        }
        for (android.os.storage.StorageVolume volume : platformVolumes) {
            if (primary && volume.isPrimary()) {
                return volume;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                final File directory = volume.getDirectory();
                if (directory != null && samePath(root, directory)) {
                    return volume;
                }
            }
            if (!primary) {
                final String uuid = volume.getUuid();
                if (uuid != null && uuid.equalsIgnoreCase(root.getName())) {
                    return volume;
                }
            }
        }
        return null;
    }

    private DiskInfo getDiskInfo(Object object) {
        String path = "";
        DiskInfo diskInfo = null;
        try {
            Field mPath = object.getClass().getDeclaredField("disk");
            mPath.setAccessible(true);
            Object diskObj = mPath.get(object);

            String id = getString(diskObj, "id");
            int flags = getInteger(diskObj, "flags");
            long size = getLong(diskObj, "size");
            String label = getString(diskObj, "label");
            int volumeCount = getInteger(diskObj, "volumeCount");
            String sysPath = getString(diskObj, "sysPath");

            diskInfo = new DiskInfo(id, flags);
            diskInfo.size = size;
            diskInfo.label = label;
            diskInfo.volumeCount = volumeCount;
            diskInfo.sysPath = sysPath;

        } catch (Exception e) {
            e.printStackTrace();
        }
        return diskInfo;
    }

    private File getFile(Object object) {
        String path = "";
        File file = null;
        try {
            Field mPath = object.getClass().getDeclaredField("mPath");
            mPath.setAccessible(true);
            Object pathObj = mPath.get(object);
            if(Utils.hasJellyBeanMR1()){
                file = (File)pathObj;
            }
            else{
                path = (String)pathObj;
                file = new File(path);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return file;
    }

    private String getDescription(Object object) {
        String description = "";
        if(Utils.hasMarshmallow()){
            description = getDescription(object, false);
        }
        else if(Utils.hasJellyBean()){
            try {
                description = getDescription(object, true);
            }
            catch (Resources.NotFoundException e){
                description = getDescription(object, false);
            }
        }
        else{
            description = getDescription(object, false);
        }
        return description;
    }

    private String getDescription(Object object, boolean hasId) {
        String description = "";
        if (hasId) {
            int mDescriptionInt = getInteger(object, "mDescriptionId");
            description = mContext.getResources().getString(mDescriptionInt);
        } else {
            description = getString(object, "mDescription");
        }
        return description;
    }

    private String getString(Object object, String id) {
        String value = "";
        try {
            Field field = object.getClass().getDeclaredField(id);
            field.setAccessible(true);
            value = (String) field.get(object);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return value;
    }

    private int getInteger(Object object, String id) {
        int value = 0;
        try {
            Field field = null;
            field = object.getClass().getDeclaredField(id);
            field.setAccessible(true);
            value = field.getInt(object);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return value;
    }

    private boolean getBoolean(Object object, String id) {
        boolean value = false;
        try {
            Field field = object.getClass().getDeclaredField(id);
            field.setAccessible(true);
            value = field.getBoolean(object);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return value;
    }

    private long getLong(Object object, String id) {
        long value = 0l;
        try {
            Field field = object.getClass().getDeclaredField(id);
            field.setAccessible(true);
            value = field.getLong(object);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return value;
    }
    
	public static long getExtStorageSize(String path, boolean isTotal){
		return getPartionSize(path, isTotal);		
	}

	public long getPartionSize(int type, boolean isTotal){
		Long size = 0L;
		
		switch (type) {
		case PARTITION_SYSTEM:
			size = getPartionSize(Environment.getRootDirectory().getPath(), isTotal);
			break;
		case PARTITION_DATA:
			size = getPartionSize(Environment.getDataDirectory().getPath(), isTotal);
			break;
		case PARTITION_CACHE:
			size = getPartionSize(Environment.getDownloadCacheDirectory().getPath(), isTotal);
			break;
		case PARTITION_EXTERNAL:
			size = getPartionSize(Environment.getExternalStorageDirectory().getPath(), isTotal);
			break;
		/*case PARTITION_EMMC:
			size = getPartionSize(DIR_EMMC, isTotal);
			break;*/
		case PARTITION_RAM:
			size = getSizeTotalRAM(isTotal);
			break;			
		}
		return size;
	}
	
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private long getSizeTotalRAM(boolean isTotal) {
		long sizeInBytes = 1000;
		MemoryInfo mi = new MemoryInfo();
		activityManager.getMemoryInfo(mi);
		if(isTotal) {
			try { 
				if(Utils.hasJellyBean()){
					long totalMegs = mi.totalMem;
					sizeInBytes = totalMegs;
				}
				else{
					RandomAccessFile reader = new RandomAccessFile("/proc/meminfo", "r");
					String load = reader.readLine();
					String[] totrm = load.split(" kB");
					String[] trm = totrm[0].split(" ");
					sizeInBytes=Long.parseLong(trm[trm.length-1]);
					sizeInBytes=sizeInBytes*1024;
					reader.close();	
				}
			} 
			catch (Exception e) { }
		}
		else{
			long availableMegs = mi.availMem;
			sizeInBytes = availableMegs;
		}		
		return sizeInBytes;
	}
	
	/**
	 * @param isTotal  The parameter for calculating total size
	 * @return return Total Size when isTotal is {@value true} else return Free Size of Internal memory(data folder)
	 */
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    @SuppressWarnings("deprecation")
	private static long getPartionSize(String path, boolean isTotal){
		StatFs stat = null;
		try {
			stat = new StatFs(path);	
		} catch (Exception e) { }
		if(null != stat){
            if(Utils.hasJellyBeanMR2()){
                final long blockSize = stat.getBlockSizeLong();
                final long availableBlocks = (isTotal ? stat.getBlockCountLong() : stat.getAvailableBlocksLong());
                return availableBlocks * blockSize;
            }
            else{
                final long blockSize = stat.getBlockSize();
                final long availableBlocks = (isTotal ? (long)stat.getBlockCount() : (long)stat.getAvailableBlocks());
                return availableBlocks * blockSize;
            }
		}
		else return 0L;
	}

    public static String getBestVolumeDescription(Context context, VolumeInfo vol) {
        if (vol == null) return null;
        // Nickname always takes precedence when defined
/*        if (!TextUtils.isEmpty(vol.fsUuid)) {
            final VolumeRecord rec = findRecordByUuid(vol.fsUuid);
            if (rec != null && !TextUtils.isEmpty(rec.nickname)) {
                return rec.nickname;
            }
        }*/
        if (!TextUtils.isEmpty(vol.getDescription())) {
            return vol.getDescription();
        }
        if (vol.disk != null) {
            final Resources res = context.getResources();
            int flags = vol.disk.flags;
            String label = vol.disk.label;
            if ((flags & FLAG_SD) != 0) {
                if (vol.disk.isInteresting(label)) {
                    return res.getString(R.string.storage_sd_card_label, label);
                } else {
                    return res.getString(R.string.storage_sd_card);
                }
            } else if ((flags & FLAG_USB) != 0) {
                if (vol.disk.isInteresting(label)) {
                    return res.getString(R.string.storage_usb_drive_label, label);
                } else {
                    return res.getString(R.string.storage_usb_drive);
                }
            } else {
                return null;
            }
            //return vol.disk.getDescription();
        }
        return null;
    }

    public VolumeInfo findPrivateForEmulated(VolumeInfo emulatedVol) {
        if (emulatedVol != null) {
            return findVolumeById(emulatedVol.getId().replace("emulated", "private"));
        } else {
            return null;
        }
    }

    public VolumeInfo findVolumeById(String id) {
        Preconditions.checkNotNull(id);
        // TODO; go directly to service to make this faster
        for (VolumeInfo vol : getVolumes()) {
            if (Objects.equals(vol.id, id)) {
                return vol;
            }
        }
        return null;
    }
}