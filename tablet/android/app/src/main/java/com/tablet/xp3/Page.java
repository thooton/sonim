package com.tablet.xp3;

import android.graphics.Canvas;

/**
 * One page of a document, drawn 300 wide (Viewport.PAGE_W) and as tall as
 * its shape makes it, into a box the caller has scaled to wherever it
 * shows: a cover on the shelf, the preview, the minimap, the top of page 1
 * on the Transform sheet.
 *
 * Shown is a page the command center drew (Pages); Blank stands in while
 * one is on its way, or when there is none to show.
 */
interface Page {

    /** Its height when 300 wide. */
    float height();

    void draw(Canvas canvas);
}
