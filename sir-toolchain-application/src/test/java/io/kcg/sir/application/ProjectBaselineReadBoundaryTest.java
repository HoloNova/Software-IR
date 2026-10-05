package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.projectbaseline.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBaselineReadBoundaryTest {
    @TempDir Path temp;
    @Test void anchoredReaderRejectsLeafDirectoryAncestorLinksTraversalAndDevices() throws Exception {
        var root=Files.createDirectory(temp.resolve("root"));var outside=Files.createDirectory(temp.resolve("outside"));Files.writeString(outside.resolve("data"),"keep");
        Files.createSymbolicLink(root.resolve("link"),outside.resolve("data"));Files.createSymbolicLink(root.resolve("dir-link"),outside);Files.createDirectory(root.resolve("dir"));
        var before=tree(temp);
        try(var reader=new SecureFileAccess(root)) {
            for(String path:List.of("link","dir-link/data","dir","../outside/data","/etc/passwd")) assertThrows(Exception.class,()->reader.read(path,1024));
        }
        Files.createSymbolicLink(temp.resolve("root-link"),root);assertThrows(Exception.class,()->new SecureFileAccess(temp.resolve("root-link")));
        assertEquals(before.get("outside/data"),tree(temp).get("outside/data"));
    }
    @Test void readIsSizeBoundedAndSizeMtimeInodeSubstitutionAreDetected() throws Exception {
        for(String kind:List.of("size","mtime","inode")) {
            var root=Files.createDirectory(temp.resolve(kind));var file=root.resolve("data");Files.writeString(file,"abcd");
            try(var reader=new SecureFileAccess(root,name->{try {
                if(kind.equals("size"))Files.writeString(file,"abcdef");
                if(kind.equals("mtime"))Files.setLastModifiedTime(file,FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis()+10000));
                if(kind.equals("inode")){var replacement=root.resolve("replacement");Files.writeString(replacement,"abcd");Files.move(replacement,file,StandardCopyOption.REPLACE_EXISTING);}
            }catch(Exception e){throw new IllegalStateException(e);}})) {assertThrows(Exception.class,()->reader.read("data",4));}
        }
        var root=Files.createDirectory(temp.resolve("limit"));Files.write(root.resolve("data"),new byte[8193]);
        try(var reader=new SecureFileAccess(root)){assertEquals(8193,reader.read("data",8193).length);assertThrows(ProjectBaselineCodec.Problem.class,()->reader.read("data",8192));}
    }
    @Test void rootIdentityChangesCannotBeUsedForPublicationOrCleanup() throws Exception {
        var root=Files.createDirectory(temp.resolve("root"));Files.writeString(root.resolve("CURRENT.new"),"a".repeat(64)+"\n");
        try(var access=new SecureFileAccess(root)) {
            Files.move(root,temp.resolve("old-root"));Files.createDirectory(root);Files.writeString(root.resolve("CURRENT.new"),"user");
            assertThrows(Exception.class,access::publishLink);assertThrows(Exception.class,()->access.writeNew("new",new byte[]{1},x->{}));
            assertEquals("user",Files.readString(root.resolve("CURRENT.new")));assertFalse(Files.exists(root.resolve("CURRENT")));assertFalse(Files.exists(root.resolve("new")));
        }
    }
    @Test void publicRegistrationRejectsStateOutputOverlapAndPhysicalRootLinksBeforePublication() throws Exception {
        var source=fixture(temp);var output=temp.resolve("output");var generated=generate(source,output);
        var before=tree(temp);var app=new ProjectBaselineApplication();
        assertInstanceOf(ProjectBaselineResult.Failure.class,app.register(new ProjectBaselineRegistrationRequest(generated.sources(),output,output.resolve("state"))));assertEquals(before,tree(temp));
        var real=Files.createDirectory(temp.resolve("real-state"));var link=temp.resolve("state-link");Files.createSymbolicLink(link,real);var linked=tree(temp);
        assertInstanceOf(ProjectBaselineResult.Failure.class,app.register(new ProjectBaselineRegistrationRequest(generated.sources(),output,link)));assertEquals(linked,tree(temp));
        var missing=temp.resolve("does-not-exist");var missingBefore=tree(temp);
        assertInstanceOf(ProjectBaselineResult.Failure.class,app.inspect(new ProjectBaselineInspectionRequest(missing,output,"a".repeat(64))));assertEquals(missingBefore,tree(temp));
    }
    @Test void directoryEnumerationStopsAtItsExplicitLimitWithoutDeletingAnyEntries() throws Exception {
        var root=Files.createDirectory(temp.resolve("root"));
        for(int i=0;i<9;i++)Files.writeString(root.resolve("f"+i),"keep");var before=tree(root);
        try(var reader=new SecureFileAccess(root)){assertTrue(assertThrows(ProjectBaselineCodec.Problem.class,()->reader.list("")).code.endsWith("LIMIT-001"));}
        assertEquals(before,tree(root));
    }
    @Test void callerBuiltOversizeAndInvalidUtf8SnapshotsAreRejectedWithoutCreatingState() throws Exception {
        var output=Files.createDirectory(temp.resolve("output"));var state=temp.resolve("state");
        for(var bytes:List.of(new byte[1024*1024+1],new byte[]{(byte)0xff})) {
            var snapshot=new io.kcg.sir.source.SourceSnapshot(ENTRY,Map.of(ENTRY,bytes));var before=tree(temp);
            var failure=assertInstanceOf(ProjectBaselineResult.Failure.class,new ProjectBaselineApplication().register(new ProjectBaselineRegistrationRequest(snapshot,output,state)));
            assertEquals(ExecutionStage.READ,failure.failedStage());assertEquals(before,tree(temp));assertFalse(Files.exists(state));
        }
    }
}
