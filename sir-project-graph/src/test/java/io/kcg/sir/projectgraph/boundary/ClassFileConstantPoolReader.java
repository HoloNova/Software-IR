package io.kcg.sir.projectgraph.boundary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Minimal test-only reader for the constant pool of a single JVM class file.
 *
 * <p>It exists so the Project Graph read-only boundary gate can inspect the bytecode that actually
 * enters {@code target/classes} instead of inferring dependencies from source imports. It parses
 * only the constant pool, which is enough to observe every internal class name, member reference
 * (owner + member name + descriptor), member name and string literal the class file refers to,
 * without adding a bytecode library dependency.
 *
 * <p>It is an independent implementation for this module, as decided in Q2 (see the work order's
 * D2): no shared test-jar or new module was introduced. The rule tables live in the boundary test
 * and are registered separately in the qualification inventory.
 *
 * <p>Malformed input is never skipped silently: an invalid magic number, a truncated class file or
 * an unknown constant pool tag fails immediately with the class file, the byte offset and, for an
 * unknown tag, the tag value.
 */
public final class ClassFileConstantPoolReader {

    private static final long MAGIC = 0xCAFEBABEL;

    private static final int CONSTANT_UTF8 = 1;
    private static final int CONSTANT_INTEGER = 3;
    private static final int CONSTANT_FLOAT = 4;
    private static final int CONSTANT_LONG = 5;
    private static final int CONSTANT_DOUBLE = 6;
    private static final int CONSTANT_CLASS = 7;
    private static final int CONSTANT_STRING = 8;
    private static final int CONSTANT_FIELD_REF = 9;
    private static final int CONSTANT_METHOD_REF = 10;
    private static final int CONSTANT_INTERFACE_METHOD_REF = 11;
    private static final int CONSTANT_NAME_AND_TYPE = 12;
    private static final int CONSTANT_METHOD_HANDLE = 15;
    private static final int CONSTANT_METHOD_TYPE = 16;
    private static final int CONSTANT_DYNAMIC = 17;
    private static final int CONSTANT_INVOKE_DYNAMIC = 18;
    private static final int CONSTANT_MODULE = 19;
    private static final int CONSTANT_PACKAGE = 20;

    private ClassFileConstantPoolReader() {
    }

    /** How a constant pool {@code CONSTANT_Utf8} entry is used by the class file. */
    public enum EntryKind {
        /** Named as an internal class name by a {@code CONSTANT_Class} entry. */
        CLASS_NAME,
        /** Referenced as a string literal by a {@code CONSTANT_String} entry. */
        STRING_LITERAL,
        /** Descriptor, member name, tag or otherwise unclassified pool text. */
        OTHER
    }

    /** One {@code CONSTANT_Utf8} entry of a class file constant pool. */
    public record Utf8Entry(int index, String value, EntryKind kind) {
    }

    /** One resolved field/method/interface-method reference: {@code owner.member}. */
    public record MemberReference(int index, String owner, String name, String descriptor) {

        /** {@code owner.name} form used by the boundary rule tables. */
        public String member() {
            return owner + "." + name;
        }
    }

    /** The reusable views of one class file constant pool. */
    public record ConstantPool(List<Utf8Entry> utf8Entries, List<MemberReference> memberReferences) {

        public ConstantPool {
            utf8Entries = List.copyOf(utf8Entries);
            memberReferences = List.copyOf(memberReferences);
        }

        public List<Utf8Entry> entriesOfKind(EntryKind kind) {
            return utf8Entries.stream().filter(entry -> entry.kind() == kind).toList();
        }

        /** Distinct internal class names referenced by {@code CONSTANT_Class} entries, sorted. */
        public List<String> referencedClassNames() {
            Set<String> names = new TreeSet<>();
            for (Utf8Entry entry : entriesOfKind(EntryKind.CLASS_NAME)) {
                names.add(entry.value());
            }
            return List.copyOf(names);
        }
    }

    /** Thrown when a class file cannot be read as a well formed constant pool. */
    public static final class ClassFileFormatException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ClassFileFormatException(String message) {
            super(message);
        }
    }

    public static ConstantPool read(Path classFile) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(classFile);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read class file: " + classFile, e);
        }
        return read(classFile, bytes);
    }

    /**
     * Reads the constant pool of {@code bytes}. {@code source} is only used as the label reported by
     * {@link ClassFileFormatException} and is never touched on disk.
     */
    public static ConstantPool read(Path source, byte[] bytes) {
        Cursor cursor = new Cursor(source, bytes);
        int magicOffset = cursor.offset();
        if (cursor.u4() != MAGIC) {
            throw cursor.failure(magicOffset,
                    "invalid magic number, expected 0xCAFEBABE (not a JVM class file)");
        }
        cursor.u2(); // minor_version
        cursor.u2(); // major_version
        int entryCount = cursor.u2();
        if (entryCount < 1) {
            throw cursor.failure(cursor.offset() - 2,
                    "invalid constant_pool_count " + entryCount + ", expected at least 1");
        }

        String[] utf8Values = new String[entryCount];
        int[] classIndexes = new int[entryCount];
        int[] stringIndexes = new int[entryCount];
        int[] nameAndTypeNameIndexes = new int[entryCount];
        int[] nameAndTypeDescriptorIndexes = new int[entryCount];
        List<int[]> memberReferences = new ArrayList<>();

        for (int index = 1; index < entryCount; index++) {
            int tagOffset = cursor.offset();
            int tag = cursor.u1();
            switch (tag) {
                case CONSTANT_UTF8 -> {
                    int length = cursor.u2();
                    utf8Values[index] = cursor.utf8(length);
                }
                case CONSTANT_INTEGER, CONSTANT_FLOAT -> cursor.skip(4);
                case CONSTANT_LONG, CONSTANT_DOUBLE -> {
                    cursor.skip(8);
                    index++; // long and double occupy two constant pool slots
                }
                case CONSTANT_CLASS -> classIndexes[index] = cursor.u2();
                case CONSTANT_STRING -> stringIndexes[index] = cursor.u2();
                case CONSTANT_NAME_AND_TYPE -> {
                    nameAndTypeNameIndexes[index] = cursor.u2();
                    nameAndTypeDescriptorIndexes[index] = cursor.u2();
                }
                case CONSTANT_FIELD_REF, CONSTANT_METHOD_REF, CONSTANT_INTERFACE_METHOD_REF -> {
                    int classIndex = cursor.u2();
                    int nameAndTypeIndex = cursor.u2();
                    memberReferences.add(new int[] {index, classIndex, nameAndTypeIndex});
                }
                case CONSTANT_DYNAMIC, CONSTANT_INVOKE_DYNAMIC -> cursor.skip(4);
                case CONSTANT_METHOD_HANDLE -> cursor.skip(3);
                case CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE -> cursor.skip(2);
                default -> throw cursor.failure(tagOffset,
                        "unknown constant pool tag " + tag + " at constant pool index " + index);
            }
        }

        if (cursor.remaining() < 4) {
            throw cursor.failure(cursor.offset(),
                    "class file truncated after the constant pool: expected access_flags and this_class");
        }

        Set<Integer> classNameIndexes = new TreeSet<>();
        for (int index = 1; index < entryCount; index++) {
            if (classIndexes[index] != 0) {
                classNameIndexes.add(classIndexes[index]);
            }
        }

        Set<Integer> stringLiteralIndexes = new TreeSet<>();
        for (int index = 1; index < entryCount; index++) {
            if (stringIndexes[index] != 0) {
                stringLiteralIndexes.add(stringIndexes[index]);
            }
        }

        List<Utf8Entry> entries = new ArrayList<>();
        for (int index = 1; index < entryCount; index++) {
            String value = utf8Values[index];
            if (value != null) {
                EntryKind kind = classNameIndexes.contains(index)
                        ? EntryKind.CLASS_NAME
                        : stringLiteralIndexes.contains(index) ? EntryKind.STRING_LITERAL : EntryKind.OTHER;
                entries.add(new Utf8Entry(index, value, kind));
            }
        }

        return new ConstantPool(entries,
                resolveMembers(memberReferences, classIndexes, nameAndTypeNameIndexes,
                        nameAndTypeDescriptorIndexes, utf8Values));
    }

    private static List<MemberReference> resolveMembers(List<int[]> rawReferences, int[] classIndexes,
            int[] nameAndTypeNameIndexes, int[] nameAndTypeDescriptorIndexes, String[] utf8Values) {
        List<MemberReference> members = new ArrayList<>();
        for (int[] raw : rawReferences) {
            int ownerNameIndex = classIndexes[raw[1]];
            int nameIndex = nameAndTypeNameIndexes[raw[2]];
            int descriptorIndex = nameAndTypeDescriptorIndexes[raw[2]];
            String owner = utf8Values[ownerNameIndex];
            String name = utf8Values[nameIndex];
            if (owner == null || name == null) {
                throw new IllegalStateException(
                        "unresolved member reference at constant pool index " + raw[0]
                                + ": owner#" + raw[1] + " nameAndType#" + raw[2]);
            }
            String descriptor = utf8Values[descriptorIndex];
            members.add(new MemberReference(raw[0], owner, name, descriptor == null ? "" : descriptor));
        }
        return members;
    }

    private static final class Cursor {

        private final Path source;
        private final byte[] bytes;
        private int offset;

        Cursor(Path source, byte[] bytes) {
            this.source = source;
            this.bytes = bytes;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return bytes.length - offset;
        }

        int u1() {
            require(1);
            return bytes[offset++] & 0xFF;
        }

        int u2() {
            require(2);
            int value = ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
            offset += 2;
            return value;
        }

        long u4() {
            require(4);
            long value = ((long) (bytes[offset] & 0xFF) << 24)
                    | ((long) (bytes[offset + 1] & 0xFF) << 16)
                    | ((long) (bytes[offset + 2] & 0xFF) << 8)
                    | (long) (bytes[offset + 3] & 0xFF);
            offset += 4;
            return value;
        }

        void skip(int length) {
            require(length);
            offset += length;
        }

        String utf8(int length) {
            require(length);
            String value = new String(bytes, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        ClassFileFormatException failure(int failureOffset, String detail) {
            return new ClassFileFormatException(
                    "malformed class file " + source + " at offset " + failureOffset + ": " + detail);
        }

        private void require(int length) {
            if (remaining() < length) {
                throw failure(offset, "truncated data: need " + length + " byte(s), have " + remaining());
            }
        }
    }
}
