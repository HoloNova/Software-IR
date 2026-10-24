package io.kcg.sir.application.conformance;

import io.kcg.sir.api.*;
import io.kcg.sir.ast.*;
import io.kcg.sir.change.edit.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Canonical test composition: the real shop after formal renaming, not a second cancellation implementation. */
public final class ShopSourceComposition {
    private ShopSourceComposition() {}
    static String compose() throws Exception {
        var entry=SourceId.of("project.sir");
        String shop=ConformanceFixtures.readResource("/topics/shop/project.sir").replace("\r\n","\n");
        var snapshot=new SourceSnapshot(entry,Map.of(entry,shop.getBytes(StandardCharsets.UTF_8)));
        var result=new SourceEditor().edit(snapshot,new CommandBatch(CommandBatch.PROTOCOL,"OnlineShop",SourceEditor.sha256(snapshot),List.of(
            new Command.Rename(Selector.root().named("declarations","Product"),"ShopProduct"),
            new Command.Rename(Selector.root().named("declarations","ProductView"),"ShopProductView"))));
        if(!(result instanceof EditResult.Edited edited))throw new IllegalStateException(result.toString());
        String renamed=new String(edited.sources().bytes(entry),StandardCharsets.UTF_8);
        String base=ConformanceFixtures.readResource("/valid/multiplication.sir").replace("\r\n","\n");
        var root=parse(base);var originals=new HashMap<String,AstDeclaration>();root.declarations().forEach(d->originals.put(name(d),d));
        var added=new ArrayList<String>();
        for(var declaration:parse(renamed).declarations()) {
            var existing=originals.get(name(declaration));
            if(existing!=null) {
                if(!(existing instanceof AstErrorDecl a) || !(declaration instanceof AstErrorDecl b)
                    || !name(declaration).equals("OutOfStock") || !a.httpStatus().equals(b.httpStatus()))
                    throw new IllegalStateException("unexpected declaration collision: "+name(declaration));
            } else added.add(fragment(renamed,declaration));
        }
        int insertion=base.offsetByCodePoints(0,root.declarations().getFirst().span().start().codePointOffset());
        return base.substring(0,insertion)+String.join("\n",added)+"\n"+base.substring(insertion);
    }
    static AstSourceUnit.Root parse(String text) {
        var result=SirParser.create().parseProject(new SirSource(SourceId.of("project.sir"),text));
        if(!result.isSuccess())throw new IllegalStateException(result.diagnostics().toString());
        return (AstSourceUnit.Root)result.unit().orElseThrow();
    }
    private static String fragment(String source,AstDeclaration d) {return source.substring(source.offsetByCodePoints(0,d.span().start().codePointOffset()),source.offsetByCodePoints(0,d.span().end().codePointOffset())).strip();}
    private static String name(AstDeclaration d) {
        return switch(d) {
            case AstEntityDecl x->x.name().text();case AstInputDecl x->x.name().text();case AstViewDecl x->x.name().text();
            case AstCapabilityDecl x->x.name().text();case AstEnumDecl x->x.name().text();case AstErrorDecl x->x.name().text();
            case AstSemanticTypeDecl x->x.name().text();default->null;
        };
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("one destination .sir path required");
        Files.writeString(Path.of(args[0]),compose(),StandardCharsets.UTF_8);
    }
}
