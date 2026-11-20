package org.kylepitcock.legacyconnector.loader.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Regression coverage for the exact parent/child cancellation-result call rewrites. */
class ForgeApiRewrites1201Test {
    private static final String EVENT =
            "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent";
    private static final String INTERACTION_RESULT = "net/minecraft/world/InteractionResult";
    private static final String GET_DESC = "()L" + INTERACTION_RESULT + ";";
    private static final String SET_DESC = "(L" + INTERACTION_RESULT + ";)V";

    private record Expected(String owner, String name, String descriptor, String helperDescriptor) {}

    private static void addCall(ClassNode node, Expected expected, int ordinal) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "call" + ordinal,
                "()V",
                null,
                null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        boolean getter = expected.name().startsWith("get");
        if (!getter) {
            method.instructions.add(new InsnNode(expected.descriptor().equals("(I)V")
                    ? Opcodes.ICONST_1 : Opcodes.ACONST_NULL));
        }
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                expected.owner(),
                expected.name(),
                expected.descriptor(),
                false));
        if (getter) method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 0;
        node.methods.add(method);
    }


    @Test
    void saplingFeatureMethodsRewriteToTailAdapters() {
        String owner = "net/neoforged/neoforge/event/level/BlockGrowFeatureEvent";
        String randomSource = "net/minecraft/util/RandomSource";
        String holder = "net/minecraft/core/Holder";
        String resourceKey = "net/minecraft/resources/ResourceKey";
        List<Expected> expected = List.of(
                new Expected(owner, "getRandomSource", "()L" + randomSource + ";",
                        "(L" + owner + ";)L" + randomSource + ";"),
                new Expected(owner, "setFeature", "(L" + holder + ";)V",
                        "(L" + owner + ";L" + holder + ";)V"),
                new Expected(owner, "setFeature", "(L" + resourceKey + ";)V",
                        "(L" + owner + ";L" + resourceKey + ";)V")
        );

        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.name = "test/SaplingFeatureCalls";
        node.superName = "java/lang/Object";
        for (int i = 0; i < expected.size(); i++) addCall(node, expected.get(i), i);

        assertEquals(expected.size(), ForgeApiRewrites1201.apply(node));

        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            }
        }
        assertEquals(expected.size(), calls.size());
        List<String> helperNames = List.of("blockGrowGetRandom", "blockGrowSetFeature", "blockGrowSetFeatureKey");
        for (int i = 0; i < expected.size(); i++) {
            MethodInsnNode call = calls.get(i);
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
            assertEquals(ForgeApiRewrites1201.TAIL, call.owner);
            assertEquals(helperNames.get(i), call.name);
            assertEquals(expected.get(i).helperDescriptor(), call.desc);
        }
    }

    @Test
    void legacyPackSizeAccessorsRewriteToTailAdapters() {
        String owner = "net/neoforged/neoforge/event/entity/living/SpawnClusterSizeEvent";
        List<Expected> expected = List.of(
                new Expected(owner, "getMaxPackSize", "()I", "(L" + owner + ";)I"),
                new Expected(owner, "setMaxPackSize", "(I)V", "(L" + owner + ";I)V")
        );

        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.name = "test/LegacyPackSizeCalls";
        node.superName = "java/lang/Object";
        for (int i = 0; i < expected.size(); i++) addCall(node, expected.get(i), i);

        assertEquals(expected.size(), ForgeApiRewrites1201.apply(node));
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            }
        }
        assertEquals(expected.size(), calls.size());
        List<String> helperNames = List.of("livingPackSizeGetMax", "livingPackSizeSetMax");
        for (int i = 0; i < expected.size(); i++) {
            MethodInsnNode call = calls.get(i);
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
            assertEquals(ForgeApiRewrites1201.TAIL, call.owner);
            assertEquals(helperNames.get(i), call.name);
            assertEquals(expected.get(i).helperDescriptor(), call.desc);
        }
    }

    @Test
    void parentAndFieldlessChildCancellationAccessorsRewriteToTypedTailOverloads() {
        List<Expected> expected = List.of(
                new Expected(EVENT, "getCancellationResult", GET_DESC,
                        "(L" + EVENT + ";)L" + INTERACTION_RESULT + ";"),
                new Expected(EVENT + "$LeftClickBlock", "getCancellationResult", GET_DESC,
                        "(L" + EVENT + "$LeftClickBlock;)L" + INTERACTION_RESULT + ";"),
                new Expected(EVENT + "$RightClickEmpty", "getCancellationResult", GET_DESC,
                        "(L" + EVENT + "$RightClickEmpty;)L" + INTERACTION_RESULT + ";"),
                new Expected(EVENT + "$LeftClickEmpty", "getCancellationResult", GET_DESC,
                        "(L" + EVENT + "$LeftClickEmpty;)L" + INTERACTION_RESULT + ";"),
                new Expected(EVENT + "$LeftClickEmpty", "setCancellationResult", SET_DESC,
                        "(L" + EVENT + "$LeftClickEmpty;L" + INTERACTION_RESULT + ";)V"),
                new Expected(EVENT, "setCancellationResult", SET_DESC,
                        "(L" + EVENT + ";L" + INTERACTION_RESULT + ";)V"),
                new Expected(EVENT + "$LeftClickBlock", "setCancellationResult", SET_DESC,
                        "(L" + EVENT + "$LeftClickBlock;L" + INTERACTION_RESULT + ";)V"),
                new Expected(EVENT + "$RightClickEmpty", "setCancellationResult", SET_DESC,
                        "(L" + EVENT + "$RightClickEmpty;L" + INTERACTION_RESULT + ";)V")
        );

        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.name = "test/InteractionCancellationResultCalls";
        node.superName = "java/lang/Object";
        for (int i = 0; i < expected.size(); i++) addCall(node, expected.get(i), i);

        assertEquals(expected.size(), ForgeApiRewrites1201.apply(node));

        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            }
        }
        assertEquals(expected.size(), calls.size());
        for (int i = 0; i < expected.size(); i++) {
            MethodInsnNode call = calls.get(i);
            Expected mapping = expected.get(i);
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), mapping.name());
            assertEquals(ForgeApiRewrites1201.TAIL, call.owner, mapping.name());
            assertEquals(mapping.name().equals("getCancellationResult")
                    ? "interactGetCancellationResult" : "interactSetCancellationResult", call.name);
            assertEquals(mapping.helperDescriptor(), call.desc, mapping.name());
        }
    }
}
