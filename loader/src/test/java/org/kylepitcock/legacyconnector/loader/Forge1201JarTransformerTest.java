package org.kylepitcock.legacyconnector.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

final class Forge1201JarTransformerTest {
    @TempDir
    Path tempDir;

    private static final String FORGE_METADATA = """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="All Rights Reserved"

            [[mods]]
            modId="sample"
            version="1.0.0"
            displayName="Sample"
            description="ASM transformer fixture"

            [[dependencies.sample]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.20.1,1.21)"
            ordering="NONE"
            side="BOTH"

            [[dependencies.sample]]
            modId="forge"
            mandatory=true
            versionRange="[47,)"
            ordering="NONE"
            side="BOTH"
            """;

    @Test
    void convertsMetadataAndRelocatesReviewedBytecodeReferences() throws Exception {
        Path original = createSourceJar(false);
        Path output = tempDir.resolve("out").resolve("sample-transformed.jar");
        String originalDigest = sha256(original);

        Forge1201JarTransformer.Result result = Forge1201JarTransformer.transform(
                original, output, "[1,)", "[21.1.258,)", "[1.21.1,1.22)");

        assertTrue(Files.isRegularFile(output));
        assertEquals(originalDigest, sha256(original));
        assertEquals(originalDigest, result.inputSha256());
        assertEquals(sha256(output), result.outputSha256());
        assertNotEquals(result.inputSha256(), result.outputSha256());

        Forge1201BytecodeTransformer.Result bytecode = result.bytecode();
        assertEquals(2, bytecode.classesScanned());
        assertEquals(2, bytecode.classesChanged());
        assertEquals(1, bytecode.classEntriesRelocated());
        assertTrue(bytecode.relocatedTypes().stream()
                .anyMatch(value -> value.startsWith("net/minecraftforge/fml/ModList -> ")));
        assertTrue(bytecode.manualAdapterTypes().contains("net/minecraftforge/registries/ForgeRegistries"));
        assertTrue(bytecode.manualAdapterTypes().contains("net/minecraftforge/registries/IForgeRegistry"));
        assertTrue(bytecode.unmappedForgeApiTypes().contains("net/minecraftforge/unknown/MissingApi"));

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(output.toFile())) {
            assertTrue(zip.getEntry(Forge1201JarArchiveTransformer.SOURCE_METADATA) == null);
            assertTrue(zip.getEntry(Forge1201JarArchiveTransformer.TARGET_METADATA) != null);
            assertTrue(zip.getEntry("net/minecraftforge/fml/legacy/OwnClass.class") == null);
            assertTrue(zip.getEntry("net/neoforged/fml/legacy/OwnClass.class") != null);
            assertTrue(zip.getEntry("META-INF/TEST.SF") == null);
            assertTrue(zip.getEntry("META-INF/services/net.minecraftforge.fml.ModList") == null);

            var service = zip.getEntry("META-INF/services/net.neoforged.fml.ModList");
            assertTrue(service != null);
            String serviceText = new String(zip.getInputStream(service).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(serviceText.contains("# retained provider comment"));
            assertTrue(serviceText.contains("net.neoforged.fml.legacy.OwnClass"));

            ClassNode caller = new ClassNode();
            new org.objectweb.asm.ClassReader(
                    zip.getInputStream(zip.getEntry("sample/Caller.class")).readAllBytes()).accept(caller, 0);
            assertEquals("Lnet/neoforged/fml/common/Mod;", caller.visibleAnnotations.get(0).desc);

            var invoke = caller.methods.stream()
                    .filter(method -> method.name.equals("invoke"))
                    .findFirst().orElseThrow();
            MethodInsnNode methodCall = (MethodInsnNode) invoke.instructions.stream()
                    .filter(instruction -> instruction instanceof MethodInsnNode)
                    .findFirst().orElseThrow();
            assertEquals("net/neoforged/fml/ModList", methodCall.owner);
            assertEquals("()Lnet/neoforged/fml/ModList;", methodCall.desc);

            FieldInsnNode field = (FieldInsnNode) invoke.instructions.stream()
                    .filter(instruction -> instruction instanceof FieldInsnNode)
                    .findFirst().orElseThrow();
            assertEquals("net/minecraftforge/registries/ForgeRegistries", field.owner);
            assertEquals("Lnet/minecraftforge/registries/IForgeRegistry;", field.desc);

            LdcInsnNode classLiteral = (LdcInsnNode) invoke.instructions.stream()
                    .filter(instruction -> instruction instanceof LdcInsnNode)
                    .findFirst().orElseThrow();
            assertEquals("Lnet/neoforged/fml/ModList;", ((Type) classLiteral.cst).getDescriptor());

            var missing = caller.methods.stream()
                    .filter(method -> method.name.equals("missingApi"))
                    .findFirst().orElseThrow();
            assertEquals("()Lnet/minecraftforge/unknown/MissingApi;", missing.desc);

            String metadata = new String(
                    zip.getInputStream(zip.getEntry(Forge1201JarArchiveTransformer.TARGET_METADATA)).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(metadata.contains("modId=\"neoforge\""));
            assertTrue(metadata.contains("type=\"required\""));
            assertFalse(metadata.contains("modId=\"forge\""));
        }
    }

    @Test
    void refusesToOverwriteOriginalJar() throws Exception {
        Path original = createSourceJar(false);
        String digest = sha256(original);

        assertThrows(IllegalArgumentException.class, () -> Forge1201JarTransformer.transform(
                original, original, "[1,)", "[21.1.258,)", "[1.21.1,1.22)"));

        assertEquals(digest, sha256(original));
    }

    @Test
    void failsOnClassEntryRelocationCollisionWithoutWritingOutput() throws Exception {
        Path original = createSourceJar(true);
        Path output = tempDir.resolve("collision.jar");

        assertThrows(IOException.class, () -> Forge1201JarTransformer.transform(
                original, output, "[1,)", "[21.1.258,)", "[1.21.1,1.22)"));

        assertFalse(Files.exists(output));
    }

    private Path createSourceJar(boolean includeRelocationCollision) throws IOException {
        Path jar = tempDir.resolve(includeRelocationCollision ? "collision-source.jar" : "source.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(out, "META-INF/mods.toml", FORGE_METADATA);
            put(out, "META-INF/TEST.SF", "stale signature");
            put(out, "META-INF/services/net.minecraftforge.fml.ModList",
                    "# retained provider comment\nnet.minecraftforge.fml.legacy.OwnClass\n");
            put(out, "sample/Caller.class", callerClass());
            put(out, "net/minecraftforge/fml/legacy/OwnClass.class", simpleClass(
                    "net/minecraftforge/fml/legacy/OwnClass"));
            if (includeRelocationCollision) {
                put(out, "net/neoforged/fml/legacy/OwnClass.class", simpleClass(
                        "net/neoforged/fml/legacy/OwnClass"));
            }
        }
        return jar;
    }

    private static byte[] callerClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Caller", null, "java/lang/Object", null);
        var annotation = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        annotation.visit("value", "sample");
        annotation.visitEnd();

        var invoke = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke", "()V", null, null);
        invoke.visitCode();
        invoke.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/ModList",
                "get", "()Lnet/minecraftforge/fml/ModList;", false);
        invoke.visitInsn(Opcodes.POP);
        invoke.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/registries/ForgeRegistries",
                "ITEMS", "Lnet/minecraftforge/registries/IForgeRegistry;");
        invoke.visitInsn(Opcodes.POP);
        invoke.visitLdcInsn(Type.getObjectType("net/minecraftforge/fml/ModList"));
        invoke.visitInsn(Opcodes.POP);
        invoke.visitInsn(Opcodes.RETURN);
        invoke.visitMaxs(1, 0);
        invoke.visitEnd();

        var missing = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "missingApi", "()Lnet/minecraftforge/unknown/MissingApi;", null, null);
        missing.visitCode();
        missing.visitInsn(Opcodes.ACONST_NULL);
        missing.visitInsn(Opcodes.ARETURN);
        missing.visitMaxs(1, 0);
        missing.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] simpleClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void put(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
