package org.kylepitcock.legacyconnector.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class Forge1201JarInspectorTest {
    @TempDir
    Path tempDir;

    @Test
    void recognizesForge1201JarFromModsToml() throws IOException {
        Path jar = createJar("META-INF/mods.toml", forge1201Metadata());

        Forge1201JarInfo info = LegacyJarInspector.inspectForge1201(jar);

        assertNotNull(info);
        assertEquals("[1.20.1,1.20.2)", info.minecraftVersionRange());
        assertEquals("[47.3.0,)", info.forgeVersionRange());
        assertEquals("alien_evolution", info.mods().get(0).modId());
        assertEquals("Alien Evolution", info.displayName());
        assertEquals(8, info.mods().get(0).dependencies().size());
        assertEquals("minecraft", info.mods().get(0).dependencies().get(0).modId());
        assertEquals("forge", info.mods().get(0).dependencies().get(1).modId());
        assertEquals("palladium", info.mods().get(0).dependencies().get(2).modId());
        assertEquals("geckolib", info.mods().get(0).dependencies().get(3).modId());
        assertEquals("kubejs", info.mods().get(0).dependencies().get(4).modId());
        assertEquals("rhino", info.mods().get(0).dependencies().get(5).modId());
        assertEquals("architectury", info.mods().get(0).dependencies().get(6).modId());
        assertEquals("pehkui", info.mods().get(0).dependencies().get(7).modId());
        assertEquals("[47.3.0,)", info.mods().get(0).dependencies().get(1).versionRange());
        assertEquals(true, info.mods().get(0).dependencies().get(1).mandatory());
    }

    @Test
    void reportsNativeDependencyCandidatesWithoutClaimingCompatibility() throws IOException {
        Path legacyJar = createJar("META-INF/mods.toml", forge1201Metadata());
        Path nativeGecko = createJar("META-INF/neoforge.mods.toml", """
                modLoader="javafml"
                loaderVersion="[4,)"
                [[mods]]
                modId="geckolib"
                version="4.8.0"

                [[dependencies.geckolib]]
                modId="optional_dependency_not_installed"
                mandatory=false
                versionRange="*"
                ordering="NONE"
                side="BOTH"
                """);
        Path nativeKube = createJar("META-INF/neoforge.mods.toml", """
                modLoader="javafml"
                loaderVersion="[4,)"
                [[mods]]
                modId="kubejs"
                version="2101.7.0"
                """);

        Forge1201JarInfo info = LegacyJarInspector.inspectForge1201(legacyJar);
        var nativeIds = Forge1201DependencyResolver.findNativeModIds(java.util.List.of(tempDir));
        var resolutions = Forge1201DependencyResolver.resolve(info, nativeIds);

        assertEquals(2, nativeIds.size());
        org.junit.jupiter.api.Assertions.assertFalse(nativeIds.contains("optional_dependency_not_installed"));
        assertEquals(Forge1201DependencyResolver.Status.NATIVE_ID_PRESENT,
                resolutions.stream().filter(r -> r.modId().equals("geckolib")).findFirst().orElseThrow().status());
        assertEquals(Forge1201DependencyResolver.Status.NATIVE_ID_PRESENT,
                resolutions.stream().filter(r -> r.modId().equals("kubejs")).findFirst().orElseThrow().status());
        assertEquals(Forge1201DependencyResolver.Status.REQUIRED_MISSING,
                resolutions.stream().filter(r -> r.modId().equals("palladium")).findFirst().orElseThrow().status());
        assertEquals(Forge1201DependencyResolver.Status.HOST_BRIDGE,
                resolutions.stream().filter(r -> r.modId().equals("forge")).findFirst().orElseThrow().status());
        org.junit.jupiter.api.Assertions.assertTrue(Forge1201DependencyResolver.summarize(info, nativeIds)
                .contains("API/version not yet verified"));
    }

    @Test
    void analyzesForgeApiClassAndMemberReferencesWithoutLoadingTheMod() throws IOException {
        Path jar = createJarWithClass("META-INF/mods.toml", forge1201Metadata(),
                "sample/AlienEvoMod.class", sampleForgeModClass());

        Forge1201ApiUsageAnalyzer.Analysis analysis = Forge1201ApiUsageAnalyzer.analyze(jar);

        assertEquals(1, analysis.classCount());
        assertEquals(java.util.List.of("sample/AlienEvoMod (alien_evolution)"), analysis.modEntrypoints());
        org.junit.jupiter.api.Assertions.assertTrue(analysis.referencedApiClasses()
                .contains("net/minecraftforge/fml/ModList"));
        org.junit.jupiter.api.Assertions.assertTrue(analysis.methodReferences().stream()
                .anyMatch(reference -> reference.owner().equals("net/minecraftforge/fml/ModList")
                        && reference.name().equals("get")));
        assertEquals(1, analysis.methodCallSites().size());
        var callSite = analysis.methodCallSites().get(0);
        assertEquals("sample/AlienEvoMod", callSite.callerClass());
        assertEquals("sample", callSite.callerMethod());
        assertEquals("()V", callSite.callerDescriptor());
        assertEquals("net/minecraftforge/fml/ModList", callSite.targetOwner());
        assertEquals("get", callSite.targetName());
        assertEquals("()Lnet/minecraftforge/fml/ModList;", callSite.targetDescriptor());
        assertEquals(Opcodes.INVOKESTATIC, callSite.opcode());
        org.junit.jupiter.api.Assertions.assertTrue(analysis.fieldReferences().stream()
                .anyMatch(reference -> reference.owner().equals("net/minecraftforge/registries/ForgeRegistries")
                        && reference.name().equals("ITEMS")));
    }

    @Test
    void ignoresNeoForgeMetadata() throws IOException {
        Path jar = createJar("META-INF/neoforge.mods.toml", forge1201Metadata());

        assertNull(LegacyJarInspector.inspectForge1201(jar));
    }

    @Test
    void ignoresOtherMinecraftVersions() throws IOException {
        String metadata = forge1201Metadata().replace("[1.20.1,1.20.2)", "[1.20.2,1.20.3)");
        Path jar = createJar("META-INF/mods.toml", metadata);

        assertNull(LegacyJarInspector.inspectForge1201(jar));
    }

    @Test
    void requiresAForgeDependency() throws IOException {
        String metadata = forge1201Metadata().replace("""
                [[dependencies.alien_evolution]]
                modId="forge"
                mandatory=true
                versionRange="[47.3.0,)"
                ordering="NONE"
                side="BOTH"
                """, "");
        Path jar = createJar("META-INF/mods.toml", metadata);

        assertNull(LegacyJarInspector.inspectForge1201(jar));
    }

    private Path createJarWithClass(String metadataPath, String metadata, String classPath, byte[] classBytes)
            throws IOException {
        Path jar = Files.createTempFile(tempDir, "forge-1.20.1-analysis-", ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(metadataPath));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(classPath));
            out.write(classBytes);
            out.closeEntry();
        }
        return jar;
    }

    private static byte[] sampleForgeModClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/AlienEvoMod", null, "java/lang/Object", null);
        AnnotationVisitor mod = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        mod.visit("value", "alien_evolution");
        mod.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sample",
                "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraftforge/fml/ModList",
                "get", "()Lnet/minecraftforge/fml/ModList;", false);
        method.visitInsn(Opcodes.POP);
        method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/registries/ForgeRegistries",
                "ITEMS", "Lnet/minecraftforge/registries/IForgeRegistry;");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private Path createJar(String metadataPath, String metadata) throws IOException {
        Path jar = Files.createTempFile(tempDir, "forge-1.20.1-", ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(metadataPath));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static String forge1201Metadata() {
        return """
                modLoader="javafml"
                loaderVersion="[47.2,)"
                license="MIT"

                [[mods]]
                modId="alien_evolution"
                version="1.0.0"
                displayName="Alien Evolution"
                description="Test metadata"

                [[dependencies.alien_evolution]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.20.2)"
                ordering="NONE"
                side="BOTH"

                [[dependencies.alien_evolution]]
                modId="forge"
                mandatory=true
                versionRange="[47.3.0,)"
                ordering="NONE"
                side="BOTH"

                [[dependencies.alien_evolution]]
                modId="palladium"
                mandatory=true
                versionRange="*"
                ordering="AFTER"
                side="BOTH"

                [[dependencies.alien_evolution]]
                modId="geckolib"
                mandatory=true
                versionRange="*"
                ordering="AFTER"
                side="BOTH"

                [[dependencies.alien_evolution]]
                modId="kubejs"
                mandatory=true
                versionRange="*"
                ordering="AFTER"
                side="BOTH"

                [[dependencies.alien_evolution]]
                modId="rhino"
                mandatory=true
                versionRange="*"
                ordering="AFTER"
                side="BOTH"

                [[dependencies.alien_evolution]]
                modId="architectury"
                mandatory=true
                versionRange="*"
                ordering="AFTER"
                side="BOTH"

                [[dependencies.alien_evolution]]
                modId="pehkui"
                mandatory=true
                versionRange="*"
                ordering="AFTER"
                side="BOTH"
                """;
    }
}
