package com.delta.xp3;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * The one file the camera may write into: where a picture taken from the menu
 * lands.
 *
 * This is how the phone's own Messages app does it. It hands the camera a
 * content:// address served by its own provider (mms_temp_file/scrapSpace)
 * as EXTRA_OUTPUT, so the full-size shot is written straight into the app's
 * private storage without either app needing a storage permission. Unlike
 * Messages' provider, this one is not exported: the camera reaches it only
 * through the write grant on the intent that opened it, and only at this one
 * path.
 */
public final class CaptureProvider extends ContentProvider {

    static final String AUTHORITY = "com.delta.xp3.capture";
    private static final String NAME = "capture.jpg";

    static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = file(uri);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    /** Name and size, which some cameras ask for before writing. */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        File file;
        try {
            file = file(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {NAME, Long.valueOf(file.length())});
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] arguments) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] arguments) {
        return 0;
    }

    private File file(Uri uri) throws FileNotFoundException {
        if (!NAME.equals(uri.getLastPathSegment()) || uri.getPathSegments().size() != 1) {
            throw new FileNotFoundException("Only the capture file is served here");
        }
        return Pictures.capture(getContext().getFilesDir());
    }
}
