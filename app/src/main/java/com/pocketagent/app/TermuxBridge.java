package com.youqi.studio;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class TermuxBridge {
    static final String EXTRA_REQUEST_ID = "pocket_agent_request_id";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final ConcurrentHashMap<Integer, CompletableFuture<Bundle>> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1000);
    private final Context context;

    TermuxBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isInstalled() {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    boolean hasPermission() {
        return context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED;
    }

    String run(String command, Uri treeUri, String relativePath) throws Exception {
        if (!isInstalled()) {
            throw new IllegalStateException("Termux is not installed. Install Termux first, then enable allow-external-apps.");
        }
        if (!hasPermission()) {
            throw new SecurityException("Pocket Agent does not have Termux RUN_COMMAND permission");
        }
        String workingDirectory = rawPath(treeUri, relativePath);
        int requestId = NEXT_ID.incrementAndGet();
        CompletableFuture<Bundle> future = new CompletableFuture<>();
        PENDING.put(requestId, future);

        Intent callbackIntent = new Intent(context, TermuxResultReceiver.class)
                .putExtra(EXTRA_REQUEST_ID, requestId);
        PendingIntent callback = PendingIntent.getBroadcast(context, requestId, callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent commandIntent = new Intent(ACTION_RUN_COMMAND)
                .setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                .putExtra(EXTRA_PATH, "/data/data/com.termux/files/usr/bin/bash")
                .putExtra(EXTRA_ARGUMENTS, new String[]{"-lc", command})
                .putExtra(EXTRA_WORKDIR, workingDirectory)
                .putExtra(EXTRA_BACKGROUND, true)
                .putExtra(EXTRA_PENDING_INTENT, callback);
        try {
            context.startService(commandIntent);
            Bundle result = future.get(2, TimeUnit.MINUTES);
            if (result == null) throw new IllegalStateException("Termux returned no result bundle");
            return new JSONObject()
                    .put("ok", result.getInt("exitCode", -1) == 0)
                    .put("exit_code", result.getInt("exitCode", -1))
                    .put("stdout", limited(result.getString("stdout", "")))
                    .put("stderr", limited(result.getString("stderr", "")))
                    .put("error", result.getString("errmsg", ""))
                    .put("working_directory", workingDirectory)
                    .toString();
        } finally {
            PENDING.remove(requestId);
        }
    }

    static void complete(int requestId, Bundle result) {
        CompletableFuture<Bundle> future = PENDING.get(requestId);
        if (future != null) future.complete(result);
    }

    static String rawPath(Uri treeUri, String relativePath) {
        if (treeUri == null) throw new IllegalStateException("Select a project directory first");
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        String[] parts = documentId.split(":", 2);
        String root;
        if (parts.length == 1 || "primary".equalsIgnoreCase(parts[0])) {
            root = "/storage/emulated/0";
        } else {
            root = "/storage/" + parts[0];
        }
        String documentPath = parts.length == 2 ? parts[1] : "";
        String child = ProjectStore.normalize(relativePath);
        String path = root;
        if (!documentPath.isEmpty()) path += "/" + documentPath;
        if (!child.isEmpty()) path += "/" + child;
        return path;
    }

    private static String limited(String value) {
        if (value == null) return "";
        return value.length() <= 60_000 ? value : value.substring(0, 60_000) + "\n... output truncated";
    }
}
