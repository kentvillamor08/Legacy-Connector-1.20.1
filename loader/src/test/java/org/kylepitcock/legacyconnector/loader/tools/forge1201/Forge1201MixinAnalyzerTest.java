package org.kylepitcock.legacyconnector.loader.tools.forge1201;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class Forge1201MixinAnalyzerTest {
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SOURCE_ENTITY = "net/minecraft/world/entity/Entity";
    private static final String SOURCE_LIVING = "net/minecraft/world/entity/LivingEntity";

    @TempDir
    Path tempDir;

    @Test
    void resolvesManifestAndHeuristicConfigsAndVisibleAndInvisibleMixinTargets() throws Exception {
        String configPath = "mixins.sample.json";
        String config = """
                {
                  "package": "com.example.mixins",
                  "required": true,
                  "minVersion": "0.8",
                  "compatibilityLevel": "JAVA_17",
                  "plugin": "com.example.Plugin",
                  "refmap": "mixins.sample.refmap.json",
                  "mixins": ["TestMixin"],
                  "client": ["MissingClient"]
                }
                """;
        Map<String, byte[]> archive = new LinkedHashMap<>();
        archive.put(configPath, config.getBytes(StandardCharsets.UTF_8));
        archive.put("mixins.sample.refmap.json", "{}".getBytes(StandardCharsets.UTF_8));
        archive.put("com/example/Plugin.class", plainClass("com/example/Plugin"));
        archive.put("com/example/mixins/TestMixin.class", mixinClass(
                "com/example/mixins/TestMixin",
                SOURCE_ENTITY,
                SOURCE_LIVING,
                true));

        Path jar = writeJar("visible-invisible.jar", archive);
        Map<String, byte[]> targetClasses = Map.of(
                SOURCE_ENTITY, plainClass(SOURCE_ENTITY),
                SOURCE_LIVING, plainClass(SOURCE_LIVING));
        List<String> targetRequests = new ArrayList<>();
        Forge1201TargetApiVerifier.TargetIndex index = Forge1201TargetApiVerifier.createIndex(name -> {
            targetRequests.add(name);
            byte[] bytes = targetClasses.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        });

        Forge1201MixinAnalyzer.Result result = Forge1201MixinAnalyzer.analyze(
                jar,
                List.of(
                        new Forge1201MixinAnalyzer.ConfigDeclaration(
                                configPath, Forge1201MixinAnalyzer.DiscoverySource.MANIFEST),
                        new Forge1201MixinAnalyzer.ConfigDeclaration(
                                configPath, Forge1201MixinAnalyzer.DiscoverySource.RESOURCE_HEURISTIC)),
                index,
                Map.of(SOURCE_ENTITY, SOURCE_ENTITY, SOURCE_LIVING, SOURCE_LIVING),
                "synthetic-mc-class-map-v1");

        assertEquals(1, result.configs().size());
        var configFinding = result.configs().getFirst();
        assertEquals(Forge1201MixinAnalyzer.ConfigStatus.PARSED, configFinding.status());
        assertEquals(List.of(
                Forge1201MixinAnalyzer.DiscoverySource.MANIFEST,
                Forge1201MixinAnalyzer.DiscoverySource.RESOURCE_HEURISTIC),
                configFinding.discoverySources());
        assertEquals("com.example.mixins", configFinding.packageName());
        assertEquals(true, configFinding.required());
        assertEquals("0.8", configFinding.minimumVersion());
        assertEquals("JAVA_17", configFinding.compatibilityLevel());
        assertEquals(Forge1201MixinAnalyzer.PluginStatus.PRESENT_NOT_EXECUTED, configFinding.pluginStatus());
        assertEquals(Forge1201MixinAnalyzer.ResourceStatus.PRESENT_VALID_JSON, configFinding.refmapStatus());

        assertEquals(2, result.mixinClasses().size());
        assertTrue(result.mixinClasses().stream().anyMatch(finding ->
                finding.mixinClassName().equals("com.example.mixins.TestMixin")
                        && finding.status() == Forge1201MixinAnalyzer.MixinClassStatus.PRESENT
                        && finding.hasMixinAnnotation()));
        assertTrue(result.mixinClasses().stream().anyMatch(finding ->
                finding.mixinClassName().equals("MissingClient")
                        && finding.status() == Forge1201MixinAnalyzer.MixinClassStatus.MISSING));

        assertEquals(2, result.targets().size());
        assertTrue(result.targets().stream().allMatch(target ->
                target.status() == Forge1201MixinAnalyzer.TargetStatus.TARGET_CLASS_PRESENT
                        && target.pluginDependent()));
        assertTrue(result.targets().stream().anyMatch(target ->
                target.selectorForm() == Forge1201MixinAnalyzer.TargetSelectorForm.CLASS_LITERAL));
        assertTrue(result.targets().stream().anyMatch(target ->
                target.selectorForm() == Forge1201MixinAnalyzer.TargetSelectorForm.STRING_TARGET));
        assertEquals("synthetic-mc-class-map-v1", result.targets().getFirst().mappingVersion());
        assertFalse(targetRequests.contains("com/example/Plugin"));
    }

    @Test
    void reportsUnsafeMissingAndMalformedConfigsWithoutSilentlySkippingThem() throws Exception {
        Path jar = writeJar("config-errors.jar", Map.of(
                "broken.json", "{ not valid json".getBytes(StandardCharsets.UTF_8)));

        var result = Forge1201MixinAnalyzer.analyze(
                jar,
                List.of(
                        new Forge1201MixinAnalyzer.ConfigDeclaration(
                                "missing.json", Forge1201MixinAnalyzer.DiscoverySource.MANIFEST),
                        new Forge1201MixinAnalyzer.ConfigDeclaration(
                                "../outside.json", Forge1201MixinAnalyzer.DiscoverySource.MANIFEST),
                        new Forge1201MixinAnalyzer.ConfigDeclaration(
                                "broken.json", Forge1201MixinAnalyzer.DiscoverySource.RESOURCE_HEURISTIC)),
                emptyTargetIndex(),
                Map.of(),
                "mapping-v1");

        assertEquals(3, result.configs().size());
        assertEquals(Forge1201MixinAnalyzer.ConfigStatus.CONFIG_MISSING,
                config(result, "missing.json").status());
        assertEquals(Forge1201MixinAnalyzer.ConfigStatus.UNSAFE_CONFIG_PATH,
                config(result, "../outside.json").status());
        assertEquals(Forge1201MixinAnalyzer.ConfigStatus.CONFIG_INVALID,
                config(result, "broken.json").status());
    }

    @Test
    void reportsMissingMixinClassAndDoesNotAssumeMinecraftIdentityMapping() throws Exception {
        String configPath = "mixins.missing.json";
        String config = """
                {"package":"com.example.mixins","mixins":["NotThere"]}
                """;
        Map<String, byte[]> archive = Map.of(configPath, config.getBytes(StandardCharsets.UTF_8));
        Path jar = writeJar("missing-class.jar", archive);

        var result = Forge1201MixinAnalyzer.analyze(
                jar,
                List.of(new Forge1201MixinAnalyzer.ConfigDeclaration(
                        configPath, Forge1201MixinAnalyzer.DiscoverySource.MANIFEST)),
                emptyTargetIndex(),
                Map.of(),
                "mapping-not-provided");

        assertEquals(Forge1201MixinAnalyzer.MixinClassStatus.MISSING,
                result.mixinClasses().getFirst().status());
        assertTrue(result.targets().isEmpty());
        assertEquals(Forge1201MixinAnalyzer.ConfigStatus.PARSED, result.configs().getFirst().status());
    }

    @Test
    void leavesUnmappedMinecraftTargetsUnresolvedAndHonorsRemapFalse() throws Exception {
        String configPath = "mixins.targets.json";
        Path jar = writeJar("unresolved-targets.jar", Map.of(
                configPath, """
                        {"package":"com.example.mixins","mixins":["UnmappedMixin"]}
                        """.getBytes(StandardCharsets.UTF_8),
                "com/example/mixins/UnmappedMixin.class",
                mixinClass("com/example/mixins/UnmappedMixin", SOURCE_ENTITY, null, false)));

        var result = Forge1201MixinAnalyzer.analyze(
                jar,
                List.of(new Forge1201MixinAnalyzer.ConfigDeclaration(
                        configPath, Forge1201MixinAnalyzer.DiscoverySource.MANIFEST)),
                emptyTargetIndex(),
                Map.of(SOURCE_ENTITY, SOURCE_ENTITY),
                "synthetic-mc-class-map-v1");

        assertEquals(1, result.targets().size());
        var target = result.targets().getFirst();
        assertFalse(target.remap());
        assertNull(target.targetInternalName());
        assertEquals(Forge1201MixinAnalyzer.TargetStatus.TARGET_REMAP_UNRESOLVED, target.status());
    }

    @Test
    void distinguishesMappedMissingTargetClassAndRefmapAndPluginAbsence() throws Exception {
        String configPath = "mixins.target-missing.json";
        Path jar = writeJar("missing-target.jar", Map.of(
                configPath, """
                        {
                          "package":"com.example.mixins",
                          "plugin":"com.example.NotInArchive",
                          "refmap":"absent.refmap.json",
                          "mixins":["MissingTargetMixin"]
                        }
                        """.getBytes(StandardCharsets.UTF_8),
                "com/example/mixins/MissingTargetMixin.class",
                mixinClass("com/example/mixins/MissingTargetMixin", SOURCE_ENTITY, null, true)));

        var result = Forge1201MixinAnalyzer.analyze(
                jar,
                List.of(new Forge1201MixinAnalyzer.ConfigDeclaration(
                        configPath, Forge1201MixinAnalyzer.DiscoverySource.MANIFEST)),
                emptyTargetIndex(),
                Map.of(SOURCE_ENTITY, SOURCE_ENTITY),
                "synthetic-mc-class-map-v1");

        assertEquals(Forge1201MixinAnalyzer.PluginStatus.MISSING_FROM_SOURCE_ARCHIVE,
                result.configs().getFirst().pluginStatus());
        assertEquals(Forge1201MixinAnalyzer.ResourceStatus.MISSING,
                result.configs().getFirst().refmapStatus());
        assertEquals(Forge1201MixinAnalyzer.TargetStatus.TARGET_CLASS_MISSING,
                result.targets().getFirst().status());
    }

    private Forge1201MixinAnalyzer.ConfigFinding config(
            Forge1201MixinAnalyzer.Result result, String path) {
        return result.configs().stream().filter(finding -> finding.configPath().equals(path))
                .findFirst().orElseThrow();
    }

    private Path writeJar(String name, Map<String, byte[]> entries) throws Exception {
        Path path = tempDir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private static byte[] plainClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] mixinClass(
            String internalName, String classLiteralTarget, String stringTarget, boolean remap) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        if (classLiteralTarget != null) {
            AnnotationVisitor annotation = writer.visitAnnotation(MIXIN_DESC, true);
            AnnotationVisitor values = annotation.visitArray("value");
            values.visit(null, Type.getObjectType(classLiteralTarget));
            values.visitEnd();
            annotation.visit("remap", remap);
            annotation.visitEnd();
        }
        if (stringTarget != null) {
            AnnotationVisitor annotation = writer.visitAnnotation(MIXIN_DESC, false);
            AnnotationVisitor targets = annotation.visitArray("targets");
            targets.visit(null, stringTarget.replace('/', '.'));
            targets.visitEnd();
            annotation.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Forge1201TargetApiVerifier.TargetIndex emptyTargetIndex() {
        return Forge1201TargetApiVerifier.createIndex(ignored -> null);
    }
}
