package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorControllerTransportContractTest {

    private static final String CAMPUS_MARKET = "valid/campus-market.sir";
    private static final String COMPOUND_FIND = "valid/compound-find.sir";
    private static final String SERVICE_WORKFLOW = "valid/service-workflow-contract.sir";
    private static final String UNIT_OUTPUT = "valid/unit-output.sir";

    @Test
    void postControllerBindsActorIdentityBeforeValidatedRequestBodyAndReturnsEntity() {
        String controller = controller(CAMPUS_MARKET, "PublishGoodsController.java");

        assertTrue(controller.contains("package com.example.campusmarket.api;"), controller);
        assertTrue(controller.contains("@RestController\n@RequestMapping(\"/api/publish-goods\")"), controller);
        assertTrue(controller.contains("public class PublishGoodsController"), controller);
        assertEquals(1, occurrences(controller, "private final PublishGoodsService service;"), controller);
        assertTrue(controller.contains(
                "public PublishGoodsController(PublishGoodsService service)"), controller);
        assertTrue(controller.contains("this.service = service;"), controller);
        assertTrue(controller.contains("@PostMapping"), controller);
        assertFalse(controller.contains("@GetMapping"), controller);

        assertTrue(controller.contains(
                "public Goods publishGoods(@RequestAttribute(\"actorId\") Long actorId, "
                        + "@Valid @RequestBody PublishGoodsInput input)"), controller);
        assertTrue(controller.contains("return service.publishGoods(actorId, input);"), controller);
        assertTrue(controller.contains("import jakarta.validation.Valid;"), controller);
        assertTrue(controller.contains("import org.springframework.web.bind.annotation.RequestAttribute;"), controller);
        assertTrue(controller.contains("import org.springframework.web.bind.annotation.RequestBody;"), controller);
        assertFalse(controller.contains("@ModelAttribute"), controller);
        assertFalse(controller.contains("actorId.get"), controller);
    }

    @Test
    void getControllerUsesUnvalidatedModelAttributeAndReturnsList() {
        String controller = controller(COMPOUND_FIND, "FilterUsersController.java");

        assertTrue(controller.contains("@RequestMapping(\"/api/filter-users\")"), controller);
        assertTrue(controller.contains("@GetMapping"), controller);
        assertFalse(controller.contains("@PostMapping"), controller);
        assertTrue(controller.contains(
                "public java.util.List<User> filterUsers(@ModelAttribute FilterInput input)"), controller);
        assertTrue(controller.contains("return service.filterUsers(input);"), controller);
        assertTrue(controller.contains("import java.util.List;"), controller);
        assertTrue(controller.contains("import org.springframework.web.bind.annotation.ModelAttribute;"), controller);
        assertFalse(controller.contains("@Valid"), controller);
        assertFalse(controller.contains("@RequestBody"), controller);
        assertFalse(controller.contains("@RequestAttribute"), controller);
    }

    @Test
    void unitControllerHasNoParametersAndDelegatesWithoutReturn() {
        String controller = controller(UNIT_OUTPUT, "PingController.java");

        assertTrue(controller.contains("@RequestMapping(\"/api/ping\")"), controller);
        assertTrue(controller.contains("@PostMapping"), controller);
        assertTrue(controller.contains("public void ping()"), controller);
        assertTrue(controller.contains("        service.ping();"), controller);
        assertFalse(controller.contains("return service.ping();"), controller);
        assertFalse(controller.contains("@RequestBody"), controller);
        assertFalse(controller.contains("@ModelAttribute"), controller);
        assertFalse(controller.contains("@RequestAttribute"), controller);
    }

    @Test
    void optionalAndValueControllersReturnServiceResultsWithLoweredTypes() {
        String optional = controller(SERVICE_WORKFLOW, "ReadNicknameController.java");
        String value = controller(SERVICE_WORKFLOW, "EchoActiveController.java");

        assertTrue(optional.contains("@GetMapping"), optional);
        assertTrue(optional.contains(
                "public java.util.Optional<String> readNickname(@ModelAttribute LookupInput input)"), optional);
        assertTrue(optional.contains("return service.readNickname(input);"), optional);
        assertTrue(optional.contains("import java.util.Optional;"), optional);
        assertFalse(optional.contains("@Valid"), optional);

        assertTrue(value.contains("@GetMapping"), value);
        assertTrue(value.contains(
                "public Boolean echoActive(@ModelAttribute LookupInput input)"), value);
        assertTrue(value.contains("return service.echoActive(input);"), value);
        assertFalse(value.contains("return service.readNickname"), value);
    }

    private static String controller(String resource, String fileName) {
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess(resource);
        return GeneratorTestSupport.contentEndingWith(files, fileName);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
