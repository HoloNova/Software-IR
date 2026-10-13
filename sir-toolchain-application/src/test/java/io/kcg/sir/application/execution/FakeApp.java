package io.kcg.sir.application.execution;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

/**
 * A stand-in for a generated Spring Boot application, behaving as FAKE_MODE says. It is packaged into a real
 * jar by the tests and started by {@code java -jar}, so the host is exercised against a real process.
 * Only JDK classes are used: the jar holds nothing but this one class file.
 */
public final class FakeApp {
    public static void main(String[] args) throws Exception {
        String mode = System.getenv().getOrDefault("FAKE_MODE", "ok");
        String secret = System.getenv("FAKE_SECRET");
        if (secret != null) {
            System.out.println("connecting with password=" + secret);
        }

        if (mode.equals("exit")) {
            System.out.println("boom");
            System.exit(3);
        }

        String childFile = System.getenv("FAKE_CHILD_PID_FILE");
        if (childFile != null) {
            Process child = new ProcessBuilder("sleep", "300").start();
            Files.writeString(Path.of(childFile), Long.toString(child.pid()), StandardCharsets.UTF_8);
        }

        int port = Integer.parseInt(System.getProperty("server.port"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body = "ready";
            if (mode.equals("never-ready")) {
                status = 503;
                body = "starting";
            } else if (path.equals("/env")) {
                body = String.join(",", new TreeSet<>(System.getenv().keySet()));
            } else if (path.equals("/args")) {
                body = String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()) + " address=" + System.getProperty("server.address");
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        System.out.println("listening on " + port);
        Thread.currentThread().join();
    }

    private FakeApp() throws IOException {
        throw new IOException("not instantiable");
    }
}
