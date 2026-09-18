package io.kcg.cli.boundary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal reader for the constant-pool UTF-8 entries of a compiled class.
 *
 * <p>Only the constant pool is read, and only its UTF-8 entries: those carry every class name,
 * method name and descriptor a class refers to, which is exactly what a static boundary check needs.
 * The class-file header and the rest of the structure are validated far enough to guarantee the
 * pool is complete, and any malformation fails loudly rather than returning a short list — a truncated
 * read would silently look like a clean boundary.
 */
final class CliClassFileConstantPool {

    private static final int MAGIC = 0xCAFEBABE;

    private CliClassFileConstantPool() {
    }

    /**
     * @param classFile a {@code .class} file
     * @return every UTF-8 constant-pool entry, in pool order
     * @throws IOException if the file cannot be read or is not a well-formed class file
     */
    static List<String> utf8Entries(Path classFile) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.remaining() < 10) {
            throw new IOException("truncated class file: " + classFile);
        }
        if (buffer.getInt() != MAGIC) {
            throw new IOException("not a class file (bad magic): " + classFile);
        }
        buffer.getShort(); // minor version
        buffer.getShort(); // major version
        int constantPoolCount = buffer.getShort() & 0xFFFF;
        List<String> entries = new ArrayList<>();
        for (int index = 1; index < constantPoolCount; index++) {
            if (!buffer.hasRemaining()) {
                throw new IOException("truncated constant pool in " + classFile);
            }
            int tag = buffer.get() & 0xFF;
            switch (tag) {
                case 1 -> { // CONSTANT_Utf8
                    int length = buffer.getShort() & 0xFFFF;
                    if (buffer.remaining() < length) {
                        throw new IOException("truncated UTF-8 entry in " + classFile);
                    }
                    byte[] raw = new byte[length];
                    buffer.get(raw);
                    entries.add(new String(raw, StandardCharsets.UTF_8));
                }
                case 7, 8, 16, 19, 20 -> buffer.getShort(); // Class, String, MethodType, Module, Package
                case 15 -> { // MethodHandle
                    buffer.get();
                    buffer.getShort();
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> buffer.getInt();
                    // Integer, Float, Fieldref, Methodref, InterfaceMethodref, NameAndType,
                    // Dynamic, InvokeDynamic
                case 5, 6 -> { // Long, Double: two pool slots
                    buffer.getLong();
                    index++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag + " in "
                        + classFile);
            }
        }
        return entries;
    }
}
