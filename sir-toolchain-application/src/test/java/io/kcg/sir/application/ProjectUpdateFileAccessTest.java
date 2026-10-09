package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.tree;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectUpdateFileAccessTest {
    @TempDir Path temp;
    @Test void linkedBackupAndBoundedDirectoryMoveKeepOriginalAndCandidateIdentities() throws Exception {
        var a=Files.createDirectory(temp.resolve("a"));var b=Files.createDirectory(temp.resolve("b"));Files.writeString(a.resolve("old"),"base");Files.writeString(b.resolve("new"),"candidate");
        try(var from=new SecureFileAccess(a);var to=new SecureFileAccess(b)) {
            var old=from.identity("old");var fresh=to.identity("new");from.linkOwned("old",old,to,"backup");to.moveOwned("new",fresh,from,"old",old,x->{});
            assertEquals("base",Files.readString(b.resolve("backup")));assertEquals("candidate",Files.readString(a.resolve("old")));assertEquals(fresh,from.identity("old"));
            to.moveOwned("backup",old,from,"old",fresh,x->{});assertEquals(old,from.identity("old"));assertEquals("base",Files.readString(a.resolve("old")));
        }
    }
    @Test void sameBytesAreNotAnOwnedFileAndMoveRaceIsRecheckedAfterCheckpoint() throws Exception {
        var a=Files.createDirectory(temp.resolve("a"));var b=Files.createDirectory(temp.resolve("b"));Files.writeString(a.resolve("old"),"base");Files.writeString(b.resolve("new"),"candidate");
        try(var from=new SecureFileAccess(a);var to=new SecureFileAccess(b)) {
            var old=from.identity("old");var fresh=to.identity("new");
            assertThrows(java.io.IOException.class,()->to.moveOwned("new",fresh,from,"old",old,point->{try {Files.move(a.resolve("old"),a.resolve("retained"));Files.writeString(a.resolve("old"),"base");}catch(Exception e){throw new RuntimeException(e);}}));
            var before=tree(temp);assertThrows(java.io.IOException.class,()->from.deleteOwned("old",old));assertEquals(before,tree(temp));assertEquals("candidate",Files.readString(b.resolve("new")));
        }
    }
    @Test void fixedJournalSlotCanBeRewrittenButAnExternalReplacementIsNeverTruncated() throws Exception {
        Files.writeString(temp.resolve("slot"),"old");try(var files=new SecureFileAccess(temp)) {
            var key=files.identity("slot");files.overwriteOwned("slot",key,"new".getBytes(StandardCharsets.UTF_8),x->{});assertEquals(key,files.identity("slot"));assertEquals("new",Files.readString(temp.resolve("slot")));
            Files.move(temp.resolve("slot"),temp.resolve("retained"));Files.writeString(temp.resolve("slot"),"new");var before=tree(temp);
            assertThrows(java.io.IOException.class,()->files.overwriteOwned("slot",key,"different".getBytes(StandardCharsets.UTF_8),x->{}));assertEquals(before,tree(temp));
        }
    }
    @Test void terminalDirectoryRelocationHasNoEmptyUnprovableDeletionWindow() throws Exception {
        Files.createDirectory(temp.resolve("active"));Files.createDirectory(temp.resolve("receipts"));Files.createDirectory(temp.resolve("active/tx"));Files.writeString(temp.resolve("active/tx/binding"),"immutable evidence");
        try(var files=new SecureFileAccess(temp)) {var key=files.directoryIdentity("active/tx");files.moveOwnedDirectory("active/tx",key,"receipts/tx",x->{});assertEquals(key,files.directoryIdentity("receipts/tx"));assertFalse(Files.exists(temp.resolve("active/tx")));assertEquals("immutable evidence",Files.readString(temp.resolve("receipts/tx/binding")));}
        assertTrue(Files.isDirectory(temp.resolve("active"))); // The retained empty parent needs receipt-proven identity before a read gate accepts it.
    }
    @Test void changedParentDirectoryAndUnknownNonemptyCleanupAreRefused() throws Exception {
        var parent=Files.createDirectory(temp.resolve("parent"));Files.writeString(parent.resolve("file"),"base");
        try(var files=new SecureFileAccess(temp)) {
            var key=files.identity("parent/file");var directory=files.directoryIdentity("parent");Files.move(parent,temp.resolve("retained"));Files.createDirectory(parent);Files.writeString(parent.resolve("file"),"base");var before=tree(temp);
            assertThrows(java.io.IOException.class,()->files.deleteOwned("parent/file",key));assertThrows(java.io.IOException.class,()->files.deleteOwnedDirectory("parent",directory));assertEquals(before,tree(temp));
            assertThrows(java.io.IOException.class,()->files.deleteOwnedDirectory("parent",files.directoryIdentity("parent")));assertEquals(before,tree(temp));
        }
    }
}
