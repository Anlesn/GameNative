package com.winlator.xenvironment.components;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.winlator.xenvironment.EnvironmentComponent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.gamenative.PrefManager;
import app.gamenative.R;
import app.gamenative.service.epic.EpicDeviceActivation;
import app.gamenative.ui.screen.auth.EpicOAuthActivity;
import app.gamenative.utils.AuthUrlRedactionKt;

public class WineRequestComponent extends EnvironmentComponent {
    private static final String TAG = "WineRequestComponent";

    abstract class RequestCodes {
        static final int OPEN_URL = 1;
        //static final int GET_WINE_CLIPBOARD = 2;
        //static final int SET_WINE_CLIPBAORD = 3;
    }

    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private ExecutorService executor;

    public void start() {
        isRunning = true;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(20000, 50, InetAddress.getLocalHost());
                while (isRunning) {
                    Socket socket = serverSocket.accept();
                    DataInputStream inputStream = new DataInputStream(socket.getInputStream());
                    DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                    int requestCode = inputStream.readInt();
                    handleRequest(inputStream, outputStream, requestCode);
                    socket.close();
                }
            } catch (IOException e) {
            }
        });
    }

    public void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
            }
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    public void handleRequest(DataInputStream inputStream, DataOutputStream outputStream, int requestCode) throws IOException {
        switch(requestCode) {
            case RequestCodes.OPEN_URL:
                openURL(inputStream, outputStream);
                break;
//            case RequestCodes.GET_WINE_CLIPBOARD:
//                getWineClipboard(inputStream, outputStream);
//                break;
//            case RequestCodes.SET_WINE_CLIPBAORD:
//                setWineClipboard(inputStream, outputStream);
//                break;
        }
    }

    private void openURL(DataInputStream inputStream, DataOutputStream outputStream) throws IOException {
        Context context = environment.getContext();

        int messageLength = inputStream.readInt();
        byte[] data = new byte[messageLength];
        inputStream.readFully(data);
        String url = new String(data, StandardCharsets.UTF_8);
        String loggableUrl = AuthUrlRedactionKt.redactUrlForLogging(url);

        // EOS device-code fallback: the game waits until the user approves the code, so never drop it.
        EpicDeviceActivation activation = EpicDeviceActivation.parse(url);
        if (activation != null) {
            Log.i(TAG, "OPEN_URL " + loggableUrl + " -> Epic device activation in EpicOAuthActivity"
                    + (activation.getUserCode() != null ? " (userCode present)" : " (no userCode)"));
            showEpicActivationToast(context, activation.getUserCode());
            startEpicWebView(context, activation.getPageUrl());
            return;
        }

        if (url.startsWith(EpicOAuthActivity.EPIC_AUTH_URL_PREFIX)) {
            Log.i(TAG, "OPEN_URL " + loggableUrl + " -> EpicOAuthActivity");
            startEpicWebView(context, url);
            return;
        }

        // Same store and default as the "Open web links externally" setting in the UI.
        if (!PrefManager.INSTANCE.getOpenWebLinksExternally()) {
            Log.i(TAG, "OPEN_URL " + loggableUrl + " ignored: open_web_links_externally is off");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "OPEN_URL " + loggableUrl + " -> external browser");
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "OPEN_URL " + loggableUrl + " dropped: no app handles it", e);
        }
    }

    private static void startEpicWebView(Context context, String url) {
        Intent intent = new Intent();
        intent.setClass(context, EpicOAuthActivity.class);
        intent.putExtra(EpicOAuthActivity.EXTRA_GAME_AUTH_URL, url);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static void showEpicActivationToast(Context context, String userCode) {
        Context appContext = context.getApplicationContext();
        String message = userCode != null
                ? appContext.getString(R.string.epic_device_activation_code, userCode)
                : appContext.getString(R.string.epic_device_activation_no_code);
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(appContext, message, Toast.LENGTH_LONG).show());
    }

}
