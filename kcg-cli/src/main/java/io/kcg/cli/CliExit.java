package io.kcg.cli;

/** The process exit codes of the command line, in one place. */
public final class CliExit {
   public static final int OK = 0;
   public static final int USAGE = 2;
   public static final int FAILURE = 3;
   /** An apply stopped half way; running the same command again finishes it. */
   public static final int RERUN_REQUIRED = 4;
   public static final int INTERNAL = 70;

   private CliExit() {
   }
}
