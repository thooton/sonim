package com.xp3.common.ui;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/** The SD card, where the apps save what they download. */
public final class Card {

    private Card() {}

    /**
     * The card's top folder: the shared volume that is not the phone's own.
     * Null when there is no card, or it isn't in yet.
     */
    public static File root(Context context) {
        File main = Environment.getExternalStorageDirectory();
        File[] volumes = context.getExternalFilesDirs(null);
        if (volumes != null) {
            for (File dir : volumes) {
                if (dir == null) continue;
                String path = dir.getAbsolutePath();
                int cut = path.indexOf("/Android/");
                if (cut <= 0) continue;
                File root = new File(path.substring(0, cut));
                if (!root.getAbsolutePath().equals(main.getAbsolutePath())) return root;
            }
        }
        return null;
    }
}
