package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.kcg.sir.application.api.ToolchainApplication;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ApplicationArchitectureTest {

    @Test
    void publicApplicationApiDoesNotExposeTransactionHooks() {
        long publicExecuteMethods = Arrays.stream(ToolchainApplication.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("execute"))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .count();

        assertEquals(1L, publicExecuteMethods,
                "only execute(ToolchainRequest) may be part of the public API");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.kcg.sir.application.api.TransactionHooks"));
    }
}
