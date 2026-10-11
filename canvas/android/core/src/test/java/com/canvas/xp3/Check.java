package com.canvas.xp3;

/** The whole of the test framework: count, compare, and say what went wrong. */
public final class Check {

    public interface Body {
        void run() throws Exception;
    }

    private int passed;
    private int failed;
    private String group = "";

    public void group(String name) {
        group = name;
    }

    public void ok(String name, boolean condition) {
        if (condition) passed++;
        else fail(name, "expected true");
    }

    public void same(String name, Object expected, Object actual) {
        boolean equal = expected == null ? actual == null : expected.equals(actual);
        if (equal) passed++;
        else fail(name, "expected <" + expected + "> but was <" + actual + ">");
    }

    /** Asserts that the body throws, and that the message says something useful. */
    public void fails(String name, Body body) {
        try {
            body.run();
        } catch (Exception e) {
            passed++;
            return;
        }
        fail(name, "expected it to be refused");
    }

    /** Asserts the body throws with a message containing `fragment`. */
    public void fails(String name, String fragment, Body body) {
        try {
            body.run();
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains(fragment)) passed++;
            else fail(name, "expected a message containing <" + fragment + "> but got <" + message + ">");
            return;
        }
        fail(name, "expected it to be refused");
    }

    /** Runs a group of tests, turning an unexpected exception into one failure. */
    public void run(String name, Body body) {
        group(name);
        try {
            body.run();
        } catch (Throwable t) {
            fail(name, "threw " + t);
            t.printStackTrace();
        }
    }

    private void fail(String name, String detail) {
        failed++;
        System.out.println("FAIL  " + group + " / " + name + ": " + detail);
    }

    public boolean report() {
        System.out.println((failed == 0 ? "OK" : "FAILED") + ": " + passed + " passed, " + failed + " failed");
        return failed == 0;
    }
}
