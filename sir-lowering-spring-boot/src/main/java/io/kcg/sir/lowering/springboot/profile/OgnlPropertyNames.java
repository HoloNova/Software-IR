package io.kcg.sir.lowering.springboot.profile;

import java.util.Set;

/** Target-only Java names for properties that MyBatis-Plus embeds in OGNL conditions. */
public final class OgnlPropertyNames {
    // Word tokens in MyBatis 3.5.19's org.apache.ibatis.ognl.OgnlParserConstants.
    private static final Set<String> RESERVED = Set.of(
            "or", "and", "bor", "xor", "band", "eq", "neq", "lt", "gt", "lte", "gte",
            "in", "not", "shl", "shr", "ushr", "instanceof", "new", "true", "false", "null");

    private OgnlPropertyNames() {}

    /**
     * A dollar inside the name is legal in Java/OGNL but impossible in a SIR identifier, so it cannot
     * collide with an author's property. MyBatis rejects leading-dollar properties, hence the letter
     * prefix. Only persistence properties use this name: wire and physical names stay put.
     */
    public static String persistent(String property) {
        return RESERVED.contains(property) ? "kcg$" + property : property;
    }
}
