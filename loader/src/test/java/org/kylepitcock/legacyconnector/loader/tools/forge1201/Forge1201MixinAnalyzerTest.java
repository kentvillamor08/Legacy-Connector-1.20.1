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
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class Forge1201MixinAnalyzerTest {
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE_DESC = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
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
    void inventoriesDescriptorAwareMixinMemberAnnotationsWithoutClaimingTargetPresence() throws Exception {
        String configPath = "mixins.members.json";
        Path jar = writeJar("member-annotations.jar", Map.of(
                configPath, """
                        {"package":"com.example.mixins","mixins":["MemberMixin"]}
                        """.getBytes(StandardCharsets.UTF_8),
                "com/example/mixins/MemberMixin.class",
                memberMixinClass("com/example/mixins/MemberMixin", SOURCE_ENTITY)));

        var result = Forge1201MixinAnalyzer.analyze(
                jar,
                List.of(new Forge1201MixinAnalyzer.ConfigDeclaration(
                        configPath, Forge1201MixinAnalyzer.DiscoverySource.MANIFEST)),
                emptyTargetIndex(),
                Map.of(),
                "no-member-mapping-supplied");

        assertEquals(7, result.memberAnnotations().size());
        assertTrue(result.memberAnnotations().stream().allMatch(finding ->
                finding.configPath().equals(configPath)
                        && finding.mixinClassName().equals("com.example.mixins.MemberMixin")
                        && finding.sourceTargetInternalNames().equals(List.of(SOURCE_ENTITY))));

        var shadowField = result.memberAnnotations().stream()
                .filter(finding -> finding.annotationKind() == Forge1201MixinAnalyzer.MemberAnnotationKind.SHADOW
                        && finding.elementKind() == Forge1201MixinAnalyzer.MemberElementKind.FIELD)
                .findFirst().orElseThrow();
        assertEquals("shadow$health", shadowField.sourceMemberName());
        assertEquals("D", shadowField.sourceMemberDescriptor());
        assertNull(shadowField.prefix());
        assertEquals(List.of("healthLegacy"), shadowField.aliases());
        assertEquals(List.of("shadow$health", "healthLegacy"), shadowField.targetMemberNameCandidates());
        assertTrue(shadowField.warnings().stream().anyMatch(warning -> warning.contains("prefixes only apply to shadow methods")));
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.MEMBER_REMAP_UNRESOLVED,
                shadowField.status());

        var shadowMethod = result.memberAnnotations().stream()
                .filter(finding -> finding.annotationKind() == Forge1201MixinAnalyzer.MemberAnnotationKind.SHADOW
                        && finding.elementKind() == Forge1201MixinAnalyzer.MemberElementKind.METHOD)
                .findFirst().orElseThrow();
        assertEquals("(I)V", shadowMethod.sourceMemberDescriptor());
        assertEquals(List.of("heal", "healLegacy"), shadowMethod.targetMemberNameCandidates());

        var overwrite = result.memberAnnotations().stream()
                .filter(finding -> finding.annotationKind() == Forge1201MixinAnalyzer.MemberAnnotationKind.OVERWRITE)
                .findFirst().orElseThrow();
        assertEquals("tick", overwrite.sourceMemberName());
        assertEquals("(I)V", overwrite.sourceMemberDescriptor());
        assertEquals(List.of("tick", "oldTick"), overwrite.targetMemberNameCandidates());
        assertEquals("@env(DEFAULT)", overwrite.constraints());
        assertFalse(overwrite.remap());
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.MEMBER_REMAP_DISABLED_UNVERIFIED,
                overwrite.status());

        var getter = result.memberAnnotations().stream()
                .filter(finding -> finding.annotationKind() == Forge1201MixinAnalyzer.MemberAnnotationKind.ACCESSOR
                        && finding.sourceMemberName().equals("getHealth"))
                .findFirst().orElseThrow();
        assertEquals("()D", getter.sourceMemberDescriptor());
        assertEquals("GETTER", getter.operation());
        assertEquals(List.of("health"), getter.targetMemberNameCandidates());

        var setter = result.memberAnnotations().stream()
                .filter(finding -> finding.annotationKind() == Forge1201MixinAnalyzer.MemberAnnotationKind.ACCESSOR
                        && finding.sourceMemberName().equals("setHealth"))
                .findFirst().orElseThrow();
        assertEquals("(D)V", setter.sourceMemberDescriptor());
        assertEquals("SETTER", setter.operation());
        assertEquals("health", setter.explicitValue());

        var invoker = result.memberAnnotations().stream()
                .filter(finding -> finding.annotationKind() == Forge1201MixinAnalyzer.MemberAnnotationKind.INVOKER)
                .findFirst().orElseThrow();
        assertEquals("callHeal", invoker.sourceMemberName());
        assertEquals("(I)V", invoker.sourceMemberDescriptor());
        assertEquals("METHOD", invoker.operation());
        assertEquals(List.of("heal"), invoker.targetMemberNameCandidates());

        var unresolvedAccessor = result.memberAnnotations().stream()
                .filter(finding -> finding.sourceMemberName().equals("fetchHealth"))
                .findFirst().orElseThrow();
        assertTrue(unresolvedAccessor.targetMemberNameCandidates().isEmpty());
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.INVALID_MEMBER_SELECTOR,
                unresolvedAccessor.status());
    }


    @Test
    void memberRemapDefaultsToMixinRemapButCanBeExplicitlyReenabled() throws Exception {
        String configPath = "mixins.remap.json";
        Path jar = writeJar("mixin-remap-defaults.jar", Map.of(
                configPath, """
                        {"package":"com.example.mixins","mixins":["RemapMixin"]}
                        """.getBytes(StandardCharsets.UTF_8),
                "com/example/mixins/RemapMixin.class",
                mixinRemapClass("com/example/mixins/RemapMixin")));

        var result = Forge1201MixinAnalyzer.analyze(
                jar,
                List.of(new Forge1201MixinAnalyzer.ConfigDeclaration(
                        configPath, Forge1201MixinAnalyzer.DiscoverySource.MANIFEST)),
                emptyTargetIndex(),
                Map.of(),
                "no-member-mapping-supplied");

        assertEquals(2, result.memberAnnotations().size());
        var inheritedFalse = result.memberAnnotations().stream()
                .filter(finding -> finding.sourceMemberName().equals("tick"))
                .findFirst().orElseThrow();
        assertFalse(inheritedFalse.remap());
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.MEMBER_REMAP_DISABLED_UNVERIFIED,
                inheritedFalse.status());

        var explicitTrue = result.memberAnnotations().stream()
                .filter(finding -> finding.sourceMemberName().equals("forceRemap"))
                .findFirst().orElseThrow();
        assertTrue(explicitTrue.remap());
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.MEMBER_REMAP_UNRESOLVED,
                explicitTrue.status());
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
    void reportsTargetIndexUnavailableInsteadOfCallingMappedClassesMissing() throws Exception {
        String configPath = "mixins.index-unavailable.json";
        Path jar = writeJar("index-unavailable.jar", Map.of(
                configPath, """
                        {"package":"com.example.mixins","mixins":["UnverifiedMixin"]}
                        """.getBytes(StandardCharsets.UTF_8),
                "com/example/mixins/UnverifiedMixin.class",
                mixinClass("com/example/mixins/UnverifiedMixin", SOURCE_ENTITY, null, true)));

        var result = Forge1201MixinAnalyzer.analyze(
                jar,
                List.of(new Forge1201MixinAnalyzer.ConfigDeclaration(
                        configPath, Forge1201MixinAnalyzer.DiscoverySource.MANIFEST)),
                null,
                Map.of(SOURCE_ENTITY, SOURCE_ENTITY),
                "synthetic-mc-class-map-v1");

        assertEquals(Forge1201MixinAnalyzer.TargetIndexStatus.UNAVAILABLE_NO_TARGET_CLASSPATH,
                result.targetIndexStatus());
        assertEquals(Forge1201MixinAnalyzer.TargetStatus.TARGET_INDEX_UNAVAILABLE,
                result.targets().getFirst().status());
        assertEquals(SOURCE_ENTITY, result.targets().getFirst().targetInternalName());
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



    private static byte[] mixinRemapClass(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_DESC, true);
        AnnotationVisitor targets = mixin.visitArray("targets");
        targets.visit(null, "net.minecraft.world.entity.Entity");
        targets.visitEnd();
        mixin.visit("remap", false);
        mixin.visitEnd();

        addAnnotatedMethod(writer, "tick", "()V", OVERWRITE_DESC, null, null, null, null);
        addAnnotatedMethod(writer, "forceRemap", "()V", OVERWRITE_DESC, null, null, null, true);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] memberMixinClass(String internalName, String targetInternalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_DESC, true);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetInternalName));
        targets.visitEnd();
        mixin.visitEnd();

        FieldVisitor shadowField = writer.visitField(Opcodes.ACC_PRIVATE, "shadow$health", "D", null, null);
        AnnotationVisitor shadowFieldAnnotation = shadowField.visitAnnotation(SHADOW_DESC, false);
        shadowFieldAnnotation.visit("prefix", "shadow$");
        AnnotationVisitor fieldAliases = shadowFieldAnnotation.visitArray("aliases");
        fieldAliases.visit(null, "healthLegacy");
        fieldAliases.visitEnd();
        shadowFieldAnnotation.visitEnd();
        shadowField.visitEnd();

        addAnnotatedMethod(writer, "shadow$heal", "(I)V", SHADOW_DESC,
                null, "shadow$", new String[] {"healLegacy"}, null);
        addAnnotatedMethod(writer, "tick", "(I)V", OVERWRITE_DESC,
                null, null, new String[] {"oldTick"}, false);
        addAnnotatedMethod(writer, "getHealth", "()D", ACCESSOR_DESC, null, null, null, null);
        addAnnotatedMethod(writer, "setHealth", "(D)V", ACCESSOR_DESC, "health", null, null, null);
        addAnnotatedMethod(writer, "callHeal", "(I)V", INVOKER_DESC, null, null, null, null);
        addAnnotatedMethod(writer, "fetchHealth", "()D", ACCESSOR_DESC, null, null, null, null);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addAnnotatedMethod(
            ClassWriter writer,
            String name,
            String descriptor,
            String annotationDescriptor,
            String value,
            String prefix,
            String[] aliases,
            Boolean remap) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        AnnotationVisitor annotation = method.visitAnnotation(annotationDescriptor, false);
        if (value != null) annotation.visit("value", value);
        if (prefix != null) annotation.visit("prefix", prefix);
        if (remap != null) annotation.visit("remap", remap);
        if (aliases != null) {
            AnnotationVisitor aliasValues = annotation.visitArray("aliases");
            for (String alias : aliases) aliasValues.visit(null, alias);
            aliasValues.visitEnd();
        }
        if (OVERWRITE_DESC.equals(annotationDescriptor)) {
            annotation.visit("constraints", "@env(DEFAULT)");
        }
        annotation.visitEnd();

        method.visitCode();
        switch (Type.getReturnType(descriptor).getSort()) {
            case Type.VOID -> method.visitInsn(Opcodes.RETURN);
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                method.visitInsn(Opcodes.ICONST_0);
                method.visitInsn(Opcodes.IRETURN);
            }
            case Type.LONG -> {
                method.visitInsn(Opcodes.LCONST_0);
                method.visitInsn(Opcodes.LRETURN);
            }
            case Type.FLOAT -> {
                method.visitInsn(Opcodes.FCONST_0);
                method.visitInsn(Opcodes.FRETURN);
            }
            case Type.DOUBLE -> {
                method.visitInsn(Opcodes.DCONST_0);
                method.visitInsn(Opcodes.DRETURN);
            }
            case Type.ARRAY, Type.OBJECT -> {
                method.visitInsn(Opcodes.ACONST_NULL);
                method.visitInsn(Opcodes.ARETURN);
            }
            default -> throw new IllegalArgumentException("Unsupported test method descriptor: " + descriptor);
        }
        method.visitMaxs(0, 0);
        method.visitEnd();
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
