package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Renders only the validated local conversion; target limits and storage choices belong to Lowering. */
final class CalendarStepRenderer {
    private CalendarStepRenderer() {}

    static List<String> render(SpringBootWorkflow.MonthStep step, Map<SymbolId,String> names,
                               String support, String error, String input) {
        var lines=new ArrayList<String>();
        String start=names.getOrDefault(step.since().symbol(),step.since().targetName());
        String end=names.getOrDefault(step.until().symbol(),step.until().targetName());
        lines.add("        java.time.Instant " + start + ";");
        lines.add("        java.time.Instant " + end + ";");
        lines.add("        {");
        if(input!=null)lines.add("            if ("+input+" == null) throw new "+error+"();");
        capture(lines,step.month(),"$monthText",names);
        capture(lines,step.zone(),"$zoneText",names);
        lines.add("            if ($monthText == null || !$monthText.matches(\"[0-9]{4}-(0[1-9]|1[0-2])\") || $zoneText == null || !"+support+".acceptsTimeZone($zoneText)) throw new "+error+"();");
        lines.add("            try {");
        lines.add("                var $calendarMonth = java.time.YearMonth.parse($monthText);");
        lines.add("                var $calendarZone = java.time.ZoneId.of($zoneText);");
        lines.add("                "+start+" = $calendarMonth.atDay(1).atStartOfDay($calendarZone).toInstant();");
        lines.add("                "+end+" = $calendarMonth.plusMonths(1).atDay(1).atStartOfDay($calendarZone).toInstant();");
        lines.add("                var $calendarMin = java.time.Instant.parse("+StringEscape.javaString(step.plan().minimumInstant())+");");
        lines.add("                var $calendarMax = java.time.Instant.parse("+StringEscape.javaString(step.plan().maximumInstant())+");");
        lines.add("                if ("+start+".isBefore($calendarMin) || "+start+".isAfter($calendarMax) || "+end+".isBefore($calendarMin) || "+end+".isAfter($calendarMax) || !"+start+".isBefore("+end+")) throw new "+error+"();");
        lines.add("            } catch (java.time.DateTimeException $calendarFailure) { throw new "+error+"(); }");
        lines.add("        }");
        return List.copyOf(lines);
    }

    private static void capture(List<String> lines, SpringExpression expression, String name, Map<SymbolId,String> names) {
        if(expression instanceof SpringExpression.OptionalPayloadValue optional) {
            lines.add("            var "+name+"Raw = "+ExpressionRenderer.renderExpression(optional.payloadMember(),names)+";");
            lines.add("            String "+name+" = "+name+"Raw == null ? null : "+name+"Raw.orElse(null);");
        } else lines.add("            String "+name+" = "+ExpressionRenderer.renderExpression(expression,names)+";");
    }
}
