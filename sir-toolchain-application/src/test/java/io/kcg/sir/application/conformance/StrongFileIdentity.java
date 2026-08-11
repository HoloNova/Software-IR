package io.kcg.sir.application.conformance;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Strong, immutable file identity that does NOT fall back to size, mtime,
 * digest, or path equality.
 *
 * <p>On platforms where {@link BasicFileAttributes#fileKey()} returns a
 * non-null value, that value is used directly. On Windows JDK 21, where that
 * key is absent, the identity is read in-process through
 * {@code GetFileInformationByHandleEx(FileIdInfo)}. A native open or query
 * failure means that identity is unproved and therefore fails closed.
 */
final class StrongFileIdentity {

    private static final int FILE_ID_INFO_CLASS = 18;

    private final Object key;
    private final String windowsFileId;
    private final String windowsVolumeSerial;

    private StrongFileIdentity(Object key, String windowsFileId, String windowsVolumeSerial) {
        this.key = key;
        this.windowsFileId = windowsFileId;
        this.windowsVolumeSerial = windowsVolumeSerial;
    }

    /**
     * Capture the strong identity of the file described by the given NOFOLLOW
     * attributes. Callers must separately prove that the object type is safe.
     */
    static StrongFileIdentity of(Path path, BasicFileAttributes attrs) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(attrs, "attrs");
        Object fileKey = attrs.fileKey();
        if (fileKey != null) {
            return new StrongFileIdentity(fileKey, null, null);
        }
        return fromWindowsFileIdInfo(path);
    }

    private static StrongFileIdentity fromWindowsFileIdInfo(Path path) throws IOException {
        Path absolutePath = path.toAbsolutePath().normalize();
        int openFlags = WinBase.FILE_FLAG_BACKUP_SEMANTICS
                | WinBase.FILE_FLAG_OPEN_REPARSE_POINT;
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(
                absolutePath.toString(),
                WinNT.FILE_READ_ATTRIBUTES,
                WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE,
                null,
                WinNT.OPEN_EXISTING,
                openFlags,
                null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            throw new IOException("STRONG_IDENTITY_UNAVAILABLE: cannot open native file handle");
        }
        try {
            FileIdInfo info = new FileIdInfo();
            info.write();
            boolean read = Kernel32.INSTANCE.GetFileInformationByHandleEx(
                    handle,
                    FILE_ID_INFO_CLASS,
                    info.getPointer(),
                    new WinDef.DWORD(info.size()));
            if (!read) {
                throw new IOException("STRONG_IDENTITY_UNAVAILABLE: cannot read native file ID");
            }
            info.read();
            if (isAllZero(info.fileId)) {
                throw new IOException("STRONG_IDENTITY_UNAVAILABLE: native file ID is empty");
            }
            return new StrongFileIdentity(null,
                    hex(info.fileId),
                    Long.toUnsignedString(info.volumeSerialNumber, 16));
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    private static boolean isAllZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) {
            result.append(Character.forDigit((b >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(b & 0x0f, 16));
        }
        return result.toString();
    }

    @Structure.FieldOrder({"volumeSerialNumber", "fileId"})
    private static final class FileIdInfo extends Structure {
        public long volumeSerialNumber;
        public byte[] fileId = new byte[16];
    }

    boolean equalsIdentity(StrongFileIdentity other) {
        if (other == null) {
            return false;
        }
        if (key != null) {
            return key.equals(other.key);
        }
        return Objects.equals(windowsVolumeSerial, other.windowsVolumeSerial)
                && Objects.equals(windowsFileId, other.windowsFileId);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof StrongFileIdentity that
                && equalsIdentity(that);
    }

    @Override
    public int hashCode() {
        return key != null ? key.hashCode() : Objects.hash(windowsVolumeSerial, windowsFileId);
    }

    boolean reproveMatches(Path path) throws IOException {
        BasicFileAttributes current = Files.readAttributes(path,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return equalsIdentity(StrongFileIdentity.of(path, current));
    }
}
