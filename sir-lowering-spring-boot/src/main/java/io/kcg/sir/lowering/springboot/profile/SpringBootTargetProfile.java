package io.kcg.sir.lowering.springboot.profile;

import java.util.Objects;

public record SpringBootTargetProfile(
   String id, int javaVersion, String springBootVersion, String mybatisPlusVersion, String persistence, String database, String buildTool, String interfaceKind
) {
   public static final String TARGET_ID_V0_1 = "spring-boot-java21-mybatis-plus-mysql-rest-v0.1";
   public static final String TARGET_ID_V0_2 = "spring-boot-java21-mybatis-plus-mysql-rest-v0.2";
   public static final String TARGET_ID = "spring-boot-java21-mybatis-plus-mysql-rest-v0.1";
   public static final SpringBootTargetProfile V0_1 = new SpringBootTargetProfile(
      "spring-boot-java21-mybatis-plus-mysql-rest-v0.1", 21, "3.5.3", "3.5.12", "mybatis-plus", "mysql", "maven", "rest"
   );
   public static final SpringBootTargetProfile V0_2 = new SpringBootTargetProfile(
      "spring-boot-java21-mybatis-plus-mysql-rest-v0.2", 21, "3.5.3", "3.5.12", "mybatis-plus", "mysql", "maven", "rest"
   );

   public SpringBootTargetProfile {
      requireText(id, "id");
      requireText(springBootVersion, "springBootVersion");
      requireText(mybatisPlusVersion, "mybatisPlusVersion");
      requireText(persistence, "persistence");
      requireText(database, "database");
      requireText(buildTool, "buildTool");
      requireText(interfaceKind, "interfaceKind");
      if (javaVersion < 1) {
         throw new IllegalArgumentException("javaVersion must be positive");
      }
   }

   private static void requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }
}
