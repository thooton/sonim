package com.print.xp3;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;
import java.io.IOException;

/**
 * Opens each PDF for the first time in a process of its own (":check").
 * On Android 8.1 a PDF that fails to open (protected, damaged) leaves
 * pdfium's last error set for good, and every PdfRenderer after it in that
 * process fails the same way. So files are tried here first, the app's own
 * process opens only those that opened here, and this process ends on the
 * call after a failure, so the next file is tried in a fresh one.
 */
public final class PdfCheck extends ContentProvider {

    static final Uri URI = Uri.parse("content://com.print.xp3.check");
    static final String PAGES = "pages";

    /** A file failed to open here: pdfium can't be trusted in this process any more. */
    private static boolean spoilt;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (spoilt) {
            // The caller sees the process end and asks again, of a fresh one.
            Process.killProcess(Process.myPid());
            return null;
        }
        Bundle answer = new Bundle();
        answer.putInt(PAGES, count(new File(arg)));
        return answer;
    }

    private static int count(File file) {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            PdfRenderer renderer;
            try {
                renderer = new PdfRenderer(fd);
            } catch (SecurityException e) {
                spoilt = true;
                return Pages.LOCKED;
            } catch (IOException | RuntimeException e) {
                spoilt = true;
                return Pages.BROKEN;
            }
            int count = renderer.getPageCount();
            renderer.close();
            return count > 0 ? count : Pages.BROKEN;
        } catch (IOException | RuntimeException e) {
            // Not readable at all (gone, no permission): pdfium wasn't asked.
            return Pages.BROKEN;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
