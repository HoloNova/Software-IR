package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.source.SirDeclarationWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ComputedViewSemanticsTest {
    static String source(String fields) {
        return TestSources.sir("""
                entity Course persistent {
                  identity id: Id generated auto;
                  field capacity: Quantity; field enrolled: Count;
                  field price: Money; field note: Optional<Text>; field password: Text secret;
                }
                view CourseView from Course {
                  field id: Id;
                  %s
                }
                """.formatted(fields));
    }

    @Test void currentRowComputationsAndPrecedenceSurviveSourceWriting() {
        String text = source("""
                field remaining: Count = item.capacity - item.enrolled;
                field full: Flag = item.enrolled >= item.capacity;
                field total: Money = item.price * (item.capacity - item.enrolled);
                field note: Optional<Text> = item.note;
                field missing: Flag = item.note is none;
                field capacity: Count = item.capacity * (item.enrolled * 2);
                """);
        var result = TestSources.analyze(text);
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        var declarations = SoftwareDraft.of(result.model().orElseThrow()).declarations();
        var writer = new SirDeclarationWriter("", SirDeclarationWriter.typesOf(declarations));
        String written = declarations.stream().map(writer::write).reduce("", (a,b) -> a + "\n" + b);
        assertTrue(written.contains("remaining: Count = item.capacity - item.enrolled"), written);
        assertTrue(written.contains("item.capacity * (item.enrolled * 2)"), written);
        var roundTrip = TestSources.analyze(TestSources.sir(written));
        assertTrue(roundTrip.isSuccess(), () -> roundTrip.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "field wrong: Text = item.capacity - item.enrolled;",
            "field wrong: Count = item.note + 1;",
            "field wrong: Count = item.unknown;",
            "field wrong: Count = input.capacity;",
            "field wrong: Count = actor.capacity;",
            "field wrong: Count = remaining;",
            "field remaining: Count = 1; field wrong: Count = item.remaining;",
            "field wrong: Text = item.password;",
            "field wrong: Flag = item.password == \"hidden\";",
            "field wrong: Moment = now();",
            "field wrong: Count where min(0) = item.capacity;",
            "field wrong: Count versioned = item.capacity;",
            "field wrong: Ref<Course> = item;",
            "field wrong: CourseView = item;",
            "field remaining: Count = 1; field remaining: Count = 2;"
    })
    void rejectsInvalidOrNonLocalResponseComputations(String fields) {
        var result = TestSources.analyze(source(fields));
        assertFalse(result.isSuccess(), fields);
    }

    @Test void inputAndEntityFieldsDoNotGainComputedInitializers() {
        for (String declaration : new String[]{"input Data { field n: Count = 1; }",
                "entity Data persistent { identity id: Id generated auto; field n: Count = 1; }"}) {
            var parsed = new io.kcg.sir.internal.DefaultSirParser().parseProject(new io.kcg.sir.api.SirSource(
                    io.kcg.sir.source.SourceId.of("invalid"), TestSources.sir(declaration)));
            assertFalse(parsed.isSuccess(), declaration);
        }
    }
}
