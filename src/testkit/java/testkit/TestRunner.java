package testkit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Tiny test runner for the offline build: discovers {@code *Test} classes under a class
 * directory, runs their {@code @Test} methods with {@code @BeforeEach}/{@code @AfterEach},
 * and exits non-zero on any failure. CI runs the same sources under the real JUnit platform.
 */
public final class TestRunner {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "build/test-classes");
        List<String> classes;
        try (Stream<Path> files = Files.walk(root)) {
            classes = files.map(p -> root.relativize(p).toString())
                    .filter(n -> n.endsWith("Test.class") && !n.contains("$"))
                    .map(n -> n.substring(0, n.length() - ".class".length()).replace('/', '.').replace('\\', '.'))
                    .sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot scan " + root, e);
        }
        int run = 0;
        List<String> failures = new ArrayList<>();
        long started = System.nanoTime();
        for (String cn : classes) {
            Class<?> c = Class.forName(cn);
            List<Method> tests = Arrays.stream(c.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Test.class))
                    .sorted(Comparator.comparing(Method::getName)).toList();
            if (tests.isEmpty() || Modifier.isAbstract(c.getModifiers())) continue;
            System.out.println(c.getSimpleName());
            for (Method m : tests) {
                run++;
                long t0 = System.nanoTime();
                Throwable error = runOne(c, m);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (error == null) {
                    System.out.printf(Locale.ROOT, "  ok    %-58s %6d ms%n", m.getName(), ms);
                } else {
                    System.out.printf(Locale.ROOT, "  FAIL  %-58s %6d ms%n        %s%n", m.getName(), ms, error);
                    failures.add(c.getSimpleName() + "." + m.getName() + ": " + error);
                    error.printStackTrace(System.out);
                }
            }
        }
        double secs = (System.nanoTime() - started) / 1e9;
        System.out.println();
        System.out.printf(Locale.ROOT, "Tests run: %d, Failures: %d, Time: %.1f s%n", run, failures.size(), secs);
        failures.forEach(f -> System.out.println("  " + f));
        if (run == 0) {
            System.out.println("no tests found under " + root);
            System.exit(1);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static Throwable runOne(Class<?> c, Method test) {
        Object instance;
        try {
            var ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            instance = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            return e;
        }
        Throwable error = null;
        try {
            invokeAll(c, instance, BeforeEach.class);
            test.setAccessible(true);
            test.invoke(instance);
        } catch (Throwable t) {
            error = unwrap(t);
        } finally {
            try {
                invokeAll(c, instance, AfterEach.class);
            } catch (Throwable t) {
                if (error == null) error = unwrap(t);
            }
        }
        return error;
    }

    private static void invokeAll(Class<?> c, Object instance, Class<? extends java.lang.annotation.Annotation> a)
            throws Throwable {
        for (Method m : c.getDeclaredMethods()) {
            if (m.isAnnotationPresent(a)) {
                m.setAccessible(true);
                m.invoke(instance);
            }
        }
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
    }
}
