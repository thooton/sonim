package com.canvas.xp3.data;

import java.util.ArrayList;
import java.util.List;

/** A band of work under one heading, as the lists are grouped. */
public final class Section {

    public static final String NO_DUE_DATE = "No due date";
    public static final String DONE_EARLY = "Done early";
    public static final String THIS_WEEK = "This week";
    public static final String NEXT_WEEK = "Next week";
    public static final String LAST_WEEK = "Last week";
    public static final String LATER = "Later";
    public static final String EARLIER = "Earlier";

    public final String label;
    public final List<Assignment> items = new ArrayList<>();

    Section(String label) {
        this.label = label;
    }
}
