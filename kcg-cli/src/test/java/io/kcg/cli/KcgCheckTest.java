package io.kcg.cli;

import io.kcg.sir.application.api.*;
import io.kcg.sir.api.*;
import io.kcg.sir.source.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KcgCheckTest {
    @TempDir Path root;
    record ProcessResult(int exit,String stdout,String stderr) {}
    static String sir() throws Exception {
        try(var in=KcgCheckTest.class.getResourceAsStream("/io/kcg/cli/campus-market.sir")) { return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8); }
    }
    ProcessResult run(Path cwd, String language, String stdin, String... args) throws Exception {
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        classpath=String.join(File.pathSeparator,Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))).map(p->Path.of(p).toAbsolutePath().toString()).toList());
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-XX:-UsePerfData","-Xmx256m","-XX:MaxMetaspaceSize=128m","-Duser.language="+language,"-Duser.country=TR","-cp",classpath,"io.kcg.cli.KcgCli")); command.addAll(List.of(args));
        var process=new ProcessBuilder(command).directory(cwd.toFile()).start();
        try(var out=process.getOutputStream()){out.write(stdin.getBytes(StandardCharsets.UTF_8));}
        byte[] stdout=process.getInputStream().readAllBytes(),stderr=process.getErrorStream().readAllBytes();
        assertTrue(process.waitFor(60,TimeUnit.SECONDS));
        return new ProcessResult(process.exitValue(),new String(stdout,StandardCharsets.UTF_8),new String(stderr,StandardCharsets.UTF_8));
    }
    static Map<String,String> fingerprint(Path root) throws Exception {
        var result=new TreeMap<String,String>();
        try(var paths=Files.walk(root)) {
            for(var p:paths.toList()) {
                var a=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                result.put(root.relativize(p).toString(),a.fileKey()+":"+a.lastModifiedTime()+":"+(a.isRegularFile()?HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))):"directory"));
            }
        } return result;
    }
    @Test void realJvmsAreDeterministicAcrossLocaleAndCwdAndChangeNoFiles() throws Exception {
        Path a=Files.createDirectories(root.resolve("a")),b=Files.createDirectories(root.resolve("b"));
        Files.createDirectories(a.resolve("state"));Files.writeString(a.resolve("state/CURRENT"),"user sentinel");Files.writeString(b.resolve("owned.txt"),"untouched");
        String source=sir(); String input="{\"id\":\"good😀\",\"sir\":"+JsonStringEncoder.encode(source)+"}\n{invalid\n{\"id\":\"semantic\",\"sir\":"+JsonStringEncoder.encode(source.replace("return goods;","return missingName;"))+"}";
        Files.writeString(a.resolve("samples.jsonl"),input); var before=fingerprint(root);
        var first=run(a,"en",input,"check");var second=run(b,"tr",input,"check");
        assertEquals(0,first.exit(),first.stderr());assertEquals(0,second.exit(),second.stderr());assertEquals("",first.stderr());assertEquals("",second.stderr());
        assertEquals(first.stdout(),second.stdout());assertEquals(3,first.stdout().lines().count());
        var rows=first.stdout().lines().toList();assertTrue(rows.get(0).startsWith("{\"id\":\"good😀\",\"ok\":true,\"stage\":\"GENERATION\""));
        assertTrue(rows.get(1).contains("\"id\":null,\"ok\":false"));assertTrue(rows.get(2).contains("\"stage\":\"SEMANTIC\""));
        assertTrue(rows.get(2).contains("SIR-FLOW-001"));assertFalse(first.stdout().contains(root.toString()));assertEquals(before,fingerprint(root));
    }
    @Test void commandParsingHelpAndFrozenOldHelpGolden() throws Exception {
        assertInstanceOf(CliCommandLine.ParsedCheck.class,CliCommandLine.parse(new String[]{"check"}));
        for(var args:List.of(new String[]{"check","--input","file"},new String[]{"check","--help","extra"},new String[]{"check","--state-root","/tmp"}))
            assertInstanceOf(CliCommandLine.UsageError.class,CliCommandLine.parse(args));
        var help=run(root,"en","","check","--help");assertEquals(0,help.exit());assertTrue(help.stdout().contains("Static checking only"));
        var top=run(root,"en","","--help");assertTrue(top.stdout().contains("  check "));
        for(var pair:List.of(new String[]{"context","f10a662800168dd3ac6ddb3ba7662e13c9b2b707a05a6d224eb3316d96399b1f"},new String[]{"plan","6233fa562e6fd5dd3f50b1dfae35e2e5083b72b5f433153d1d85680cc754265e"})) {
            var r=run(root,"en","",pair[0],"--help");assertEquals(0,r.exit());assertEquals(pair[1],HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(r.stdout().getBytes(StandardCharsets.UTF_8))));
        }
        assertEquals("984ddae03b52a99319e5a47e795720d5b6193cdf78b17b4f98c4f9a63b2549f5",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(run(root,"en","","--version").stdout().getBytes(StandardCharsets.UTF_8))));
    }
    @Test void renderingKeepsOriginalRelatedFixIdentityAndUnicodeInformation() throws Exception {
        var span=new SourceSpan(SourceId.of("sample.sir"),new SourcePosition(0,1,1),new SourcePosition(2,1,3));
        var d=new ValidationDiagnostic("TEST","WARNING",ValidationStopAfter.SEMANTIC,"original\n\ud800",span,
            List.of(new RelatedLocation("first",span)),List.of(new FixHint("fix",span,"x\"😀")),"sir://x","ast://x","lir://x","x.java");
        var out=new ByteArrayOutputStream();CheckResultRenderer.render(out,new SirValidationResult("opaque",true,ValidationStopAfter.SEMANTIC,List.of(d),0,null,null));
        String text=out.toString(StandardCharsets.UTF_8);assertEquals(1,text.lines().count());assertTrue(text.endsWith("\n"));
        assertTrue(text.contains("\"message\":\"original\\n\\ud800\""));assertTrue(text.contains("\"related\":[{\"message\":\"first\""));
        assertTrue(text.contains("\"replacementText\":\"x\\\"😀\""));assertTrue(text.contains("\"sourceNodeId\":\"ast://x\""));assertTrue(text.contains("\"endCodePointOffset\":2"));
    }
    @Test void checkProcessesSingleLineWithoutFinalLfAndEmptyInput() throws Exception {
        assertEquals("",run(root,"en","","check").stdout());
        var r=run(root,"en","{\"id\":\"a\",\"sir\":\"bad\"}","check");assertEquals(0,r.exit());assertEquals(1,r.stdout().lines().count());assertTrue(r.stdout().contains("\"ok\":false"));
    }
}
