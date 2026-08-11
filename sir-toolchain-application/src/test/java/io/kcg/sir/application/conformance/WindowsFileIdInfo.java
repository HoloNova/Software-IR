package io.kcg.sir.application.conformance;

import com.sun.jna.Structure;

/** Native Win32 FILE_ID_INFO layout used only by the Stage E test harness. */
@Structure.FieldOrder({"volumeSerialNumber", "fileId"})
public final class WindowsFileIdInfo extends Structure {
    public long volumeSerialNumber;
    public byte[] fileId = new byte[16];
}