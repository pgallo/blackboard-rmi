package org.junit.jupiter.api;

import org.junit.jupiter.api.function.Executable;

import java.util.Objects;

/**
 * Subset of the JUnit 5 assertions with the same signatures, so the test sources compile
 * unchanged against either this shim (offline build) or the real library (Maven build).
 */
public final class Assertions {

    private Assertions() {
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    public static void assertTrue(boolean condition) {
        assertTrue(condition, "expected true");
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) fail(message);
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, "expected false");
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) fail(message);
    }

    public static void assertNull(Object actual) {
        assertNull(actual, "expected null");
    }

    public static void assertNull(Object actual, String message) {
        if (actual != null) fail(message + " ==> was <" + actual + ">");
    }

    public static void assertNotNull(Object actual) {
        assertNotNull(actual, "expected not null");
    }

    public static void assertNotNull(Object actual, String message) {
        if (actual == null) fail(message);
    }

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            fail((message == null ? "" : message + " ==> ") + "expected: <" + expected + "> but was: <" + actual + ">");
        }
    }

    public static void assertEquals(long expected, long actual) {
        assertEquals((Object) expected, (Object) actual, null);
    }

    public static void assertEquals(long expected, long actual, String message) {
        assertEquals((Object) expected, (Object) actual, message);
    }

    public static void assertEquals(double expected, double actual, double delta) {
        assertEquals(expected, actual, delta, null);
    }

    public static void assertEquals(double expected, double actual, double delta, String message) {
        if (Math.abs(expected - actual) > delta) {
            fail((message == null ? "" : message + " ==> ") + "expected: <" + expected + "> but was: <" + actual + ">");
        }
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
        try {
            executable.execute();
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) return expectedType.cast(t);
            throw new AssertionError("expected " + expectedType.getName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + expectedType.getName() + " to be thrown");
    }
}
