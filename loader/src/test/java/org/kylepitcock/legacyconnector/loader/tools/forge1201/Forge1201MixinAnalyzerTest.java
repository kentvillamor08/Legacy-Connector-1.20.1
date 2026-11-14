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
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String MODIFY_ARG_DESC = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    private static final String MODIFY_ARGS_DESC = "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;";
    private static final String MODIFY_VARIABLE_DESC = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
    private static final String MODIFY_CONSTANT_DESC = "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;";
    private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String SLICE_DESC = "Lorg/spongepowered/asm/mixin/injection/Slice;";
    private static final String DESC_DESC = "Lorg/spongepowered/asm/mixin/injection/Desc;";
    private static final String CONSTANT_DESC = "Lorg/spongepowered/asm/mixin/injection/Constant;";
    private static final String CONDITION_DESC = "Lorg/spongepowered/asm/mixin/injection/Constant$Condition;";
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

        assertEquals(11, result.memberAnnotations().size());
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
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.INVALID_ANNOTATION_VALUE,
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

        var constructorInvoker = result.memberAnnotations().stream()
                .filter(finding -> finding.sourceMemberName().equals("newEntity"))
                .findFirst().orElseThrow();
        assertEquals("CONSTRUCTOR", constructorInvoker.operation());
        assertEquals(List.of("<init>"), constructorInvoker.targetMemberNameCandidates());

        var unresolvedAccessor = result.memberAnnotations().stream()
                .filter(finding -> finding.sourceMemberName().equals("fetchHealth"))
                .findFirst().orElseThrow();
        assertTrue(unresolvedAccessor.targetMemberNameCandidates().isEmpty());
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.INVALID_MEMBER_SELECTOR,
                unresolvedAccessor.status());

        var lowerCaseAccessor = result.memberAnnotations().stream()
                .filter(finding -> finding.sourceMemberName().equals("gethealth"))
                .findFirst().orElseThrow();
        assertTrue(lowerCaseAccessor.targetMemberNameCandidates().isEmpty());
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.INVALID_MEMBER_SELECTOR,
                lowerCaseAccessor.status());

        var unsupportedAccessor = result.memberAnnotations().stream()
                .filter(finding -> finding.sourceMemberName().equals("getHealthWithArgs"))
                .findFirst().orElseThrow();
        assertEquals("UNSUPPORTED_SHAPE", unsupportedAccessor.operation());
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.INVALID_ANNOTATION_VALUE,
                unsupportedAccessor.status());

        var concreteAccessor = result.memberAnnotations().stream()
                .filter(finding -> finding.sourceMemberName().equals("readHealthConcrete"))
                .findFirst().orElseThrow();
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.INVALID_ANNOTATION_VALUE,
                concreteAccessor.status());
        assertTrue(concreteAccessor.warnings().stream().anyMatch(warning -> warning.contains("must be abstract")));
    }


    @Test
    void inventoriesCoreInjectorHandlersAndDescriptorAwareTargetSelectors() throws Exception {
        String configPath = "mixins.injectors.json";
        Path jar = writeJar("injector-annotations.jar", Map.of(
                configPath, """
                        {"package":"com.example.mixins","mixins":["InjectorMixin"]}
                        """.getBytes(StandardCharsets.UTF_8),
                "com/example/mixins/InjectorMixin.class",
                injectorMixinClass("com/example/mixins/InjectorMixin", SOURCE_ENTITY)));

        var result = Forge1201MixinAnalyzer.analyze(jar,
                List.of(new Forge1201MixinAnalyzer.ConfigDeclaration(
                        configPath, Forge1201MixinAnalyzer.DiscoverySource.MANIFEST)),
                emptyTargetIndex(), Map.of(), "no-member-mapping-supplied");

        assertEquals(9, result.injectors().size());
        assertTrue(result.injectors().stream().allMatch(finding ->
                finding.configPath().equals(configPath)
                        && finding.mixinClassName().equals("com.example.mixins.InjectorMixin")
                        && finding.sourceTargetInternalNames().equals(List.of(SOURCE_ENTITY))));

        var inject = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("onTick")).findFirst().orElseThrow();
        assertEquals(Forge1201MixinAnalyzer.InjectorAnnotationKind.INJECT, inject.annotationKind());
        assertEquals("(L" + SOURCE_ENTITY + ";I)V", inject.sourceMemberDescriptor());
        assertEquals(2, inject.targetSelectors().size());
        assertEquals("tick", inject.targetSelectors().getFirst().memberName());
        assertEquals("()V", inject.targetSelectors().getFirst().descriptor());
        assertEquals("hurt", inject.targetSelectors().get(1).memberName());
        assertEquals(SOURCE_ENTITY, inject.targetSelectors().get(1).ownerInternalName());
        assertEquals("(D)Z", inject.targetSelectors().get(1).descriptor());
        assertEquals(1, inject.atAnnotationCount());
        assertEquals(1, inject.sliceAnnotationCount());
        assertEquals(1, inject.require());
        assertEquals(Boolean.TRUE, inject.cancellable());
        assertFalse(inject.remap());
        assertEquals(Forge1201MixinAnalyzer.InjectorResolutionStatus.INJECTOR_REMAP_DISABLED_UNVERIFIED,
                inject.status());

        var descHandler = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("redirectHurtDesc")).findFirst().orElseThrow();
        assertEquals(Forge1201MixinAnalyzer.InjectorAnnotationKind.REDIRECT, descHandler.annotationKind());
        var descSelector = descHandler.targetSelectors().stream()
                .filter(selector -> selector.source() == Forge1201MixinAnalyzer.InjectorSelectorSource.DESC_ANNOTATION)
                .findFirst().orElseThrow();
        assertEquals(SOURCE_ENTITY, descSelector.ownerInternalName());
        assertEquals("hurt", descSelector.memberName());
        assertEquals("(D)Z", descSelector.descriptor());
        assertEquals(1, descSelector.minimumMatches());
        assertEquals(1, descSelector.maximumMatches());

        var overloaded = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("modifyOverload")).findFirst().orElseThrow();
        assertEquals(List.of("()V", "(I)V"), overloaded.targetSelectors().stream()
                .map(Forge1201MixinAnalyzer.InjectorTargetSelector::descriptor).toList());

        var wildcard = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("modifyWildcard")).findFirst().orElseThrow();
        assertEquals("tick", wildcard.targetSelectors().getFirst().memberName());
        assertEquals("ANY", wildcard.targetSelectors().getFirst().quantifier());
        assertEquals(2, wildcard.index());
        assertEquals(Boolean.TRUE, wildcard.argsOnly());

        var malformed = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("badSelector")).findFirst().orElseThrow();
        assertEquals(Forge1201MixinAnalyzer.InjectorResolutionStatus.INVALID_INJECTOR_SELECTOR,
                malformed.status());

        var field = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("invalidInjectorField")).findFirst().orElseThrow();
        assertEquals(Forge1201MixinAnalyzer.MemberElementKind.FIELD, field.elementKind());
        assertEquals(Forge1201MixinAnalyzer.InjectorResolutionStatus.INVALID_ANNOTATION_PLACEMENT,
                field.status());

        var modifyConstant = result.injectors().stream()
                .filter(f -> f.annotationKind() == Forge1201MixinAnalyzer.InjectorAnnotationKind.MODIFY_CONSTANT)
                .findFirst().orElseThrow();
        assertEquals(1, modifyConstant.sliceAnnotationCount());
        assertTrue(result.injectors().stream().anyMatch(f ->
                f.annotationKind() == Forge1201MixinAnalyzer.InjectorAnnotationKind.MODIFY_ARGS));
    }


    @Test
    void inventoriesDetailedAtSelectorsAndTypedModifyConstantDiscriminators() throws Exception {
        String configPath = "mixins.at-constants.json";
        Path jar = writeJar("at-constants.jar", Map.of(
                configPath, """
                        {"package":"com.example.mixins","mixins":["AtConstantMixin"]}
                        """.getBytes(StandardCharsets.UTF_8),
                "com/example/mixins/AtConstantMixin.class",
                atConstantMixinClass("com/example/mixins/AtConstantMixin", SOURCE_ENTITY)));

        var result = Forge1201MixinAnalyzer.analyze(jar,
                List.of(new Forge1201MixinAnalyzer.ConfigDeclaration(
                        configPath, Forge1201MixinAnalyzer.DiscoverySource.MANIFEST)),
                emptyTargetIndex(), Map.of(), "no-member-mapping-supplied");

        var atHandler = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("atMetadata"))
                .findFirst().orElseThrow();
        assertEquals(3, atHandler.injectionPoints().size());

        var fieldAt = atHandler.injectionPoints().getFirst();
        assertEquals("FIELD", fieldAt.value());
        assertEquals(Forge1201MixinAnalyzer.AtValueKind.BUILT_IN, fieldAt.valueKind());
        assertEquals("L" + SOURCE_ENTITY + ";health:D", fieldAt.rawTarget());
        assertEquals(SOURCE_ENTITY, fieldAt.targetSelector().ownerInternalName());
        assertEquals("health", fieldAt.targetSelector().memberName());
        assertEquals("D", fieldAt.targetSelector().descriptor());
        assertEquals(Forge1201MixinAnalyzer.AtTargetDescriptorKind.FIELD,
                fieldAt.targetSelector().descriptorKind());
        assertEquals(Forge1201MixinAnalyzer.AtTargetSelectorStatus.FIELD_DESCRIPTOR,
                fieldAt.targetSelector().status());
        assertEquals(Opcodes.GETFIELD, fieldAt.opcode());
        assertEquals(2, fieldAt.ordinal());
        assertEquals("BY", fieldAt.shift());
        assertEquals(-1, fieldAt.shiftBy());
        assertEquals("segment", fieldAt.sliceId());
        assertEquals(List.of("array=getter", "audit=fixture"), fieldAt.args());
        assertFalse(fieldAt.remap());
        assertEquals("healthLookup", fieldAt.id());

        var descAt = atHandler.injectionPoints().get(1);
        assertEquals("INVOKE", descAt.value());
        assertEquals(SOURCE_ENTITY, descAt.descSelector().ownerInternalName());
        assertEquals("hurt", descAt.descSelector().memberName());
        assertEquals("(D)Z", descAt.descSelector().descriptor());
        assertEquals(1, descAt.descSelector().minimumMatches());
        assertEquals(1, descAt.descSelector().maximumMatches());

        var headAt = atHandler.injectionPoints().get(2);
        assertEquals("HEAD", headAt.value());
        assertEquals(Forge1201MixinAnalyzer.AtTargetSelectorStatus.NOT_DECLARED,
                headAt.targetSelector().status());
        assertEquals(-1, headAt.opcode());
        assertEquals(-1, headAt.ordinal());
        assertEquals("NONE", headAt.shift());
        assertEquals(0, headAt.shiftBy());
        assertTrue(headAt.remap());

        var constants = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("modifyTypedConstants"))
                .findFirst().orElseThrow().constants();
        assertEquals(7, constants.size());
        assertEquals(Integer.valueOf(7), constants.get(0).intValue());
        assertEquals(3, constants.get(0).ordinal());
        assertEquals("segment", constants.get(0).sliceId());
        assertEquals(List.of("GREATER_THAN_ZERO"), constants.get(0).expandZeroConditions());
        assertEquals(Boolean.TRUE, constants.get(0).log());
        assertEquals(Float.valueOf(1.25F), constants.get(1).floatValue());
        assertEquals(Long.valueOf(9L), constants.get(2).longValue());
        assertEquals(Double.valueOf(2.5D), constants.get(3).doubleValue());
        assertEquals("hello", constants.get(4).stringValue());
        assertEquals("Lcom/example/Thing;", constants.get(5).classValueDescriptor());
        assertEquals(Boolean.TRUE, constants.get(6).nullValue());
        assertTrue(constants.stream().allMatch(constant -> constant.declared()
                && constant.status() == Forge1201MixinAnalyzer.ConstantFindingStatus.EXPLICIT_TYPED_DISCRIMINATOR));

        var implicit = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("modifyImplicitConstant"))
                .findFirst().orElseThrow().constants().getFirst();
        assertFalse(implicit.declared());
        assertEquals(Forge1201MixinAnalyzer.ConstantFindingStatus.MATCH_HANDLER_RETURN_TYPE, implicit.status());

        var conflict = result.injectors().stream()
                .filter(finding -> finding.sourceMemberName().equals("modifyConflictingConstant"))
                .findFirst().orElseThrow().constants().getFirst();
        assertEquals(Forge1201MixinAnalyzer.ConstantFindingStatus.CONFLICTING_DISCRIMINATORS, conflict.status());
    }

    private static byte[] atConstantMixinClass(String internalName, String targetInternalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                internalName, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_DESC, true);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetInternalName));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor atMethod = writer.visitMethod(Opcodes.ACC_PRIVATE, "atMetadata", "()V", null, null);
        AnnotationVisitor inject = atMethod.visitAnnotation(INJECT_DESC, false);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "tick()V");
        methods.visitEnd();

        AnnotationVisitor atArray = inject.visitArray("at");
        AnnotationVisitor fieldAt = atArray.visitAnnotation(null, AT_DESC);
        fieldAt.visit("value", "FIELD");
        fieldAt.visit("target", "L" + targetInternalName + ";health:D");
        fieldAt.visit("opcode", Opcodes.GETFIELD);
        fieldAt.visit("ordinal", 2);
        fieldAt.visitEnum("shift", "Lorg/spongepowered/asm/mixin/injection/At$Shift;", "BY");
        fieldAt.visit("by", -1);
        fieldAt.visit("slice", "segment");
        fieldAt.visit("remap", false);
        fieldAt.visit("id", "healthLookup");
        AnnotationVisitor fieldArgs = fieldAt.visitArray("args");
        fieldArgs.visit(null, "array=getter");
        fieldArgs.visit(null, "audit=fixture");
        fieldArgs.visitEnd();
        fieldAt.visitEnd();

        AnnotationVisitor invokeAt = atArray.visitAnnotation(null, AT_DESC);
        invokeAt.visit("value", "INVOKE");
        AnnotationVisitor atDesc = invokeAt.visitAnnotation("desc", DESC_DESC);
        atDesc.visit("owner", Type.getObjectType(targetInternalName));
        atDesc.visit("value", "hurt");
        atDesc.visit("ret", Type.BOOLEAN_TYPE);
        AnnotationVisitor atDescArgs = atDesc.visitArray("args");
        atDescArgs.visit(null, Type.DOUBLE_TYPE);
        atDescArgs.visitEnd();
        atDesc.visit("min", 1);
        atDesc.visit("max", 1);
        atDesc.visitEnd();
        invokeAt.visitEnd();

        AnnotationVisitor headAt = atArray.visitAnnotation(null, AT_DESC);
        headAt.visit("value", "HEAD");
        headAt.visitEnd();
        atArray.visitEnd();

        AnnotationVisitor slices = inject.visitArray("slice");
        AnnotationVisitor slice = slices.visitAnnotation(null, SLICE_DESC);
        slice.visit("id", "segment");
        slice.visitEnd();
        slices.visitEnd();
        inject.visitEnd();
        atMethod.visitCode();
        atMethod.visitInsn(Opcodes.RETURN);
        atMethod.visitMaxs(0, 0);
        atMethod.visitEnd();

        MethodVisitor constantsMethod = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "modifyTypedConstants", "(I)I", null, null);
        AnnotationVisitor modify = constantsMethod.visitAnnotation(MODIFY_CONSTANT_DESC, false);
        AnnotationVisitor targetMethods = modify.visitArray("method");
        targetMethods.visit(null, "tick()V");
        targetMethods.visitEnd();
        AnnotationVisitor constants = modify.visitArray("constant");

        AnnotationVisitor intConstant = constants.visitAnnotation(null, CONSTANT_DESC);
        intConstant.visit("intValue", 7);
        intConstant.visit("ordinal", 3);
        intConstant.visit("slice", "segment");
        intConstant.visit("log", true);
        AnnotationVisitor zeroConditions = intConstant.visitArray("expandZeroConditions");
        zeroConditions.visitEnum(null, CONDITION_DESC, "GREATER_THAN_ZERO");
        zeroConditions.visitEnd();
        intConstant.visitEnd();

        AnnotationVisitor floatConstant = constants.visitAnnotation(null, CONSTANT_DESC);
        floatConstant.visit("floatValue", 1.25F);
        floatConstant.visitEnd();
        AnnotationVisitor longConstant = constants.visitAnnotation(null, CONSTANT_DESC);
        longConstant.visit("longValue", 9L);
        longConstant.visitEnd();
        AnnotationVisitor doubleConstant = constants.visitAnnotation(null, CONSTANT_DESC);
        doubleConstant.visit("doubleValue", 2.5D);
        doubleConstant.visitEnd();
        AnnotationVisitor stringConstant = constants.visitAnnotation(null, CONSTANT_DESC);
        stringConstant.visit("stringValue", "hello");
        stringConstant.visitEnd();
        AnnotationVisitor classConstant = constants.visitAnnotation(null, CONSTANT_DESC);
        classConstant.visit("classValue", Type.getObjectType("com/example/Thing"));
        classConstant.visitEnd();
        AnnotationVisitor nullConstant = constants.visitAnnotation(null, CONSTANT_DESC);
        nullConstant.visit("nullValue", true);
        nullConstant.visitEnd();
        constants.visitEnd();
        modify.visitEnd();
        emitInjectorMethodBody(constantsMethod, "(I)I");

        MethodVisitor implicitMethod = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "modifyImplicitConstant", "(I)I", null, null);
        AnnotationVisitor implicit = implicitMethod.visitAnnotation(MODIFY_CONSTANT_DESC, false);
        AnnotationVisitor implicitMethods = implicit.visitArray("method");
        implicitMethods.visit(null, "tick()V");
        implicitMethods.visitEnd();
        implicit.visitEnd();
        emitInjectorMethodBody(implicitMethod, "(I)I");

        MethodVisitor conflictMethod = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "modifyConflictingConstant", "(I)I", null, null);
        AnnotationVisitor conflict = conflictMethod.visitAnnotation(MODIFY_CONSTANT_DESC, false);
        AnnotationVisitor conflictMethods = conflict.visitArray("method");
        conflictMethods.visit(null, "tick()V");
        conflictMethods.visitEnd();
        AnnotationVisitor conflictArray = conflict.visitArray("constant");
        AnnotationVisitor conflictConstant = conflictArray.visitAnnotation(null, CONSTANT_DESC);
        conflictConstant.visit("intValue", 2);
        conflictConstant.visit("stringValue", "also-int");
        conflictConstant.visitEnd();
        conflictArray.visitEnd();
        conflict.visitEnd();
        emitInjectorMethodBody(conflictMethod, "(I)I");

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] injectorMixinClass(String internalName, String targetInternalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                internalName, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_DESC, true);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetInternalName));
        targets.visitEnd();
        mixin.visitEnd();

        addInjectorMethod(writer, "onTick", "(L" + targetInternalName + ";I)V", INJECT_DESC,
                new String[] {"tick()V", "L" + targetInternalName + ";hurt(D)Z"},
                false, true, 1, null, true);
        addInjectorMethod(writer, "redirectHurt", "(L" + targetInternalName + ";D)Z", REDIRECT_DESC,
                new String[] {"L" + targetInternalName + ";hurt(D)Z"}, null, true, null, null, false);
        addDescSelectorMethod(writer, "redirectHurtDesc", targetInternalName);
        addInjectorMethod(writer, "modifyOverload", "(I)I", MODIFY_ARG_DESC,
                new String[] {"overloaded()V", "overloaded(I)V"}, null, false, null, 1, false);
        addInjectorMethod(writer, "modifyArgsBundle",
                "(Lorg/spongepowered/asm/mixin/injection/invoke/arg/Args;)V", MODIFY_ARGS_DESC,
                new String[] {"send(I)V"}, null, false, null, null, false);
        addInjectorMethod(writer, "modifyWildcard", "(I)I", MODIFY_VARIABLE_DESC,
                new String[] {"tick*()V"}, null, false, null, 2, false);
        addInjectorMethod(writer, "modifyConstant", "(I)I", MODIFY_CONSTANT_DESC,
                new String[] {"update()V"}, null, true, null, null, false);
        addInjectorMethod(writer, "badSelector", "()V", INJECT_DESC,
                new String[] {"tick(I"}, null, false, null, null, false);

        FieldVisitor field = writer.visitField(Opcodes.ACC_PRIVATE, "invalidInjectorField", "I", null, null);
        AnnotationVisitor invalid = field.visitAnnotation(INJECT_DESC, false);
        AnnotationVisitor methods = invalid.visitArray("method");
        methods.visit(null, "tick()V");
        methods.visitEnd();
        invalid.visitEnd();
        field.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addDescSelectorMethod(ClassWriter writer, String methodName, String ownerInternalName) {
        String handlerDescriptor = "(L" + ownerInternalName + ";D)Z";
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, methodName, handlerDescriptor, null, null);
        AnnotationVisitor annotation = method.visitAnnotation(REDIRECT_DESC, false);
        AnnotationVisitor targetArray = annotation.visitArray("target");
        AnnotationVisitor desc = targetArray.visitAnnotation(null, DESC_DESC);
        desc.visit("owner", Type.getObjectType(ownerInternalName));
        desc.visit("value", "hurt");
        desc.visit("ret", Type.BOOLEAN_TYPE);
        AnnotationVisitor args = desc.visitArray("args");
        args.visit(null, Type.DOUBLE_TYPE);
        args.visitEnd();
        desc.visit("min", 1);
        desc.visit("max", 1);
        desc.visitEnd();
        targetArray.visitEnd();
        AnnotationVisitor at = annotation.visitAnnotation("at", AT_DESC);
        at.visit("value", "INVOKE");
        at.visitEnd();
        annotation.visitEnd();
        emitInjectorMethodBody(method, handlerDescriptor);
    }

    private static void addInjectorMethod(
            ClassWriter writer, String name, String descriptor, String annotationDescriptor,
            String[] selectors, Boolean remap, boolean includeSlice,
            Integer require, Integer index, boolean cancellable) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, name, descriptor, null, null);
        AnnotationVisitor annotation = method.visitAnnotation(annotationDescriptor, false);
        AnnotationVisitor methodSelectors = annotation.visitArray("method");
        for (String selector : selectors) methodSelectors.visit(null, selector);
        methodSelectors.visitEnd();
        if (remap != null) annotation.visit("remap", remap);
        if (require != null) annotation.visit("require", require);
        if (index != null) annotation.visit("index", index);
        if (MODIFY_VARIABLE_DESC.equals(annotationDescriptor) && index != null) {
            annotation.visit("argsOnly", true);
        }
        if (INJECT_DESC.equals(annotationDescriptor)) {
            annotation.visit("cancellable", cancellable);
            AnnotationVisitor atArray = annotation.visitArray("at");
            AnnotationVisitor at = atArray.visitAnnotation(null, AT_DESC);
            at.visit("value", "HEAD");
            at.visitEnd();
            atArray.visitEnd();
        } else if (!MODIFY_CONSTANT_DESC.equals(annotationDescriptor)) {
            AnnotationVisitor at = annotation.visitAnnotation("at", AT_DESC);
            at.visit("value", "HEAD");
            at.visitEnd();
        }
        if (MODIFY_CONSTANT_DESC.equals(annotationDescriptor)) {
            AnnotationVisitor constants = annotation.visitArray("constant");
            AnnotationVisitor constant = constants.visitAnnotation(null, CONSTANT_DESC);
            constant.visit("intValue", 1);
            constant.visitEnd();
            constants.visitEnd();
        }
        if (includeSlice) {
            if (INJECT_DESC.equals(annotationDescriptor) || MODIFY_CONSTANT_DESC.equals(annotationDescriptor)) {
                AnnotationVisitor slices = annotation.visitArray("slice");
                AnnotationVisitor slice = slices.visitAnnotation(null, SLICE_DESC);
                slice.visit("id", "sliceFixture");
                slice.visitEnd();
                slices.visitEnd();
            } else {
                AnnotationVisitor slice = annotation.visitAnnotation("slice", SLICE_DESC);
                slice.visit("id", "sliceFixture");
                slice.visitEnd();
            }
        }
        annotation.visitEnd();
        emitInjectorMethodBody(method, descriptor);
    }

    private static void emitInjectorMethodBody(MethodVisitor method, String descriptor) {
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
            default -> throw new IllegalArgumentException("Unsupported method descriptor: " + descriptor);
        }
        method.visitMaxs(0, 0);
        method.visitEnd();
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
    void pluginDeclaredMixinMembersRemainPluginDependentWithoutExecutingPluginCode() throws Exception {
        String configPath = "mixins.plugin.json";
        Path jar = writeJar("mixin-plugin-members.jar", Map.of(
                configPath, """
                        {
                          "package":"com.example.mixins",
                          "plugin":"com.example.DynamicPlugin",
                          "mixins":["MemberMixin"]
                        }
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

        assertEquals(Forge1201MixinAnalyzer.PluginStatus.MISSING_FROM_SOURCE_ARCHIVE,
                result.configs().getFirst().pluginStatus());
        var overwrite = result.memberAnnotations().stream()
                .filter(finding -> finding.annotationKind() == Forge1201MixinAnalyzer.MemberAnnotationKind.OVERWRITE)
                .findFirst().orElseThrow();
        assertEquals(Forge1201MixinAnalyzer.MemberResolutionStatus.PLUGIN_DEPENDENT, overwrite.status());
        assertTrue(overwrite.evidence().contains("Plugin code was not executed"));
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
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, internalName, null, "java/lang/Object", null);

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
        addAnnotatedMethod(writer, "newEntity", "(I)Lcom/example/Target;", INVOKER_DESC,
                "<init>", null, null, null);
        addAnnotatedMethod(writer, "fetchHealth", "()D", ACCESSOR_DESC, null, null, null, null);
        addAnnotatedMethod(writer, "gethealth", "()D", ACCESSOR_DESC, null, null, null, null);
        addAnnotatedMethod(writer, "getHealthWithArgs", "(II)V", ACCESSOR_DESC, null, null, null, null);
        addAnnotatedMethod(writer, "readHealthConcrete", "()D", ACCESSOR_DESC,
                "health", null, null, null, false);

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
        boolean abstractMethod = ACCESSOR_DESC.equals(annotationDescriptor)
                || INVOKER_DESC.equals(annotationDescriptor);
        addAnnotatedMethod(writer, name, descriptor, annotationDescriptor,
                value, prefix, aliases, remap, abstractMethod);
    }

    private static void addAnnotatedMethod(
            ClassWriter writer,
            String name,
            String descriptor,
            String annotationDescriptor,
            String value,
            String prefix,
            String[] aliases,
            Boolean remap,
            boolean abstractMethod) {
        int access = SHADOW_DESC.equals(annotationDescriptor) && aliases != null && aliases.length > 0
                ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC;
        if (abstractMethod) access |= Opcodes.ACC_ABSTRACT;
        MethodVisitor method = writer.visitMethod(access, name, descriptor, null, null);
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

        if (abstractMethod) {
            method.visitEnd();
            return;
        }

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
