package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.change.edit.*;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.source.SourceSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultiplicationToolchainContractTest {
    @Test void actualHttpFixtureIsValidAndUsesTheExistingCreationStatus() throws Exception {
        var compiled = compile(ProjectTestSupport.oneFile("project.sir", ProjectTestSupport.resource("valid/multiplication.sir")));
        assertTrue(new SpringBootLoweredIrValidator().validate(compiled.loweredModel()).isEmpty());
        assertTrue(content(compiled, "PurchaseProductController.java").contains("HttpStatus.CREATED"));
    }

    @Test void shopUsesOneQuantityForAtomicStockTheOrderTotalAndItsLine() throws Exception {
        String source = ProjectTestSupport.resource("topics/shop/project.sir").replace("\r\n", "\n");
        assertTrue(source.contains("field quantity: Quantity;\n    }\n    error OutOfStock"), "BuyRequest must accept quantity");
        var compiled = compile(ProjectTestSupport.oneFile("project.sir", source));
        String service = content(compiled, "BuyNowService.java");
        assertTrue(service.contains(".multiply(java.math.BigDecimal.valueOf(input.getQuantity()))"), service);
        assertTrue(service.contains(".ge(Product::getStock, input.getQuantity())"), service);
        assertTrue(service.contains(".setDecrBy(Product::getStock, input.getQuantity())"), service);
        assertTrue(service.contains("line.setQuantity(input.getQuantity())"), service);
        int stockWrite = service.indexOf("productMapper.update("), orderWrite = service.indexOf("purchaseOrderMapper.insert(");
        assertTrue(stockWrite >= 0 && stockWrite < orderWrite, service);
        assertTrue(orderWrite < service.indexOf("line.setPurchaseId(purchase.getId())"), "persist the generated order ID before capturing its foreign key:\n" + service);
    }

    @Test void preciseInputRenameRebuildsConversionAndRequiredValuePlansWithoutRenamingOtherInputs() throws Exception {
        var source = ProjectTestSupport.oneFile("project.sir", ProjectTestSupport.resource("valid/multiplication.sir"));
        var result = new SourceEditor().edit(source, new CommandBatch(CommandBatch.PROTOCOL, "Multiplication",
                SourceEditor.sha256(source), List.of(new Command.Rename(
                    Selector.root().named("declarations", "Numbers").named("fields", "quantity"), "units"))));
        var edited = assertInstanceOf(EditResult.Edited.class, result);
        var compiled = compile(edited.sources());
        var capability = compiled.loweredModel().declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).filter(cap -> cap.javaName().equals("Multiply"))
                .findFirst().orElseThrow();
        assertTrue(capability.requiredInputs().stream().anyMatch(input -> input.fieldPath().equals("units") && input.propertyName().equals("units")));
        assertTrue(content(compiled, "MultiplyService.java").contains("java.math.BigDecimal.valueOf(input.getUnits())"));
        assertTrue(content(compiled, "PurchaseProductService.java").contains("java.math.BigDecimal.valueOf(input.getQuantity())"));
    }

    private static String content(ProjectCompilation.Compiled compiled, String name) {
        return compiled.generatedFiles().stream().filter(file -> file.relativePath().endsWith("/" + name)).findFirst().orElseThrow().content();
    }
    private static ProjectCompilation.Compiled compile(SourceSnapshot snapshot) {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(snapshot, new SpringBootGenerator()::generate, diagnostics);
        assertTrue(compiled.isPresent(), diagnostics.toString());
        return compiled.orElseThrow();
    }
}
