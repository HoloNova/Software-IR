package io.kcg.sir.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Narrow direct-reference boundary, paired with real-process directory fingerprints in CLI tests. */
class ValidationArchitectureTest {
    static final List<String> FORBIDDEN=List.of("java/nio/file/", "java/io/FileInputStream", "java/io/FileOutputStream", "java/io/RandomAccessFile", "java/nio/channels/FileChannel", "StateRootLock", "BundleStore", "BundleReader", "JournalGate", "ProjectGraphBuilder", "FileTransaction");
    static String constants(String name) throws Exception {
        try(var in=ValidationArchitectureTest.class.getResourceAsStream("/"+name.replace('.','/')+".class")) {
            assertNotNull(in,"compiled class missing: "+name);return new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);
        }
    }
    static boolean violation(String binary) { return FORBIDDEN.stream().anyMatch(binary::contains); }
    @Test void checkerAndTransportHaveNoFilesystemStateOrGraphReferences() throws Exception {
        for(String name:List.of("api.SirValidationApplication","api.SirValidationBatchRequest","api.SirValidationBatchRequest$ResultSink",
            "internal.CompilationStages","internal.CompilationStages$Snapshot","internal.ValidationHashes","internal.ValidationJsonLines",
            "internal.ValidationJsonLines$Line","internal.ValidationJsonLines$Frame","internal.ValidationJsonLines$Text","internal.ValidationJsonLines$BadJson"))
            assertFalse(violation(constants("io.kcg.sir.application."+name)),name);
    }
    @Test void boundaryIsSensitiveToAnActualTestOnlyWriter() throws Exception {
        assertTrue(violation(constants(Probe.class.getName())));
    }
    static final class Probe {
        static void write(Path p) throws Exception { Files.writeString(p,"probe"); }
    }
}
