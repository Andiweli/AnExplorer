package dev.dworks.apps.anexplorer.misc;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import dev.dworks.apps.anexplorer.BuildConfig;
import dev.dworks.apps.anexplorer.provider.NetworkStorageProvider;
import dev.dworks.apps.anexplorer.service.ConnectionsService;

/** Network helpers shared by the FTP and network-storage features. */
public class ConnectionUtils {

    public static final String TAG = ConnectionUtils.class.getSimpleName();

    public static final String ACTION_FTPSERVER_STARTED = BuildConfig.APPLICATION_ID + ".action.FTPSERVER_STARTED";
    public static final String ACTION_FTPSERVER_STOPPED = BuildConfig.APPLICATION_ID + ".action.FTPSERVER_STOPPED";
    public static final String ACTION_FTPSERVER_FAILEDTOSTART = BuildConfig.APPLICATION_ID + ".action.FTPSERVER_FAILEDTOSTART";

    public static final String ACTION_START_FTPSERVER = BuildConfig.APPLICATION_ID + ".action.START_FTPSERVER";
    public static final String ACTION_STOP_FTPSERVER = BuildConfig.APPLICATION_ID + ".action.STOP_FTPSERVER";

    public static int FTP_SERVER_PORT = 2211;

    /**
     * Returns true for a currently active Wi-Fi or Ethernet network. Tethering interfaces are
     * checked as a fallback because an access-point interface is not necessarily Android's
     * active network.
     */
    public static boolean isConnectedToLocalNetwork(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
                if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    return true;
                }
            } else {
                //noinspection deprecation
                NetworkInfo ni = cm.getActiveNetworkInfo();
                //noinspection deprecation
                if (ni != null && ni.isConnected()
                        && (ni.getType() == ConnectivityManager.TYPE_WIFI
                        || ni.getType() == ConnectivityManager.TYPE_ETHERNET)) {
                    return true;
                }
            }
        }

        // Legacy hotspot APIs are hidden, but still useful on older vendor builds.
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            try {
                Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
                method.setAccessible(true);
                if (Boolean.TRUE.equals(method.invoke(wm))) {
                    return true;
                }
            } catch (Exception ignored) {
                // Vendor may not expose the legacy hotspot API; interface detection below remains.
            }
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface netInterface : Collections.list(interfaces)) {
                    if (!netInterface.isUp() || netInterface.isLoopback()) {
                        continue;
                    }
                    String name = netInterface.getName() == null ? "" : netInterface.getName().toLowerCase();
                    String display = netInterface.getDisplayName() == null
                            ? "" : netInterface.getDisplayName().toLowerCase();
                    if (name.startsWith("rndis") || display.startsWith("rndis")
                            || name.startsWith("wlan") || name.startsWith("ap")
                            || name.startsWith("eth")) {
                        return true;
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Unable to enumerate network interfaces", e);
        }
        return false;
    }

    public static boolean isConnectedToWifi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        //noinspection deprecation
        NetworkInfo ni = cm.getActiveNetworkInfo();
        //noinspection deprecation
        return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
    }

    /** Finds a usable IPv4 address without relying on deprecated WifiInfo APIs. */
    public static InetAddress getLocalInetAddress(Context context) {
        if (!isConnectedToLocalNetwork(context)) {
            Log.e(TAG, "getLocalInetAddress called and no local connection is available");
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
                if (active != null && caps != null
                        && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    LinkProperties properties = cm.getLinkProperties(active);
                    InetAddress address = firstUsableIpv4(properties);
                    if (address != null) {
                        return address;
                    }
                }
            }
        }

        try {
            Enumeration<NetworkInterface> netinterfaces = NetworkInterface.getNetworkInterfaces();
            if (netinterfaces != null) {
                for (NetworkInterface netinterface : Collections.list(netinterfaces)) {
                    if (!netinterface.isUp() || netinterface.isLoopback()) {
                        continue;
                    }
                    Enumeration<InetAddress> addresses = netinterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (address instanceof Inet4Address
                                && !address.isLoopbackAddress()
                                && !address.isLinkLocalAddress()) {
                            return address;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to determine local IP address", e);
        }
        return null;
    }

    private static InetAddress firstUsableIpv4(LinkProperties properties) {
        if (properties == null) {
            return null;
        }
        for (LinkAddress linkAddress : properties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if (address instanceof Inet4Address
                    && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress()) {
                return address;
            }
        }
        return null;
    }

    public static InetAddress intToInet(int value) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = byteOfInt(value, i);
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public static byte byteOfInt(int value, int which) {
        int shift = which * 8;
        return (byte) (value >> shift);
    }

    public static boolean isPortAvailable(int port) {
        ServerSocket ss = null;
        DatagramSocket ds = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            ds = new DatagramSocket(port);
            ds.setReuseAddress(true);
            return true;
        } catch (IOException ignored) {
        } finally {
            if (ds != null) {
                ds.close();
            }
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    public static String getFTPAddress(Context context) {
        InetAddress inetAddress = getLocalInetAddress(context);
        if (inetAddress != null) {
            return "ftp://" + inetAddress.getHostAddress() + ":" + FTP_SERVER_PORT;
        }
        return "";
    }

    public static int getAvailablePortForFTP() {
        for (int i = FTP_SERVER_PORT; i < 65000; i++) {
            if (isPortAvailable(i)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Android restricts process/service visibility on modern releases. This API is retained only
     * to query our own process, which Android still exposes to the caller.
     */
    @SuppressWarnings("deprecation")
    public static boolean isServerRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<ActivityManager.RunningServiceInfo> runningServices = manager.getRunningServices(Integer.MAX_VALUE);
        String ftpServiceClassName = ConnectionsService.class.getName();
        for (ActivityManager.RunningServiceInfo service : runningServices) {
            if (service.service != null && ftpServiceClassName.equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isServerAuthority(Intent intent) {
        if (intent != null && intent.getData() != null) {
            String authority = intent.getData().getAuthority();
            return NetworkStorageProvider.AUTHORITY.equals(authority);
        }
        return false;
    }
}
