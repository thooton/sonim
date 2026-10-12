package com.delta.xp3;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.Arrays;

/**
 * What the installer would say about a downloaded APK, said first and in words.
 *
 * An app already on the phone is updated in place by the same install prompt,
 * keeping its data. The installer refuses a different signing key or an older
 * version with only "App not installed", so both are checked here and
 * explained instead.
 */
final class Apks {

    /** The verdict: a problem to show, or a label for the install. */
    static final class Verdict {
        /** Null when the APK can go to the installer. */
        final String problem;
        /** "List 1.0.0", or "List 1.0.0 → 1.0.1" for an update. */
        final String label;
        final boolean update;

        Verdict(String problem, String label, boolean update) {
            this.problem = problem;
            this.label = label;
            this.update = update;
        }
    }

    private Apks() {}

    static Verdict inspect(Context context, File apk) {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (info == null) return new Verdict("That file isn't an app this phone can read.", null, false);
        // The label is only readable once the archive's own path is filled in.
        ApplicationInfo app = info.applicationInfo;
        app.sourceDir = apk.getPath();
        app.publicSourceDir = apk.getPath();
        String label = pm.getApplicationLabel(app).toString();

        PackageInfo installed = installed(pm, info.packageName);
        if (installed != null && !Arrays.equals(installed.signatures, info.signatures)) {
            return new Verdict("Can't update " + label + ": signed with a different key.", label, true);
        }
        if (installed != null && versionCode(info) < versionCode(installed)) {
            return new Verdict("Can't update " + label + ": " + version(info)
                + " is older than the installed " + version(installed) + ".", label, true);
        }
        if (installed != null) {
            return new Verdict(null, label + " " + version(installed) + " → " + version(info), true);
        }
        return new Verdict(null, label + (info.versionName != null ? " " + info.versionName : ""), false);
    }

    /** The copy of `packageName` already on the phone, or null. */
    private static PackageInfo installed(PackageManager pm, String packageName) {
        try {
            return pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // getLongVersionCode arrives in API 28; the XP3 is 27.
    @SuppressWarnings("deprecation")
    private static int versionCode(PackageInfo info) {
        return info.versionCode;
    }

    private static String version(PackageInfo info) {
        return info.versionName != null ? info.versionName : "version " + versionCode(info);
    }
}
