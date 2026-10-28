package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.RequiredInput;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which input fields a capability requires is decided per capability: paging-only page/size keep their defaults. */
class RequiredInputLoweringTest {
    private static final String SOURCE = """
            sir 0.3
            software Required {
              metadata { displayName "Required"; namespace "example.required"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus;
                database mysql; build maven; interface rest; }
              sources { } imports { }
              declarations {
                entity Entry persistent { identity id: Id generated auto; field count: Count; }
                view EntryView from Entry { field id: Id; field count: Count; }
                input Query {
                  field least: Count; field note: Optional<Text>; field page: PageNo; field size: PageSize;
                }
                error Refused http 409;
                capability Paged {
                  input Query; output Page<EntryView>; fails Refused; requires readonly; expose query;
                  workflow {
                    find Entry where item.count >= input.least order by id ascending Page input.page, input.size else Refused as entries;
                    return entries;
                  }
                }
                capability Unpaged {
                  input Query; output Unit; fails Refused; requires readonly; expose query;
                  workflow { validate input.least >= 0 else Refused; return unit; }
                }
                capability PageMath {
                  input Query; output Page<EntryView>; fails Refused; requires readonly; expose query;
                  workflow {
                    validate input.page + 0 > 0 else Refused;
                    find Entry where item.count >= input.least order by id ascending Page input.page, input.size else Refused as entries;
                    return entries;
                  }
                }
              }
            }
            """;

    @Test void pagingOnlyFieldsKeepDefaultsAndEveryOtherNonOptionalFieldIsRequired() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSourceSuccess(SOURCE);
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
        // The same input: Paged pages with page/size, Unpaged does not, PageMath also computes with page.
        assertEquals(List.of("least"), paths(model, "Paged"));
        assertEquals(List.of("least", "page", "size"), paths(model, "Unpaged"));
        assertEquals(List.of("least", "page"), paths(model, "PageMath"));
    }

    private static List<String> paths(SpringBootLoweredModel model, String name) {
        return model.declarations().stream().filter(CapabilityDeclaration.class::isInstance).map(CapabilityDeclaration.class::cast)
                .filter(capability -> capability.javaName().equals(name)).findFirst().orElseThrow()
                .requiredInputs().stream().map(RequiredInput::fieldPath).toList();
    }
}
