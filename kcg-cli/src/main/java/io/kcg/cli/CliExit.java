package io.kcg.cli;

public final class CliExit {
   public static final int OK = 0;
   public static final int USAGE = 2;
   public static final int FAILURE = 3;
   public static final int RECOVERY_REQUIRED = 4;
   public static final int INTERNAL = 70;

   private CliExit() {
   }
}
