package io.kcg.sir.generator.springboot.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the physical expressions for nullable decimal, time and reference values, without starting an application. */
class OptionalComparisonRenderingTest {
    @Test
    void decimalEqualityIsNumericAndNullableOrderingsDoNotDereferenceNull(@TempDir Path dir) throws Exception {
        var decimal = new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BIG_DECIMAL);
        var instant = new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.INSTANT);
        String eq = render(decimal, SpringExpression.BinaryOperator.EQ, true, true);
        String lt = render(instant, SpringExpression.BinaryOperator.LT, true, false);
        var reference = new LoweredJavaType.EntityReference(new SymbolId("sir://test/entity/User"), "User", new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LONG));
        String ref = render(reference, SpringExpression.BinaryOperator.EQ, true, false);
        Path file = dir.resolve("Probe.java");
        Files.writeString(file, "public class Probe {"
                + "public static boolean eq(java.math.BigDecimal left, java.math.BigDecimal right) { return " + eq + "; }"
                + "public static boolean lt(java.time.Instant left, java.time.Instant right) { return " + lt + "; }"
                + "public static boolean ref(Long left, Long right) { return " + ref + "; }}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), file.toString()));
        try (var loader = new URLClassLoader(new java.net.URL[] {dir.toUri().toURL()})) {
            var probe = loader.loadClass("Probe");
            var equals = probe.getMethod("eq", java.math.BigDecimal.class, java.math.BigDecimal.class);
            assertEquals(true, equals.invoke(null, new java.math.BigDecimal("1.0"), new java.math.BigDecimal("1.00")));
            assertEquals(false, equals.invoke(null, null, new java.math.BigDecimal("1.0")));
            var less = probe.getMethod("lt", java.time.Instant.class, java.time.Instant.class);
            assertEquals(false, less.invoke(null, null, java.time.Instant.EPOCH));
            assertEquals(true, less.invoke(null, java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(1)));
            var same = probe.getMethod("ref", Long.class, Long.class);
            assertEquals(false, same.invoke(null, null, 1000L));
            assertEquals(true, same.invoke(null, 1000L, 1000L));
        }
    }

    private static String render(LoweredJavaType type, SpringExpression.BinaryOperator operator, boolean leftOptional, boolean rightOptional) {
        var span = new SourceSpan(SourceId.of("probe.sir"), new SourcePosition(0, 1, 1), new SourcePosition(1, 1, 2));
        var origin = new LoweredOrigin(Optional.empty(), new AstNodeId("probe"), span);
        var left = new SpringExpression.NameExpression(new LoweredNodeId("lir://probe/left"), origin,
                leftOptional ? new LoweredJavaType.OptionalValue(type) : type, new SymbolId("sir://probe/left"), "left");
        var right = new SpringExpression.NameExpression(new LoweredNodeId("lir://probe/right"), origin,
                rightOptional ? new LoweredJavaType.OptionalValue(type) : type, new SymbolId("sir://probe/right"), "right");
        var comparison = new SpringExpression.BinaryExpression(new LoweredNodeId("lir://probe/comparison"), origin,
                new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BOOLEAN), left, operator, right,
                Optional.of(new SpringExpression.PresentComparison(type, leftOptional, rightOptional)));
        return ExpressionRenderer.renderExpression(comparison, Map.of());
    }
}
