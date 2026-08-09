package com.youqi.studio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * One-click Termux setup: detect, install prompt, auto-configure permissions.
 */
final class TermuxSetup {
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_PROPERTIES = "/data/data/com.termux/files/home/.termux/termux.properties";

    enum State {
        NOT_INSTALLED,
        INSTALLED_NO_PERMISSION,
        READY
    }

    private final Context context;

    TermuxSetup(Context context) {
        this.context = context.getApplicationContext();
    }

    State checkState() {
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(TERMUX_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return State.NOT_INSTALLED;
        }
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND")
                != PackageManager.PERMISSION_GRANTED) {
            return State.INSTALLED_NO_PERMISSION;
        }
        return State.READY;
    }

    /** Open Play Store page for Termux. */
    void openInstallPage() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        intent.setData(Uri.parse("market://details?id=" + TERMUX_PACKAGE));
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            // Play Store not available, try F-Droid or browser
            intent.setData(Uri.parse("https://f-droid.org/packages/com.termux/"));
            context.startActivity(intent);
        }
    }

    /** Open Termux app info page so user can grant "Allow external apps" permission manually if needed. */
    void openAppInfoPage() {
        android.content.Intent intent = new android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + TERMUX_PACKAGE));
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Run initial bootstrap commands: allow-external-apps, install essential packages. */
    boolean runBootstrap(TermuxBridge bridge) throws Exception {
        if (!bridge.isInstalled()) return false;
        // Write termux.properties to allow external apps
        try {
            bridge.run("mkdir -p ~/.termux && echo 'allow-external-apps=true' > ~/.termux/termux.properties && termux-reload-settings", null, "");
        } catch (Exception e) {
            // Non-fatal: may already be configured
        }
        // Install essential packages for agent + MCP
        bridge.run("pkg update -y && pkg install -y git ripgrep python nodejs-lts", null, "");
        return true;
    }
}
