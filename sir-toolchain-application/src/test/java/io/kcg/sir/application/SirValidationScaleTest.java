package io.kcg.sir.application;

import io.kcg.sir.application.api.*;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** One measured JVM, streaming input and counters only, no sample cache or retained result list. */
class SirValidationScaleTest {
    @Test void oneThousandFullGenerationSamples() throws Exception {
        String source = SirValidationApplicationTest.source("course-admin-enrollment.sir");
        var pools = ManagementFactory.getMemoryPoolMXBeans(); pools.forEach(p -> p.resetPeakUsage());
        var counts = new int[1]; var digest = new String[1];
        InputStream input = new InputStream() {
            int row, offset; byte[] current = new byte[0];
            @Override public int read() {
                if (offset == current.length) {
                    if (row == 1000) return -1;
                    current = (SirValidationBatchTest.sample("scale-" + row++, source) + "\n").getBytes(StandardCharsets.UTF_8); offset = 0;
                }
                return current[offset++] & 255;
            }
            @Override public int read(byte[] b, int off, int len) {
                if (len == 0) return 0;
                int first = read(); if (first < 0) return -1; b[off] = (byte)first;
                int n = Math.min(len - 1, current.length - offset); System.arraycopy(current, offset, b, off + 1, n); offset += n; return n + 1;
            }
        };
        long begin = System.nanoTime();
        var summary = new SirValidationApplication().checkBatch(new SirValidationBatchRequest(input, result -> {
            assertEquals("scale-" + counts[0], result.id()); assertTrue(result.ok(), result.toString());
            assertEquals(ValidationStopAfter.GENERATION, result.stage()); assertEquals(35, result.fileCount());
            if (digest[0] == null) digest[0] = result.digest(); else assertEquals(digest[0], result.digest());
            counts[0]++;
        }));
        long elapsed = System.nanoTime() - begin;
        assertEquals(1000, counts[0]); assertEquals(1000, summary.lineCount()); assertFalse(summary.internalFailure());
        System.out.println("Q23_SCALE count=1000 ok=1000 stage=GENERATION files=35 elapsedNanos=" + elapsed + " digest=" + digest[0]);
        for (var pool : pools) System.out.println("Q23_MEMORY pool=" + pool.getName() + " peakUsed=" + pool.getPeakUsage().getUsed());
        Path status = Path.of("/proc/self/status");
        if (Files.isRegularFile(status)) {
            for (String line : Files.readAllLines(status)) if (line.startsWith("VmHWM:") || line.startsWith("VmRSS:")) System.out.println("Q23_RSS " + line);
        } else System.out.println("Q23_RSS NOT_RUN: process RSS counters unavailable");
        System.out.println("Q23_JVM " + System.getProperty("java.version") + " pid=" + ProcessHandle.current().pid());
    }
}
