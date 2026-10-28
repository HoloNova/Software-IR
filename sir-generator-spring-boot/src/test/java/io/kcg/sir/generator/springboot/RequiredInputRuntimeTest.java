package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every non-Optional input field must carry a value before the workflow runs, whatever the workflow does with it.
 * The policy is capability-local, not a NotNull on the shared DTO: a capability that pages keeps its page/size defaults.
 */
class RequiredInputRuntimeTest {
    private static URLClassLoader loader;
    private static final String SOURCE = """
            sir 0.3
            software NumericInput {
              metadata { displayName "Numeric Input"; namespace "example.numeric"; }
              target { language java 21; framework spring_boot; persistence mybatis_plus;
                database mysql; build maven; interface rest; }
              sources { } imports { }
              declarations {
                entity Entry persistent { identity id: Id generated auto; field count: Count; }
                view EntryView from Entry { field id: Id; field count: Count; }
                input Numbers {
                  field left: Count where min(0); field right: Count; field expected: Count;
                  field page: PageNo; field size: PageSize; field flag: Flag;
                }
                input Reference { field entry: Ref<Entry>; }
                enum Color { RED, BLUE }
                input Kinds {
                  field happenedAt: Moment; field active: Flag; field label: Text; field entry: Ref<Entry>; field color: Color;
                  field note: Optional<Text>;
                }
                capability Kinded {
                  input Kinds; output Unit; fails Refused; requires readonly; expose query;
                  workflow { validate input.active else Refused; return unit; }
                }
                error Refused http 409;
                capability ReferenceMath {
                  input Reference; output Unit; fails Refused; requires readonly; expose query;
                  workflow { validate input.entry.id * 2 > 0 else Refused; return unit; }
                }
                capability Add {
                  input Numbers; output Unit; fails Refused; requires atomic; expose command at "/add/{left}";
                  workflow { validate input.left + input.right == input.expected else Refused; return unit; }
                }
                capability Negate {
                  input Numbers; output Unit; fails Refused; requires readonly; expose query;
                  workflow { validate -input.left == input.expected else Refused; return unit; }
                }
                capability ShortCircuit {
                  input Numbers; output Unit; fails Refused; requires readonly; expose query;
                  workflow { validate input.flag or input.left * input.right == 0 else Refused; return unit; }
                }
                capability PageArithmetic {
                  input Numbers; output Unit; fails Refused; requires readonly; expose query;
                  workflow { validate input.page + 1 > 1 else Refused; return unit; }
                }
                capability Paged {
                  input Numbers; output Page<EntryView>; fails Refused; requires readonly; expose query;
                  workflow {
                    find Entry where item.count >= 0 order by id ascending Page input.page, input.size else Refused as entries;
                    return entries;
                  }
                }
              }
            }
            """;

    @BeforeAll static void compile(@TempDir Path directory) throws Exception {
        loader = GeneratedRuntimeSupport.compile(SOURCE, directory);
    }
    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void everyMissingNonOptionalFieldIsAStructuredFieldErrorInDeclaredOrder() throws Exception {
        Object input = numbers();
        input.getClass().getMethod("setExpected", Integer.class).invoke(input, 3);
        Throwable failure = failure("Add", input);
        assertEquals("ApiException", failure.getClass().getSimpleName());
        assertEquals("INVALID_REQUEST", failure.getClass().getMethod("code").invoke(failure));
        // Add does not page, so page and size are ordinary required fields here; expected is only compared.
        assertEquals(List.of("left", "right", "page", "size", "flag"), paths(failure));
    }

    @Test void fieldsThatAreOnlyComparedStoredOrUnreadAreRequiredForEveryTypeButOptional() throws Exception {
        Object input = loader.loadClass("example.numeric.api.Kinds").getConstructor().newInstance();
        Throwable failure = failure("Kinded", input);
        assertEquals("INVALID_REQUEST", failure.getClass().getMethod("code").invoke(failure));
        assertEquals(List.of("happenedAt", "active", "label", "entryId", "color"), paths(failure));
        var fields = (List<?>) failure.getClass().getMethod("fields").invoke(failure);
        for (Object field : fields) assertEquals("NotNull", field.getClass().getMethod("code").invoke(field));
    }

    @Test void referenceIdentityReportsTheActualDtoFieldAndAcceptsItsNumericValue() throws Exception {
        Object input = loader.loadClass("example.numeric.api.Reference").getConstructor().newInstance();
        Throwable missing = failure("ReferenceMath", input);
        assertEquals("INVALID_REQUEST", missing.getClass().getMethod("code").invoke(missing));
        var fields = (List<?>) missing.getClass().getMethod("fields").invoke(missing);
        assertEquals(1, fields.size());
        assertEquals("entryId", fields.getFirst().getClass().getMethod("path").invoke(fields.getFirst()));
        input.getClass().getMethod("setEntryId", Long.class).invoke(input, 5L);
        Object service = loader.loadClass("example.numeric.application.ReferenceMathService").getConstructor().newInstance();
        assertDoesNotThrow(() -> service.getClass().getMethod("referenceMath", input.getClass()).invoke(service, input));
    }

    @Test void numericNegationAlsoRejectsNullBeforeItIsEvaluated() throws Exception {
        assertEquals("ApiException", failure("Negate", numbers()).getClass().getSimpleName());
    }

    @Test void aShortCircuitedBranchDoesNotMakeRequiredNumbersOptional() throws Exception {
        Object input = numbers();
        input.getClass().getMethod("setFlag", Boolean.class).invoke(input, true);
        assertEquals("ApiException", failure("ShortCircuit", input).getClass().getSimpleName());
        assertEquals("ApiException", failure("PageArithmetic", numbers()).getClass().getSimpleName());
    }

    @Test void pathInjectionRunsBeforeTheArithmeticPreflight() throws Exception {
        Object input = numbers();
        input.getClass().getMethod("setRight", Integer.class).invoke(input, 2);
        input.getClass().getMethod("setExpected", Integer.class).invoke(input, 12);
        input.getClass().getMethod("setPage", Integer.class).invoke(input, 1);
        input.getClass().getMethod("setSize", Integer.class).invoke(input, 1);
        input.getClass().getMethod("setFlag", Boolean.class).invoke(input, false);
        var serviceType = loader.loadClass("example.numeric.application.AddService");
        try (var factory = jakarta.validation.Validation.byDefaultProvider().configure()
                .messageInterpolator(new org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator()).buildValidatorFactory()) {
        var controller = loader.loadClass("example.numeric.api.AddController").getConstructor(serviceType, jakarta.validation.Validator.class)
                .newInstance(serviceType.getConstructor().newInstance(), factory.getValidator());
        var endpoint = java.util.Arrays.stream(controller.getClass().getDeclaredMethods())
                .filter(method -> method.getName().equals("add")).findFirst().orElseThrow();
        Object[] arguments = java.util.Arrays.stream(endpoint.getParameterTypes())
                .map(type -> type.equals(input.getClass()) ? input : Integer.valueOf(10)).toArray();
        assertDoesNotThrow(() -> endpoint.invoke(controller, arguments));
        assertEquals(10, input.getClass().getMethod("getLeft").invoke(input));
        }
    }

    @Test void aCapabilityThatPagesKeepsPageAndSizeDefaultsOnTheSameDto() throws Exception {
        Object input = numbers();
        try (var factory = jakarta.validation.Validation.byDefaultProvider().configure()
                .messageInterpolator(new org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator()).buildValidatorFactory()) {
            assertTrue(factory.getValidator().validate(input).isEmpty());
        }
        var mapper = loader.loadClass("example.numeric.persistence.EntryMapper");
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{mapper}, (object, method, arguments) -> switch (method.getName()) {
            case "selectCount" -> 0L;
            case "selectList" -> List.of();
            default -> throw new AssertionError(method);
        });
        var service = loader.loadClass("example.numeric.application.PagedService").getConstructor(mapper).newInstance(proxy);
        var paged = service.getClass().getMethod("paged", input.getClass());
        // The fields Paged does not page with are still required; only page and size may be left out.
        Throwable missing = assertThrows(InvocationTargetException.class, () -> paged.invoke(service, input)).getCause();
        assertEquals(List.of("left", "right", "expected", "flag"), paths(missing));
        for (String setter : List.of("setLeft", "setRight", "setExpected")) input.getClass().getMethod(setter, Integer.class).invoke(input, 0);
        input.getClass().getMethod("setFlag", Boolean.class).invoke(input, false);
        Object page = assertDoesNotThrow(() -> service.getClass().getMethod("paged", input.getClass()).invoke(service, input));
        assertEquals(1, page.getClass().getMethod("getPage").invoke(page));
        assertTrue((Integer) page.getClass().getMethod("getSize").invoke(page) > 0);
    }

    private static List<String> paths(Throwable failure) throws Exception {
        var fields = (List<?>) failure.getClass().getMethod("fields").invoke(failure);
        return fields.stream().map(field -> {
            try { return (String) field.getClass().getMethod("path").invoke(field); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }).toList();
    }

    private static Object numbers() throws Exception { return loader.loadClass("example.numeric.api.Numbers").getConstructor().newInstance(); }
    private static Throwable failure(String capability, Object input) throws Exception {
        var service = loader.loadClass("example.numeric.application." + capability + "Service").getConstructor().newInstance();
        return assertThrows(InvocationTargetException.class,
                () -> service.getClass().getMethod(Character.toLowerCase(capability.charAt(0)) + capability.substring(1), input.getClass()).invoke(service, input)).getCause();
    }
}
