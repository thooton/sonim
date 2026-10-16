package com.updater.xp3.core;

/** Where the phone's copy of an app stands against the release. */
public enum Status {
    /** Not on the phone. */
    INSTALL,
    /** On the phone, older than the release. */
    UPDATE,
    /** On the phone, the release's version. */
    CURRENT,
    /** On the phone, newer than the release (built and installed by hand). */
    NEWER;

    /**
     * @param installed whether the app is on the phone at all
     * @param here      its versionName there, which may be null
     * @param latest    the release's versionName
     */
    public static Status of(boolean installed, String here, String latest) {
        if (!installed) return INSTALL;
        int c = Version.compare(here, latest);
        if (c < 0) return UPDATE;
        return c == 0 ? CURRENT : NEWER;
    }
}
