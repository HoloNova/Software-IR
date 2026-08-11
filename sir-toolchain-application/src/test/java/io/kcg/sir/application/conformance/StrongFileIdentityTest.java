package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class StrongFileIdentityTest {

    @Test
    void timedOutFsutilIsForciblyTerminatedAndFailsClosed() {
        TimedOutProcess process = new TimedOutProcess();

        IOException failure = assertThrows(IOException.class,
                () -> StrongFileIdentity.awaitFsutilFileId(process, 1, 1));

        assertEquals("STRONG_IDENTITY_UNAVAILABLE: fsutil timed out", failure.getMessage());
        assertTrue(process.destroyCalled, "timeout must first request graceful termination");
        assertTrue(process.destroyForciblyCalled,
                "a process that survives graceful termination must be forcibly terminated");
        assertTrue(process.waitCalls >= 3,
                "the implementation must prove process exit after forced termination");
    }

    private static final class TimedOutProcess extends Process {
        private int waitCalls;
        private boolean destroyCalled;
        private boolean destroyForciblyCalled;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            waitCalls++;
            return waitCalls >= 3;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyCalled = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalled = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return waitCalls < 3;
        }
    }
}