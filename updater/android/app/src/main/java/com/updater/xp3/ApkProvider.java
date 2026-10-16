package com.updater.xp3;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.updater.xp3.core.Catalog;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets the installer read one downloaded APK, read-only, through a temporary
 * grant on its content URI. Only `<name>.apk` files in the downloads folder,
 * with a name the release could have given them, are served.
 */
public final class ApkProvider extends ContentProvider {

    static final String APK = "application/vnd.android.package-archive";
    private static final String AUTHORITY = "com.updater.xp3.apks";

    /** Where APKs wait for the installer; each is deleted once it has been. */
    static File folder(Context context) {
        return new File(context.getFilesDir(), "apks");
    }

    static File file(Context context, Catalog.Entry entry) {
        return new File(folder(context), entry.apk());
    }

    static Uri uri(Catalog.Entry entry) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(entry.apk()).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return APK;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = file(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {file.getName(), Long.valueOf(file.length())});
        return cursor;
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null || !name.endsWith(".apk")
            || !Catalog.validName(name.substring(0, name.length() - 4))) {
            throw new FileNotFoundException("Only downloaded APKs are served here");
        }
        File file = new File(folder(getContext()), name);
        if (!file.isFile()) throw new FileNotFoundException("Not downloaded");
        return file;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
