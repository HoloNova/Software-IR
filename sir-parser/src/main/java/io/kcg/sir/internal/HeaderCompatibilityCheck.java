package io.kcg.sir.internal;

import java.math.BigInteger;
import java.util.List;
import org.antlr.v4.runtime.tree.TerminalNode;

final class HeaderCompatibilityCheck {
    private static final BigInteger JAVA_21 = BigInteger.valueOf(21);
    private static final List<String> SUPPORTED_TARGET = List.of(
            "java", "spring_boot", "mybatis_plus", "mysql", "maven", "rest");

    boolean validate(
            SirParser.DocumentContext document,
            SourceText source,
            DiagnosticCollector diagnostics) {
        TerminalNode version = document.versionLiteral().DOTTED_NUMBER();
        if (!version.getText().equals("0.1")) {
            diagnostics.error(
                    "SIR-VERSION-001",
                    "当前实现只支持 SIR 0.1",
                    source.span(version.getSymbol()));
            return false;
        }

        SirParser.TargetBlockContext target = document.softwareDecl().targetBlock();
        List<TerminalNode> names = target.IDENT();
        BigInteger javaVersion = new BigInteger(target.INT().getText());
        for (int index = 0; index < SUPPORTED_TARGET.size(); index++) {
            String actual = names.get(index).getText();
            if (!SUPPORTED_TARGET.get(index).equals(actual)) {
                diagnostics.error(
                        "SIR-TARGET-001",
                        "当前实现不支持 Target 值：" + actual,
                        source.span(names.get(index).getSymbol()));
                return false;
            }
        }
        if (!javaVersion.equals(JAVA_21)) {
            diagnostics.error(
                    "SIR-TARGET-001",
                    "当前实现只支持 Java 21",
                    source.span(target.INT().getSymbol()));
            return false;
        }
        return true;
    }

}
