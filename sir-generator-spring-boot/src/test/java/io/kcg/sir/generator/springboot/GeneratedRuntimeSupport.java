package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

/** Compile the actual generated Java against the target dependencies without starting an application. */
final class GeneratedRuntimeSupport {
    private GeneratedRuntimeSupport() {}

    static URLClassLoader compile(String source, Path directory) throws Exception {
        var arguments = new ArrayList<String>(List.of("--release", "21", "-classpath",
                System.getProperty("java.class.path"), "-d", directory.toString()));
        for (var file : GeneratorTestSupport.generateSuccessFromText(source)) {
            if (!file.relativePath().endsWith(".java")) continue;
            Path path = directory.resolve(file.relativePath());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.content());
            arguments.add(path.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, GeneratedRuntimeSupport.class.getClassLoader());
    }
}
