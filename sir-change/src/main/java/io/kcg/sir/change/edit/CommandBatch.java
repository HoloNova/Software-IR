package io.kcg.sir.change.edit;

import java.util.List;
import java.util.Objects;

/**
 * An ordered list of commands that succeed or fail together.
 *
 * <p>The batch is bound to the software it was written against: the protocol version, the software
 * name and the SHA-256 of the exact source text. A source that changed since is stale and the batch
 * is refused, so a command can never land on a different revision than the one its author saw.
 */
public record CommandBatch(String protocol, String softwareName, String baselineSha256, List<Command> commands) {
   public static final String PROTOCOL = "sir-command/1";

   public CommandBatch {
      Objects.requireNonNull(protocol, "protocol");
      Objects.requireNonNull(softwareName, "softwareName");
      Objects.requireNonNull(baselineSha256, "baselineSha256");
      commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
      if (commands.isEmpty()) {
         throw new IllegalArgumentException("a batch needs at least one command");
      }
   }
}
