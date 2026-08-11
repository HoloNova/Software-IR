package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorServiceWorkflowContractTest {

    private static final String CAMPUS_MARKET = "valid/campus-market.sir";
    private static final String COMPOUND_FIND = "valid/compound-find.sir";
    private static final String SERVICE_WORKFLOW = "valid/service-workflow-contract.sir";
    private static final String UNIT_OUTPUT = "valid/unit-output.sir";

    @Test
    void atomicServiceUsesLoweredActorInputMapperAndCreateWorkflowInOrder() {
        String service = service(CAMPUS_MARKET, "PublishGoodsService.java");

        assertTrue(service.contains("package com.example.campusmarket.application;"), service);
        assertTrue(service.contains("@Service\n@Transactional\npublic class PublishGoodsService"), service);
        assertEquals(1, occurrences(service, "private final GoodsMapper goodsMapper;"), service);
        assertTrue(service.contains("public PublishGoodsService(GoodsMapper goodsMapper)"), service);
        assertTrue(service.contains("this.goodsMapper = goodsMapper;"), service);
        assertTrue(service.contains(
                "public Goods publishGoods(Long actorId, PublishGoodsInput input)"), service);

        assertOrder(service,
                "if (!((input.getPrice()).compareTo(new java.math.BigDecimal(\"0.01\")) >= 0))",
                "throw new InvalidGoodsPriceException();",
                "var goods = new Goods();",
                "goods.setTitle(input.getTitle());",
                "goods.setPrice(input.getPrice());",
                "goods.setSellerId(actorId);",
                "goods.setStatus(GoodsStatus.AVAILABLE);",
                "goodsMapper.insert(goods);",
                "return goods;");
        assertFalse(service.contains("goodsMapper.updateById(goods);"), service);
    }

    @Test
    void loadUpdatePersistAndFindUseOneMapperAndPreserveStepOrder() {
        String service = service(SERVICE_WORKFLOW, "ExerciseStepsService.java");

        assertTrue(service.contains("@Transactional\npublic class ExerciseStepsService"), service);
        assertEquals(1, occurrences(service, "private final UserMapper userMapper;"), service);
        assertTrue(service.contains("public ExerciseStepsService(UserMapper userMapper)"), service);
        assertTrue(service.contains(
                "public java.util.List<User> exerciseSteps(LookupInput input)"), service);
        assertTrue(service.contains("import java.util.List;"), service);

        assertOrder(service,
                "var user = userMapper.selectById(input.getId());",
                "if (user == null)",
                "throw new NotFoundException();",
                "user.setState(State.ACTIVE);",
                "userMapper.updateById(user);",
                "var wrapper = new LambdaQueryWrapper<User>()",
                "User::getState",
                "var users = userMapper.selectList(wrapper);",
                "return users;");
        assertFalse(service.contains("userMapper.insert(user);"), service);
    }

    @Test
    void readonlyFindKeepsAndOrNotGroupingAndListResponse() {
        String service = service(COMPOUND_FIND, "FilterUsersService.java");

        assertTrue(service.contains("@Transactional(readOnly = true)"), service);
        assertTrue(service.contains(
                "public java.util.List<User> filterUsers(FilterInput input)"), service);
        assertTrue(service.contains(
                "q3.and(q5 -> q5.eq(User::getState, State.ACTIVE))"
                        + ".or(q6 -> q6.eq(User::getState, State.INACTIVE))"), service);
        assertTrue(service.contains(
                ".and(q4 -> q4.eq(User::getEnabled, input.getEnabled()))"), service);
        assertTrue(service.contains(
                "q2.not(q7 -> q7.and(q8 -> q8.eq(User::getState, State.ACTIVE))"
                        + ".or(q9 -> q9.eq(User::getEnabled, false)))"), service);
        assertTrue(service.contains("return users;"), service);
        assertFalse(service.contains(".or()"), service);
    }

    @Test
    void unitOptionalAndValueResponsesUseTheirLoweredServiceTypesAndReturns() {
        String unit = service(UNIT_OUTPUT, "PingService.java");
        String optional = service(SERVICE_WORKFLOW, "ReadNicknameService.java");
        String value = service(SERVICE_WORKFLOW, "EchoActiveService.java");

        assertTrue(unit.contains("public void ping()"), unit);
        assertTrue(unit.contains("        return;"), unit);
        assertFalse(unit.contains("return null;"), unit);

        assertTrue(optional.contains("import java.util.Optional;"), optional);
        assertTrue(optional.contains(
                "public java.util.Optional<String> readNickname(LookupInput input)"), optional);
        assertTrue(optional.contains("return input.getNickname();"), optional);

        assertTrue(value.contains("public Boolean echoActive(LookupInput input)"), value);
        assertTrue(value.contains("return input.getActive();"), value);
    }

    private static String service(String resource, String fileName) {
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

    private static void assertOrder(String text, String... fragments) {
        int cursor = -1;
        for (String fragment : fragments) {
            int next = text.indexOf(fragment, cursor + 1);
            assertTrue(next > cursor, () -> "missing or out of order fragment: " + fragment + "\n" + text);
            cursor = next;
        }
    }
}
