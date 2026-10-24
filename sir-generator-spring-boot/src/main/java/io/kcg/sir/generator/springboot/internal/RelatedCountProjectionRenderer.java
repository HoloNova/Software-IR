package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.*;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import java.util.List;
import java.util.stream.Collectors;

/** Prepares one index for all source rows; field construction never performs a query. */
final class RelatedCountProjectionRenderer {
    private RelatedCountProjectionRenderer() {}
    record PreparedRead(String indexVariable,List<String> lines) {
        PreparedRead { lines=List.copyOf(lines); }
    }
    static PreparedRead prepare(GenerationContext ctx,ViewDeclaration owner,RelatedCountPlan plan,String rows,
            String mapper,String suffix) {
        String helper=ctx.model().basePackage()+".persistence.RelatedCounts";
        var child=ctx.entity(plan.targetEntitySymbol());
        String entity=ctx.declaredPackage(new LoweredJavaType.Declared(LoweredJavaType.DeclaredKind.ENTITY,
            child.sourceSymbol(),child.javaName()))+"."+child.javaName();
        String keys="$count"+suffix+"Keys",index="$count"+suffix+"Index";
        String query="new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<"+entity+">("+entity+".class)"
            +".select("+StringEscape.javaString(plan.keyProjection())+", "+StringEscape.javaString(plan.countProjection())+")"
            +".in("+StringEscape.javaString(plan.keyColumn())+", "+keys+")";
        if(!plan.conditionSql().isEmpty()) query+=".apply("+StringEscape.javaString(plan.conditionSql())
            +plan.arguments().stream().map(WorkflowRenderer::renderExistsArgument).collect(Collectors.joining(", ",plan.arguments().isEmpty()?"":", ",""))+")";
        query+=".groupBy("+StringEscape.javaString(plan.keyColumn())+")";
        String decoder=plan.keyType().equals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.UUID))?"uuidKey":"longKey";
        return new PreparedRead(index,List.of(
            "        var "+keys+" = "+rows+".stream().map($source -> $source.get"+Character.toUpperCase(plan.sourceKeyPropertyName().charAt(0))
                +plan.sourceKeyPropertyName().substring(1)+"()).distinct().toList();",
            "        var "+index+" = "+helper+".read("+keys+", "+plan.maxKeys()+", "+StringEscape.javaString(plan.keyAlias())+", "
                +StringEscape.javaString(plan.countAlias())+", "+helper+"::"+decoder+", () -> this."+mapper+"."+plan.mapperMethod()+"("+query+"));"));
    }
}
