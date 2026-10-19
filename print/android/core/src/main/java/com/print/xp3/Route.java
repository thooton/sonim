package com.print.xp3;

import com.print.xp3.raster.AppleRaster;
import com.print.xp3.raster.Back;
import com.print.xp3.raster.Format;
import com.print.xp3.raster.Pcl;
import com.print.xp3.raster.Pclm;
import com.print.xp3.raster.PostScript;
import com.print.xp3.raster.PwgRaster;

/** One way to print a job: how it travels, in what language, and the settings that go with it. */
public final class Route {

    public enum Transport { IPP, RAW, LPD }

    public enum Kind {
        PDF("application/pdf", "PDF"),
        PWG("image/pwg-raster", ""),
        URF("image/urf", ""),
        PCLM("application/PCLm", ""),
        POSTSCRIPT("application/postscript", "POSTSCRIPT"),
        PCL("application/vnd.hp-PCL", "PCL");

        public final String mime;
        /** PJL's name for it, for a raw port. */
        public final String pjl;

        Kind(String mime, String pjl) {
            this.mime = mime;
            this.pjl = pjl;
        }
    }

    public Transport transport;
    public Kind kind;
    /** What document-format says over IPP; usually kind.mime. */
    public String mime;
    public int dpi = 300;
    public boolean color;
    /** Frame.ONE_SIDED, LONG_EDGE or SHORT_EDGE: what the printer was found able to do. */
    public int duplex;
    public Back back = Back.NONE;
    public int pclmStrip = 16;
    /** Copies the printer makes (IPP's copies, PCL's and PostScript's own); 1 when not. */
    public int printerCopies = 1;
    /** Copies made by sending every page again. */
    public int repeat = 1;
    /** Copies made by sending the whole job again (a PDF to a printer that won't copy). */
    public int jobs = 1;
    /** Whether IPP is told the page range (a PDF sent whole); otherwise only those pages are sent. */
    public boolean pageRanges;
    /** Whether a raw or LPD job is wrapped in PJL. */
    public boolean pjl;

    public boolean raster() {
        return kind != Kind.PDF;
    }

    public Format format() {
        switch (kind) {
            case PWG:
                return new PwgRaster();
            case URF:
                return new AppleRaster();
            case PCLM:
                return new Pclm(pclmStrip);
            case POSTSCRIPT:
                return new PostScript();
            case PCL:
                return new Pcl();
            default:
                throw new IllegalStateException("No page images for " + kind);
        }
    }

    @Override
    public String toString() {
        return transport + " " + kind + (raster() ? " " + dpi + "dpi " + (color ? "color" : "grey") : "");
    }
}
