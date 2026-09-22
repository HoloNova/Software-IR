package io.kcg.sir.generator.springboot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deliberate, test-only architecture violation used as a decoy for the production boundary gate.
 *
 * <p>The task of this class is to be wrong on purpose: it reads the file system through
 * {@code java.nio.file} and reaches the process environment through {@code java.lang.System}. The
 * boundary gate must report exactly those forbidden references when it scans this class file, which
 * proves the gate is not a test that can only ever return "no violations".
 *
 * <p>It lives under {@code src/test}, is compiled into {@code target/test-classes} and must never
 * appear in the production class census, in the published JAR or in any generated project. Nothing
 * in the production code may call it, and the gate asserts that it sits outside the production
 * output directory.
 */
final class ForbiddenReferenceProbe {

    private ForbiddenReferenceProbe() {
    }

    static String describe(Path path) {
        try {
            return Files.readString(path) + System.lineSeparator();
        } catch (IOException e) {
            return e.getMessage();
        }
    }
}
