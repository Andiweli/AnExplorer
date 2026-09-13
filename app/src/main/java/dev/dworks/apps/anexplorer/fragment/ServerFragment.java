package dev.dworks.apps.anexplorer.fragment;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import dev.dworks.apps.anexplorer.DocumentsApplication;
import dev.dworks.apps.anexplorer.R;
import dev.dworks.apps.anexplorer.misc.ConnectionUtils;
import dev.dworks.apps.anexplorer.misc.IconUtils;
import dev.dworks.apps.anexplorer.model.RootInfo;
import dev.dworks.apps.anexplorer.network.NetworkConnection;
import dev.dworks.apps.anexplorer.service.ConnectionsService;

import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_FTPSERVER_FAILEDTOSTART;
import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_FTPSERVER_STARTED;
import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_FTPSERVER_STOPPED;
import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_START_FTPSERVER;
import static dev.dworks.apps.anexplorer.misc.ConnectionUtils.ACTION_STOP_FTPSERVER;
import static dev.dworks.apps.anexplorer.misc.Utils.EXTRA_ROOT;

public class ServerFragment extends Fragment implements View.OnClickListener {

    private TextView status;
    private TextView username;
    private TextView password;
    private TextView path;
    private TextView address;
    private Button action;
    private TextView warning;
    private RootInfo root;

    public static void show(FragmentManager fm, RootInfo root) {
        final ServerFragment fragment = new ServerFragment();
        final Bundle args = new Bundle();
        args.putParcelable(EXTRA_ROOT, root);
        fragment.setArguments(args);
        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, fragment);
        ft.commitAllowingStateLoss();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_server, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        status = view.findViewById(R.id.status);
        username = view.findViewById(R.id.username);
        password = view.findViewById(R.id.password);
        path = view.findViewById(R.id.path);
        address = view.findViewById(R.id.address);
        warning = view.findViewById(R.id.warning);
        action = view.findViewById(R.id.action);
        action.setOnClickListener(this);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setRetainInstance(true);
        root = getArguments().getParcelable(EXTRA_ROOT);

        NetworkConnection connection = NetworkConnection.fromRootInfo(getActivity(), root);
        path.setText(connection.getPath());
        username.setText(connection.getUserName());
        password.setText(connection.getPassword());
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatus();

        IntentFilter wifiFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        ContextCompat.registerReceiver(
                getActivity(),
                mWifiReceiver,
                wifiFilter,
                ContextCompat.RECEIVER_EXPORTED);

        IntentFilter ftpFilter = new IntentFilter();
        ftpFilter.addAction(ACTION_FTPSERVER_STARTED);
        ftpFilter.addAction(ACTION_FTPSERVER_STOPPED);
        ftpFilter.addAction(ACTION_FTPSERVER_FAILEDTOSTART);
        ContextCompat.registerReceiver(
                getActivity(),
                mFtpReceiver,
                ftpFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            getActivity().unregisterReceiver(mWifiReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            getActivity().unregisterReceiver(mFtpReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void startServer() {
        Intent intent = new Intent(ACTION_START_FTPSERVER);
        intent.setPackage(getActivity().getPackageName());
        intent.putExtras(getArguments());

        if (DocumentsApplication.isWatch()) {
            Intent serverService = new Intent(getActivity(), ConnectionsService.class);
            serverService.putExtras(intent.getExtras());
            if (!ConnectionUtils.isServerRunning(getActivity())) {
                ContextCompat.startForegroundService(getActivity(), serverService);
            }
        } else {
            getActivity().sendBroadcast(intent);
        }
    }

    private void stopServer() {
        Intent intent = new Intent(ACTION_STOP_FTPSERVER);
        intent.setPackage(getActivity().getPackageName());
        intent.putExtras(getArguments());

        if (DocumentsApplication.isWatch()) {
            Intent serverService = new Intent(getActivity(), ConnectionsService.class);
            serverService.putExtras(intent.getExtras());
            getActivity().stopService(serverService);
        } else {
            getActivity().sendBroadcast(intent);
        }
    }

    private void updateStatus() {
        setStatus(ConnectionUtils.isServerRunning(getActivity()));
    }

    private void setStatus(boolean running) {
        if (running) {
            setText(address, ConnectionUtils.getFTPAddress(getActivity()));
            status.setText(getString(R.string.ftp_status_running));
            action.setText(R.string.stop_ftp);
        } else {
            setText(address, "");
            setText(warning, "");
            status.setText(getString(R.string.ftp_status_not_running));
            action.setText(R.string.start_ftp);
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.action) {
            if (!ConnectionUtils.isServerRunning(getActivity())) {
                if (ConnectionUtils.isConnectedToLocalNetwork(getActivity())) {
                    startServer();
                } else {
                    setText(warning, getString(R.string.ftp_no_wifi));
                }
            } else {
                stopServer();
            }
        }
    }

    private final BroadcastReceiver mWifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectionUtils.isConnectedToLocalNetwork(context)) {
                setText(warning, "");
            } else {
                stopServer();
                setStatus(false);
                setText(address, "");
                setText(warning, getString(R.string.ftp_no_wifi));
            }
        }
    };

    private final BroadcastReceiver mFtpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String receivedAction = intent.getAction();
            if (ACTION_FTPSERVER_STARTED.equals(receivedAction)) {
                setStatus(true);
            } else if (ACTION_FTPSERVER_FAILEDTOSTART.equals(receivedAction)) {
                setStatus(false);
                setText(warning, "Oops! Something went wrong");
            } else if (ACTION_FTPSERVER_STOPPED.equals(receivedAction)) {
                setStatus(false);
            }
        }
    };

    private void setTintedImage(ImageView imageview, int resourceId) {
        imageview.setImageDrawable(IconUtils.applyTintAttr(
                getActivity(), resourceId, android.R.attr.textColorPrimary));
    }

    private void setText(TextView textView, String text) {
        textView.setText(text);
        textView.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }
}
