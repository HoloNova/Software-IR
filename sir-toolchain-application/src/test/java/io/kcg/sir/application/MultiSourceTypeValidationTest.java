package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import io.kcg.sir.application.api.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiSourceTypeValidationTest {
    @TempDir Path temp;

    @Test void importedEntityDateCannotBeComparedToInt64() throws Exception {
        Path source = fixture(temp), root = source.resolve("project.sir");
        String text = Files.readString(root).replace("sources {", "sources { source \"modules/clock.sir\";")
                .replace("imports {", "imports { import Clock from \"modules/clock.sir\";")
                .replace("declarations {", """
                        declarations {
                          capability CheckClock {
                            input GetCourseInput; output Unit; fails CourseNotFound; requires readonly; expose query;
                            workflow { load Clock by input.id as clock else CourseNotFound;
                              validate clock.happened == input.id else CourseNotFound;
                              return unit;
                            }
                          }
                        """);
        Files.writeString(root, text);
        Files.writeString(source.resolve("modules/clock.sir"), """
                sir 0.2 imports {} declarations {
                  entity Clock persistent { identity id: Int64 generated auto; field happened: Date; }
                }
                """);
        var diagnostic = rejected(source, "SIR-TYPE-001");
        assertEquals("比较操作数类型不一致", diagnostic.message());
        var span = diagnostic.sourceSpan().orElseThrow();
        assertEquals("clock.happened == input.id", text.substring(span.start().codePointOffset(), span.end().codePointOffset()));
    }

    @Test void toOneRelationCannotBeReinterpretedAsACollectionAcrossFiles() throws Exception {
        Path source = fixture(temp), root = source.resolve("project.sir");
        Files.writeString(root, Files.readString(root).replace("field student: StudentSummary;", "field student: List<StudentSummary>;"));
        var diagnostic = rejected(source, "SIR-SYMBOL-002");
        assertEquals("找不到关联: Student 没有指向 Enrollment 的 Ref 字段", diagnostic.message());
    }

    @Test void validCrossFileBindingsStillPassThroughRelationDepthValidation() throws Exception {
        Path source = fixture(temp), root = source.resolve("project.sir");
        Files.writeString(root, Files.readString(root).replace("sources {", "sources { source \"modules/grade.sir\";")
                .replace("imports {", "imports { import Grade from \"modules/grade.sir\";")
                .replace("view StudentSummary from Student {", """
                        view GradeSummary from Grade { field id: Int64; field score: Int32; }
                        view StudentSummary from Student { field grades: List<GradeSummary>;
                        """));
        Files.writeString(source.resolve("modules/grade.sir"), """
                sir 0.2 imports { import Student from "modules/student.sir"; } declarations {
                  entity Grade persistent { identity id: Int64 generated auto; field student: Ref<Student>; field score: Int32; }
                }
                """);
        rejected(source, "SIR-VALID-005");
    }

    private ExecutionDiagnostic rejected(Path source, String code) throws Exception {
        var before = tree(temp);
        var result = assertInstanceOf(ProjectToolchainResult.Failure.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(source, ENTRY, temp.resolve("output"))));
        assertEquals(ExecutionStage.SEMANTIC, result.failedStage()); assertEquals(FailureDisposition.NO_CHANGES, result.disposition());
        assertEquals(List.of(code), result.diagnostics().stream().filter(ExecutionDiagnostic::isError).map(ExecutionDiagnostic::code).toList());
        assertEquals(ENTRY, result.diagnostics().getFirst().sourceSpan().orElseThrow().source());
        assertEquals(before, tree(temp));
        return result.diagnostics().getFirst();
    }
}
