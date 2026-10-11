package com.delta.xp3;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.delta.xp3.data.RemoteFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Lets the system installer, or whatever app opens a file, read one
 * downloaded file, read-only, through a temporary grant on its content URI.
 * Only plain names in the downloads folder are served.
 */
public final class ShareProvider extends ContentProvider {

    static final String APK = "application/vnd.android.package-archive";
    private static final String AUTHORITY = "com.delta.xp3.files";

    /** Where downloaded files live, each under its command-center name. */
    static File folder(Context context) {
        return new File(context.getFilesDir(), "downloads");
    }

    static Uri uri(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build();
    }

    static String mime(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".apk")) return APK;
        int dot = lower.lastIndexOf('.');
        String type = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower.substring(dot + 1));
        return type == null ? "application/octet-stream" : type;
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
        String name = uri.getLastPathSegment();
        return name == null ? null : mime(name);
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
        if (uri.getPathSegments().size() != 1 || !RemoteFile.validName(name)) {
            throw new FileNotFoundException("Only downloaded files are served here");
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
