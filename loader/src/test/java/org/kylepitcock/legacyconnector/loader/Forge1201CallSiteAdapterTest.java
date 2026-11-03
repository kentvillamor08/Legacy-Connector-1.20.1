package org.kylepitcock.legacyconnector.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

final class Forge1201CallSiteAdapterTest {
    private static final String EVENT = "net/minecraftforge/event/entity/EntityEvent$Size";
    private static final String DIMENSIONS = "net/minecraft/world/entity/EntityDimensions";
    private static final String DIMENSIONS_DESC = "L" + DIMENSIONS + ";";

    @TempDir
    Path tempDir;

    @Test
    void loadsProfileAndAppliesExactFieldMethodAndCheckcastRules() throws Exception {
        Path profileFile = writeProfile("""
                {
                  "schemaVersion": 1,
                  "id": "synthetic-descriptor-profile",
                  "rules": [{
                    "className": "sample/AdapterFixture",
                    "methodName": "adapt",
                    "methodDescriptor": "()V",
                    "operations": [
                      {
                        "type": "field-descriptor",
                        "owner": "sample/Attributes",
                        "name": "DAMAGE",
                        "descriptor": "Lsample/Attribute;",
                        "replacementDescriptor": "Lsample/Holder;",
                        "opcode": "GETSTATIC",
                        "expectedMatches": 1
                      },
                      {
                        "type": "method-descriptor",
                        "owner": "sample/Event",
                        "name": "has",
                        "descriptor": "(Lsample/Attribute;)Z",
                        "replacementDescriptor": "(Lsample/Holder;)Z",
                        "opcode": "INVOKEVIRTUAL",
                        "expectedMatches": 1
                      },
                      {
                        "type": "checkcast-type",
                        "sourceType": "java/lang/String",
                        "replacementType": "sample/SkinModel",
                        "expectedMatches": 1
                      }
                    ]
                  }]
                }
                """);

        Forge1201CallSiteAdapter.Profile profile = Forge1201CallSiteAdapter.load(profileFile);
        Forge1201CallSiteAdapter.Session session = new Forge1201CallSiteAdapter.Session(profile);
        ClassNode node = new ClassNode();
        node.name = "sample/AdapterFixture";
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "adapt", "()V", null, null);
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC,
                "sample/Attributes", "DAMAGE", "Lsample/Attribute;"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "sample/Event", "has", "(Lsample/Attribute;)Z", false));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);

        session.apply(node);
        Forge1201CallSiteAdapter.Stats stats = session.finish();

        assertEquals("synthetic-descriptor-profile", stats.profileId());
        assertEquals(1, stats.rulesApplied());
        assertEquals(3, stats.operationsApplied());
        assertEquals("Lsample/Holder;", ((FieldInsnNode) method.instructions.getFirst()).desc);
        assertEquals("(Lsample/Holder;)Z",
                ((MethodInsnNode) method.instructions.get(1)).desc);
        assertEquals("sample/SkinModel", ((TypeInsnNode) method.instructions.get(2)).desc);
    }

    @Test
    void rewritesEntityDimensionsEyeHeightWithoutDroppingScaleOrClamp() throws Exception {
        Path profileFile = writeProfile("""
                {
                  "schemaVersion": 1,
                  "id": "synthetic-size-profile",
                  "rules": [{
                    "className": "sample/SizeAdapter",
                    "methodName": "apply",
                    "methodDescriptor": "(Lnet/minecraftforge/event/entity/EntityEvent$Size;)V",
                    "operations": [{
                      "type": "entity-dimensions-eye-height-scale",
                      "expectedMatches": 1
                    }]
                  }]
                }
                """);

        ClassNode node = new ClassNode();
        node.name = "sample/SizeAdapter";
        MethodNode method = createSizeMethod();
        node.methods.add(method);

        Forge1201CallSiteAdapter.Session session =
                new Forge1201CallSiteAdapter.Session(Forge1201CallSiteAdapter.load(profileFile));
        session.apply(node);
        Forge1201CallSiteAdapter.Stats stats = session.finish();

        assertEquals(1, stats.rulesApplied());
        assertEquals(1, stats.operationsApplied());
        assertEquals(2, countCalls(method, EVENT, "getNewSize", "()" + DIMENSIONS_DESC));
        assertEquals(1, countCalls(method, DIMENSIONS, "eyeHeight", "()F"));
        assertEquals(1, countCalls(method, DIMENSIONS, "withEyeHeight", "(F)" + DIMENSIONS_DESC));
        assertEquals(2, countCalls(method, EVENT, "setNewSize", "(" + DIMENSIONS_DESC + ")V"));
        assertEquals(0, countCalls(method, EVENT, "getNewEyeHeight", "()F"));
        assertEquals(0, countCalls(method, EVENT, "setNewEyeHeight", "(F)V"));
        assertTrue(countVars(method, Opcodes.FLOAD, 3) >= 1);
        assertTrue(countVars(method, Opcodes.FSTORE, 3) >= 2);
        assertEquals(4, method.maxLocals);
        assertTrue(method.maxStack >= 3);

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) calls.add(call.owner + "#" + call.name);
        }
        int withEyeHeightIndex = calls.indexOf(DIMENSIONS + "#withEyeHeight");
        int secondSetSizeIndex = calls.lastIndexOf(EVENT + "#setNewSize");
        assertTrue(withEyeHeightIndex >= 0);
        assertTrue(secondSetSizeIndex > withEyeHeightIndex);
    }

    @Test
    void failsClosedWhenARequiredInstructionCountDoesNotMatch() throws Exception {
        Path profileFile = writeProfile("""
                {
                  "schemaVersion": 1,
                  "id": "stale-profile",
                  "rules": [{
                    "className": "sample/AdapterFixture",
                    "methodName": "adapt",
                    "methodDescriptor": "()V",
                    "operations": [{
                      "type": "method-descriptor",
                      "owner": "sample/Event",
                      "name": "has",
                      "descriptor": "(Lsample/Attribute;)Z",
                      "replacementDescriptor": "(Lsample/Holder;)Z",
                      "opcode": "INVOKEVIRTUAL",
                      "expectedMatches": 2
                    }]
                  }]
                }
                """);

        ClassNode node = new ClassNode();
        node.name = "sample/AdapterFixture";
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "adapt", "()V", null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "sample/Event", "has", "(Lsample/Attribute;)Z", false));
        node.methods.add(method);

        Forge1201CallSiteAdapter.Session session =
                new Forge1201CallSiteAdapter.Session(Forge1201CallSiteAdapter.load(profileFile));
        assertThrows(IOException.class, () -> session.apply(node));
    }

    @Test
    void failsAtFinishWhenARequiredMethodWasNotFound() throws Exception {
        Path profileFile = writeProfile("""
                {
                  "schemaVersion": 1,
                  "id": "missing-method-profile",
                  "rules": [{
                    "className": "sample/AdapterFixture",
                    "methodName": "required",
                    "methodDescriptor": "()V",
                    "operations": [{
                      "type": "checkcast-type",
                      "sourceType": "java/lang/String",
                      "replacementType": "sample/Model",
                      "expectedMatches": 1
                    }]
                  }]
                }
                """);

        Forge1201CallSiteAdapter.Session session =
                new Forge1201CallSiteAdapter.Session(Forge1201CallSiteAdapter.load(profileFile));
        ClassNode node = new ClassNode();
        node.name = "sample/AdapterFixture";
        node.methods.add(new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "different", "()V", null, null));
        session.apply(node);

        assertThrows(IOException.class, session::finish);
    }

    @Test
    void rejectsUnsupportedProfileVersion() throws Exception {
        Path profileFile = writeProfile("""
                {"schemaVersion": 99, "id": "future", "rules": []}
                """);
        assertThrows(IOException.class, () -> Forge1201CallSiteAdapter.load(profileFile));
    }

    private Path writeProfile(String json) throws IOException {
        Path file = tempDir.resolve("adapter-profile.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static MethodNode createSizeMethod() {
        MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "apply", "(L" + EVENT + ";)V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "getNewSize",
                "()" + DIMENSIONS_DESC, false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, DIMENSIONS, "width", "F"));
        method.instructions.add(new LdcInsnNode(1.35F));
        method.instructions.add(new InsnNode(Opcodes.FMUL));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, DIMENSIONS, "height", "F"));
        method.instructions.add(new LdcInsnNode(1.35F));
        method.instructions.add(new InsnNode(Opcodes.FMUL));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DIMENSIONS, "scalable",
                "(FF)" + DIMENSIONS_DESC, false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "setNewSize",
                "(" + DIMENSIONS_DESC + ")V", false));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "getNewEyeHeight", "()F", false));
        method.instructions.add(new LdcInsnNode(1.35F));
        method.instructions.add(new InsnNode(Opcodes.FMUL));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 3));

        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new LdcInsnNode(0.05F));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, DIMENSIONS, "height", "F"));
        method.instructions.add(new LdcInsnNode(0.05F));
        method.instructions.add(new InsnNode(Opcodes.FSUB));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(FF)F", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(FF)F", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "setNewEyeHeight",
                "(F)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxLocals = 4;
        method.maxStack = 4;
        return method;
    }

    private static int countCalls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor)) count++;
        }
        return count;
    }

    private static int countVars(MethodNode method, int opcode, int slot) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode
                    && variable.var == slot) count++;
        }
        return count;
    }
}
