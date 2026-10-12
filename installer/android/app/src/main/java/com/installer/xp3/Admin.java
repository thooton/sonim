package com.installer.xp3;

import android.app.admin.DeviceAdminReceiver;

/**
 * The device-admin component this app is registered under. It holds no
 * policies and does nothing on its own; it exists only because an app must
 * have a DeviceAdminReceiver before it can be made the device owner, and being
 * the device owner is what lets {@link InstallActivity} install without the
 * system installer's confirmation screen.
 */
public final class Admin extends DeviceAdminReceiver {
}
