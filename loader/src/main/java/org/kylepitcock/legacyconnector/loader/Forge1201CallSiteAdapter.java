package org.kylepitcock.legacyconnector.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Applies explicitly selected, fail-fast call-site adaptations to Forge 1.20.1 class files.
 *
 * <p>Profiles are external JSON data. The transformer contains reusable operations only; mod-specific
 * class selectors and adaptation profiles can live outside the public loader repository.</p>
 *
 * <p>All selectors use the original input namespace and are evaluated before package relocation.
 * Each rule must match exactly one method, and every operation must match its declared instruction
 * count. A stale or partially matching profile aborts the archive transformation.</p>
 */
public final class Forge1201CallSiteAdapter {
    private static final int PROFILE_SCHEMA_VERSION = 1;

    private static final String OP_FIELD_DESCRIPTOR = "field-descriptor";
    private static final String OP_METHOD_DESCRIPTOR = "method-descriptor";
    private static final String OP_CHECKCAST_TYPE = "checkcast-type";
    private static final String OP_ENTITY_DIMENSIONS_EYE_HEIGHT_SCALE =
            "entity-dimensions-eye-height-scale";

    private static final String ENTITY_DIMENSIONS = "net/minecraft/world/entity/EntityDimensions";
    private static final String MATH = "java/lang/Math";

    private Forge1201CallSiteAdapter() {}

    public record Profile(String id, List<Rule> rules) {
        public Profile {
            rules = List.copyOf(rules);
        }
    }

    public record Rule(
            String className,
            String methodName,
            String methodDescriptor,
            List<Operation> operations) {
        public Rule {
            operations = List.copyOf(operations);
        }

        String key() {
            return className + "#" + methodName + methodDescriptor;
        }
    }

    public record Operation(
            String type,
            String owner,
            String name,
            String descriptor,
            String replacementDescriptor,
            String sourceType,
            String replacementType,
            int opcode,
            int expectedMatches) {}

    public record Stats(String profileId, int rulesApplied, int operationsApplied) {}

    /**
     * Reads and validates an external adapter profile.
     *
     * <p>Profile class names and descriptors refer to the original Forge 1.20.1 input JAR, not
     * the already-relocated intermediate representation.</p>
     */
    public static Profile load(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Adapter profile does not exist or is not a regular file: " + path);
        }

        final JsonObject root;
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new IOException("Adapter profile root must be a JSON object");
            root = parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IOException("Cannot parse adapter profile " + path, malformed);
        }

        int schemaVersion = requiredInt(root, "schemaVersion", "profile");
        if (schemaVersion != PROFILE_SCHEMA_VERSION) {
            throw new IOException("Unsupported adapter profile schemaVersion " + schemaVersion
                    + "; supported version is " + PROFILE_SCHEMA_VERSION);
        }
        String id = requiredString(root, "id", "profile");
        JsonArray jsonRules = requiredArray(root, "rules", "profile");
        if (jsonRules.isEmpty()) throw new IOException("Adapter profile must contain at least one rule");

        List<Rule> rules = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < jsonRules.size(); index++) {
            JsonElement element = jsonRules.get(index);
            if (!element.isJsonObject()) throw new IOException("rules[" + index + "] must be an object");
            JsonObject jsonRule = element.getAsJsonObject();
            String location = "rules[" + index + "]";
            String className = requiredInternalName(jsonRule, "className", location);
            String methodName = requiredString(jsonRule, "methodName", location);
            String methodDescriptor = requiredString(jsonRule, "methodDescriptor", location);
            if (!methodDescriptor.startsWith("(")) {
                throw new IOException(location + ".methodDescriptor must be a JVM method descriptor");
            }

            JsonArray jsonOperations = requiredArray(jsonRule, "operations", location);
            if (jsonOperations.isEmpty()) {
                throw new IOException(location + ".operations must not be empty");
            }
            List<Operation> operations = new ArrayList<>();
            for (int operationIndex = 0; operationIndex < jsonOperations.size(); operationIndex++) {
                JsonElement operationElement = jsonOperations.get(operationIndex);
                if (!operationElement.isJsonObject()) {
                    throw new IOException(location + ".operations[" + operationIndex + "] must be an object");
                }
                operations.add(parseOperation(operationElement.getAsJsonObject(),
                        location + ".operations[" + operationIndex + "]"));
            }

            Rule rule = new Rule(className, methodName, methodDescriptor, operations);
            if (!keys.add(rule.key())) throw new IOException("Duplicate adapter rule: " + rule.key());
            rules.add(rule);
        }
        return new Profile(id, rules);
    }

    public static final class Session {
        private final Profile profile;
        private final Map<String, Rule> pending = new LinkedHashMap<>();
        private int operationsApplied;

        public Session(Profile profile) {
            if (profile == null) throw new IllegalArgumentException("Adapter profile is required");
            this.profile = profile;
            for (Rule rule : profile.rules()) pending.put(rule.key(), rule);
        }

        /** Applies the profile rules belonging to this class, if any. */
        public void apply(ClassNode classNode) throws IOException {
            if (classNode == null) throw new IllegalArgumentException("Class node is required");
            List<Rule> classRules = profile.rules().stream()
                    .filter(rule -> rule.className().equals(classNode.name))
                    .toList();
            for (Rule rule : classRules) {
                if (!pending.containsKey(rule.key())) {
                    throw new IOException("Adapter rule was encountered more than once: " + rule.key());
                }

                List<MethodNode> matches = classNode.methods.stream()
                        .filter(method -> method.name.equals(rule.methodName())
                                && method.desc.equals(rule.methodDescriptor()))
                        .toList();
                if (matches.size() != 1) {
                    throw new IOException("Adapter rule " + rule.key() + " expected exactly one method, found "
                            + matches.size());
                }

                MethodNode method = matches.get(0);
                for (Operation operation : rule.operations()) {
                    applyOperation(rule, method, operation);
                    operationsApplied++;
                }
                pending.remove(rule.key());
            }
        }

        /** Fails if any required class/method rule was absent from the input archive. */
        public Stats finish() throws IOException {
            if (!pending.isEmpty()) {
                throw new IOException("Adapter profile '" + profile.id() + "' did not match required methods: "
                        + String.join(", ", pending.keySet()));
            }
            return new Stats(profile.id(), profile.rules().size(), operationsApplied);
        }
    }

    private static void applyOperation(Rule rule, MethodNode method, Operation operation) throws IOException {
        switch (operation.type()) {
            case OP_FIELD_DESCRIPTOR -> replaceFieldDescriptor(rule, method, operation);
            case OP_METHOD_DESCRIPTOR -> replaceMethodDescriptor(rule, method, operation);
            case OP_CHECKCAST_TYPE -> replaceCheckcastType(rule, method, operation);
            case OP_ENTITY_DIMENSIONS_EYE_HEIGHT_SCALE -> rewriteEntityDimensionsEyeHeightScale(rule, method, operation);
            default -> throw new IOException("Unsupported adapter operation '" + operation.type()
                    + "' in " + rule.key());
        }
    }

    private static void replaceFieldDescriptor(Rule rule, MethodNode method, Operation operation)
            throws IOException {
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == operation.opcode()
                    && field.owner.equals(operation.owner())
                    && field.name.equals(operation.name())
                    && field.desc.equals(operation.descriptor())) {
                field.desc = operation.replacementDescriptor();
                matches++;
            }
        }
        requireExpectedMatches(rule, operation, matches);
    }

    private static void replaceMethodDescriptor(Rule rule, MethodNode method, Operation operation)
            throws IOException {
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == operation.opcode()
                    && call.owner.equals(operation.owner())
                    && call.name.equals(operation.name())
                    && call.desc.equals(operation.descriptor())) {
                call.desc = operation.replacementDescriptor();
                matches++;
            }
        }
        requireExpectedMatches(rule, operation, matches);
    }

    private static void replaceCheckcastType(Rule rule, MethodNode method, Operation operation)
            throws IOException {
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode typeInsn
                    && typeInsn.getOpcode() == Opcodes.CHECKCAST
                    && typeInsn.desc.equals(operation.sourceType())) {
                typeInsn.desc = operation.replacementType();
                matches++;
            }
        }
        requireExpectedMatches(rule, operation, matches);
    }

    /**
     * Rewrites the common Forge Size-event pattern that scaled width/height and then changed eye
     * height through separate event accessors. The target keeps eye height inside EntityDimensions.
     *
     * <p>The original eye height is captured before the first setNewSize call. The existing 1.35
     * multiplier and clamp are retained, then applied through withEyeHeight and a second setNewSize.
     * Exact surrounding instruction patterns are required; otherwise this operation fails closed.</p>
     */
    private static void rewriteEntityDimensionsEyeHeightScale(
            Rule rule, MethodNode method, Operation operation) throws IOException {
        final String eventOwner = "net/minecraftforge/event/entity/EntityEvent$Size";
        final String dimensions = ENTITY_DIMENSIONS;
        final String dimensionsDescriptor = "L" + dimensions + ";";

        List<MethodInsnNode> getNewSize = calls(method, eventOwner, "getNewSize",
                "()" + dimensionsDescriptor);
        List<MethodInsnNode> setNewSize = calls(method, eventOwner, "setNewSize",
                "(" + dimensionsDescriptor + ")V");
        List<MethodInsnNode> getNewEyeHeight = calls(method, eventOwner, "getNewEyeHeight", "()F");
        List<MethodInsnNode> setNewEyeHeight = calls(method, eventOwner, "setNewEyeHeight", "(F)V");

        if (getNewSize.size() != 1 || setNewSize.size() != 1
                || getNewEyeHeight.size() != 1 || setNewEyeHeight.size() != 1) {
            throw new IOException("Eye-height adapter for " + rule.key()
                    + " expected one getNewSize, setNewSize, getNewEyeHeight, and setNewEyeHeight call; found "
                    + getNewSize.size() + ", " + setNewSize.size() + ", "
                    + getNewEyeHeight.size() + ", " + setNewEyeHeight.size());
        }

        MethodInsnNode initialSetSize = setNewSize.get(0);
        AbstractInsnNode initialSizeArgument = previousOpcode(initialSetSize);
        AbstractInsnNode initialSizeReceiver = previousOpcode(initialSizeArgument);
        if (!isVar(initialSizeArgument, Opcodes.ALOAD, 2)
                || !isVar(initialSizeReceiver, Opcodes.ALOAD, 0)) {
            throw new IOException("Eye-height adapter for " + rule.key()
                    + " did not find the expected event.setNewSize(local-2) call pattern");
        }

        MethodInsnNode oldEyeGetter = getNewEyeHeight.get(0);
        AbstractInsnNode oldEyeReceiver = previousOpcode(oldEyeGetter);
        if (!isVar(oldEyeReceiver, Opcodes.ALOAD, 0)) {
            throw new IOException("Eye-height adapter for " + rule.key()
                    + " did not find the expected getNewEyeHeight receiver");
        }

        MethodInsnNode oldEyeSetter = setNewEyeHeight.get(0);
        List<AbstractInsnNode> clampTail = previousOpcodes(oldEyeSetter, 9);
        if (!isExpectedClampTail(clampTail, dimensions)) {
            throw new IOException("Eye-height adapter for " + rule.key()
                    + " did not find the expected local-2 eye-height clamp pattern");
        }

        // Save the unscaled event eye height before the dimensions are replaced.
        InsnList capture = new InsnList();
        capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
        capture.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, eventOwner, "getNewSize",
                "()" + dimensionsDescriptor, false));
        capture.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, dimensions, "eyeHeight", "()F", false));
        capture.add(new VarInsnNode(Opcodes.FSTORE, 3));
        method.instructions.insertBefore(initialSizeReceiver, capture);

        // Reuse the original float local so the original 1.35 multiplication and clamp remain intact.
        method.instructions.remove(oldEyeReceiver);
        method.instructions.set(oldEyeGetter, new VarInsnNode(Opcodes.FLOAD, 3));

        // Keep the original event on the stack, then add the scaled dimensions as the withEyeHeight receiver.
        method.instructions.insert(clampTail.get(0), new VarInsnNode(Opcodes.ALOAD, 2));
        MethodInsnNode withEyeHeight = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, dimensions, "withEyeHeight",
                "(F)" + dimensionsDescriptor, false);
        MethodInsnNode setUpdatedSize = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, eventOwner, "setNewSize",
                "(" + dimensionsDescriptor + ")V", false);
        method.instructions.set(oldEyeSetter, withEyeHeight);
        method.instructions.insert(withEyeHeight, setUpdatedSize);

        method.maxLocals = Math.max(method.maxLocals, 4);
        method.maxStack = Math.max(method.maxStack, 3);
        requireExpectedMatches(rule, operation, 1);
    }

    private static boolean isExpectedClampTail(List<AbstractInsnNode> tail, String dimensions) {
        if (tail.size() != 9) return false;
        return isVar(tail.get(0), Opcodes.ALOAD, 0)
                && isFloatConstant(tail.get(1), 0.05F)
                && isVar(tail.get(2), Opcodes.ALOAD, 2)
                && tail.get(3) instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(dimensions)
                    && field.desc.equals("F")
                && isFloatConstant(tail.get(4), 0.05F)
                && tail.get(5).getOpcode() == Opcodes.FSUB
                && isVar(tail.get(6), Opcodes.FLOAD, 3)
                && isMethod(tail.get(7), Opcodes.INVOKESTATIC, MATH, "min", "(FF)F")
                && isMethod(tail.get(8), Opcodes.INVOKESTATIC, MATH, "max", "(FF)F");
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) {
                matches.add(call);
            }
        }
        return matches;
    }

    private static List<AbstractInsnNode> previousOpcodes(AbstractInsnNode instruction, int count) {
        List<AbstractInsnNode> reversed = new ArrayList<>(count);
        AbstractInsnNode cursor = instruction;
        while (reversed.size() < count && (cursor = previousOpcode(cursor)) != null) {
            reversed.add(cursor);
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }

    private static boolean isVar(AbstractInsnNode instruction, int opcode, int slot) {
        return instruction instanceof VarInsnNode variable
                && variable.getOpcode() == opcode && variable.var == slot;
    }

    private static boolean isFloatConstant(AbstractInsnNode instruction, float expected) {
        if (!(instruction instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Float value)) return false;
        return Float.compare(value, expected) == 0;
    }

    private static boolean isMethod(
            AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == opcode
                && call.owner.equals(owner)
                && call.name.equals(name)
                && call.desc.equals(descriptor);
    }

    private static void requireExpectedMatches(Rule rule, Operation operation, int actual) throws IOException {
        if (actual != operation.expectedMatches()) {
            throw new IOException("Adapter operation '" + operation.type() + "' in " + rule.key()
                    + " expected " + operation.expectedMatches() + " matches, found " + actual);
        }
    }

    private static Operation parseOperation(JsonObject json, String location) throws IOException {
        String type = requiredString(json, "type", location);
        int expected = requiredInt(json, "expectedMatches", location);
        if (expected < 1) throw new IOException(location + ".expectedMatches must be at least 1");

        return switch (type) {
            case OP_FIELD_DESCRIPTOR, OP_METHOD_DESCRIPTOR -> new Operation(
                    type,
                    requiredInternalName(json, "owner", location),
                    requiredString(json, "name", location),
                    requiredString(json, "descriptor", location),
                    requiredString(json, "replacementDescriptor", location),
                    null, null,
                    requiredOpcode(json, "opcode", location, type.equals(OP_FIELD_DESCRIPTOR)),
                    expected);
            case OP_CHECKCAST_TYPE -> new Operation(
                    type, null, null, null, null,
                    requiredInternalName(json, "sourceType", location),
                    requiredInternalName(json, "replacementType", location),
                    -1, expected);
            case OP_ENTITY_DIMENSIONS_EYE_HEIGHT_SCALE -> {
                if (expected != 1) {
                    throw new IOException(location + ".expectedMatches must be 1 for " + type);
                }
                yield new Operation(type, null, null, null, null, null, null, -1, expected);
            }
            default -> throw new IOException(location + " has unsupported operation type '" + type + "'");
        };
    }

    private static int requiredOpcode(JsonObject json, String property, String location, boolean field)
            throws IOException {
        String name = requiredString(json, property, location);
        return switch (name) {
            case "GETSTATIC" -> Opcodes.GETSTATIC;
            case "PUTSTATIC" -> Opcodes.PUTSTATIC;
            case "GETFIELD" -> {
                if (!field) throw new IOException(location + ".opcode GETFIELD is only valid for field operations");
                yield Opcodes.GETFIELD;
            }
            case "PUTFIELD" -> {
                if (!field) throw new IOException(location + ".opcode PUTFIELD is only valid for field operations");
                yield Opcodes.PUTFIELD;
            }
            case "INVOKEVIRTUAL" -> {
                if (field) throw new IOException(location + ".opcode INVOKEVIRTUAL is not valid for field operations");
                yield Opcodes.INVOKEVIRTUAL;
            }
            case "INVOKESTATIC" -> {
                if (field) throw new IOException(location + ".opcode INVOKESTATIC is not valid for field operations");
                yield Opcodes.INVOKESTATIC;
            }
            case "INVOKEINTERFACE" -> {
                if (field) throw new IOException(location + ".opcode INVOKEINTERFACE is not valid for field operations");
                yield Opcodes.INVOKEINTERFACE;
            }
            case "INVOKESPECIAL" -> {
                if (field) throw new IOException(location + ".opcode INVOKESPECIAL is not valid for field operations");
                yield Opcodes.INVOKESPECIAL;
            }
            default -> throw new IOException(location + ".opcode is unsupported: " + name);
        };
    }

    private static String requiredInternalName(JsonObject json, String property, String location)
            throws IOException {
        String value = requiredString(json, property, location);
        if (value.indexOf('.') >= 0 || value.startsWith("/") || value.endsWith("/")
                || value.contains("//")) {
            throw new IOException(location + "." + property + " must be a JVM internal name using '/' separators");
        }
        return value;
    }

    private static String requiredString(JsonObject json, String property, String location) throws IOException {
        JsonElement element = json.get(property);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IOException(location + "." + property + " must be a non-blank string");
        }
        return element.getAsString();
    }

    private static int requiredInt(JsonObject json, String property, String location) throws IOException {
        JsonElement element = json.get(property);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException(location + "." + property + " must be an integer");
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException invalid) {
            throw new IOException(location + "." + property + " must be an integer", invalid);
        }
    }

    private static JsonArray requiredArray(JsonObject json, String property, String location) throws IOException {
        JsonElement element = json.get(property);
        if (element == null || !element.isJsonArray()) {
            throw new IOException(location + "." + property + " must be an array");
        }
        return element.getAsJsonArray();
    }
}
