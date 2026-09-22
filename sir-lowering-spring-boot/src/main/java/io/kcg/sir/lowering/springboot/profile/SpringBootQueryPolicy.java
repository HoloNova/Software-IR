package io.kcg.sir.lowering.springboot.profile;

import java.util.List;
import java.util.Objects;

/**
 * Target decisions owned by the Spring Boot lowering profile for query capabilities.
 *
 * <p>The bounds are fixed by the target profile rather than declared in SIR: the page number, the
 * page size, and the literals a literal match must escape are all part of the target's query
 * contract, and a generated application that allowed unbounded offsets would let a client ask the
 * database to skip an arbitrarily large number of rows.
 */
public record SpringBootQueryPolicy(
   int pageDefaultSize,
   int pageMaxSize,
   int pageMaxNumber,
   char likeEscapeCharacter,
   List<String> likeEscapedLiterals
) {
   public static final SpringBootQueryPolicy V0_1 = new SpringBootQueryPolicy(20, 100, 10_000, '\\', List.of("\\", "%", "_"));

   public SpringBootQueryPolicy {
      if (pageDefaultSize < 1) {
         throw new IllegalArgumentException("pageDefaultSize must be positive");
      }

      if (pageMaxSize < pageDefaultSize) {
         throw new IllegalArgumentException("pageMaxSize must not be smaller than pageDefaultSize");
      }

      if (pageMaxNumber < 1) {
         throw new IllegalArgumentException("pageMaxNumber must be positive");
      }

      if (likeEscapeCharacter == 0) {
         throw new IllegalArgumentException("likeEscapeCharacter must not be NUL");
      }

      likeEscapedLiterals = List.copyOf(Objects.requireNonNull(likeEscapedLiterals, "likeEscapedLiterals"));
      if (likeEscapedLiterals.isEmpty()) {
         throw new IllegalArgumentException("likeEscapedLiterals must not be empty");
      }

      if (!likeEscapedLiterals.contains(String.valueOf(likeEscapeCharacter))) {
         throw new IllegalArgumentException("likeEscapedLiterals must contain the escape character itself");
      }
   }
}
