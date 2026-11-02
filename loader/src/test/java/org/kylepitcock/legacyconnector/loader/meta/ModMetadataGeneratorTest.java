package org.kylepitcock.legacyconnector.loader.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.kylepitcock.legacyconnector.loader.LegacyJar;
import org.kylepitcock.legacyconnector.loader.LegacyModDecl;
import org.kylepitcock.legacyconnector.loader.LegacyVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

final class ModMetadataGeneratorTest {
    private static LegacyJar sampleJar() {
        LegacyJar jar = new LegacyJar(Path.of("LegacyTest-1.0.jar"));
        jar.version = LegacyVersion.V1_7_10;

        LegacyModDecl mod = new LegacyModDecl();
        mod.legacyId = "Test|Core";
        mod.mainClass = "example/test/TestMod";
        mod.name = "Test Mod";
        mod.version = "1.0";
        mod.description = "A generated metadata fixture";
        jar.mods.add(mod);
        return jar;
    }

    @Test
    void generatesForgeModsToml() {
        String toml = new String(ModMetadataGenerator.modsToml(sampleJar()), StandardCharsets.UTF_8);

        assertTrue(toml.contains("modLoader=\"javafml\""));
        assertTrue(toml.contains("loaderVersion=\"[47,)\""));
        assertTrue(toml.contains("[[mods]]"));
        assertTrue(toml.contains("modId=\"test_core\""));
        assertTrue(toml.contains("[[dependencies.test_core]]"));
        assertTrue(toml.contains("modId=\"forge\""));
        assertTrue(toml.contains("mandatory=true"));
        assertTrue(toml.contains("versionRange=\"[47.3.0,)\""));
        assertTrue(toml.contains("modId=\"minecraft\""));
        assertTrue(toml.contains("versionRange=\"[1.20.1,1.20.2)\""));
        assertFalse(toml.contains("neoforge"));
        assertFalse(toml.contains("type=\"required\""));
    }

    @Test
    void generatesForgeModEntrypointUsingForgeLoadingContext() {
        byte[] bytecode = ModMetadataGenerator.entrypointClass(sampleJar(), sampleJar().mods.get(0));
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, 0);

        assertEquals(Opcodes.V17, node.version);
        assertTrue(node.visibleAnnotations.stream()
                .anyMatch(annotation -> annotation.desc.equals("Lnet/minecraftforge/fml/common/Mod;")));

        MethodNode constructor = node.methods.stream()
                .filter(method -> method.name.equals("<init>"))
                .findFirst()
                .orElseThrow();

        assertEquals("(Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;)V", constructor.desc);

        List<MethodInsnNode> calls = constructor.instructions.iterator().hasNext()
                ? methodCalls(constructor)
                : List.of();

        assertTrue(calls.stream().anyMatch(call ->
                call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext")
                        && call.name.equals("getModEventBus")
                        && call.desc.equals("()Lnet/minecraftforge/eventbus/api/IEventBus;")));

        assertTrue(calls.stream().anyMatch(call ->
                call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext")
                        && call.name.equals("getContainer")
                        && call.desc.equals("()Lnet/minecraftforge/fml/javafmlmod/FMLModContainer;")));

        assertTrue(calls.stream().anyMatch(call ->
                call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(ModMetadataGenerator.BOOTSTRAP)
                        && call.name.equals("construct")
                        && call.desc.equals("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                                + "Lnet/minecraftforge/eventbus/api/IEventBus;"
                                + "Lnet/minecraftforge/fml/ModContainer;)V")));
    }

    private static List<MethodInsnNode> methodCalls(MethodNode method) {
        java.util.ArrayList<MethodInsnNode> calls = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }
}
