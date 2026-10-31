package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import org.junit.jupiter.api.Test;

/** An optional operand of containsLiteral is recorded in the plan, so absence is rendered as false, not UNKNOWN. */
class OptionalStringMatchLoweringTest {
    private static final String SOURCE = """
            sir 0.3
            software Search {
              metadata { displayName "Search"; namespace "example.search"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus;
                database mysql; build maven; interface rest; }
              sources { } imports { }
              declarations {
                entity Note persistent {
                  identity id: Id generated auto; field title: Text; field note: Optional<Text>;
                }
                view NoteView from Note { field id: Id; field title: Text; }
                input Query { field keyword: Optional<Text>; field word: Text; field page: PageNo; field size: PageSize; }
                error BadPage;
                capability OptionalKeyword {
                  input Query; output Page<NoteView>; fails BadPage; requires readonly; expose query;
                  workflow {
                    find Note where input.keyword is none or item.title containsLiteral input.keyword
                      order by id ascending Page input.page, input.size else BadPage as found;
                    return found;
                  }
                }
                capability OptionalColumn {
                  input Query; output Page<NoteView>; fails BadPage; requires readonly; expose query;
                  workflow {
                    find Note where not (item.note containsLiteral input.word)
                      order by id ascending Page input.page, input.size else BadPage as found;
                    return found;
                  }
                }
                capability RequiredBoth {
                  input Query; output Page<NoteView>; fails BadPage; requires readonly; expose query;
                  workflow {
                    find Note where item.title containsLiteral input.word
                      order by id ascending Page input.page, input.size else BadPage as found;
                    return found;
                  }
                }
              }
            }
            """;

    @Test void eitherOptionalOperandAsksForAbsentAsFalse() {
        SpringBootLoweredModel model = LoweringTestSupport.lowerSourceSuccess(SOURCE);
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
        assertTrue(match(model, "OptionalKeyword").absentIsFalse());
        assertTrue(match(model, "OptionalColumn").absentIsFalse());
        assertFalse(match(model, "RequiredBoth").absentIsFalse());
    }

    private static SpringExpression.StringMatch match(SpringBootLoweredModel model, String capability) {
        return model.declarations().stream().filter(CapabilityDeclaration.class::isInstance).map(CapabilityDeclaration.class::cast)
                .filter(declaration -> declaration.javaName().equals(capability)).findFirst().orElseThrow()
                .workflow().steps().stream().filter(SpringBootWorkflow.FindStep.class::isInstance)
                .map(SpringBootWorkflow.FindStep.class::cast).findFirst().orElseThrow().stringMatches().getFirst();
    }
}
