package org.kylepitcock.legacyconnector.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Applies explicitly selected, fail-fast call-site adaptations to Forge 1.20.1 class files.
 *
 * <p>Profiles are external JSON data. The transformer contains reusable operations only; mod-specific
 * class selectors and bytecode patterns can live outside the public loader repository.</p>
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
    private static final String OP_INSTRUCTION_SEQUENCE = "instruction-sequence";

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

    /** An instruction recipe. Unused operands are null or -1 according to the instruction kind. */
    public record InstructionSpec(
            String kind,
            int opcode,
            int variable,
            String owner,
            String name,
            String descriptor,
            String typeName,
            String valueType,
            String value) {}

    public record Operation(
            String type,
            String owner,
            String name,
            String descriptor,
            String replacementDescriptor,
            String sourceType,
            String replacementType,
            int opcode,
            List<InstructionSpec> match,
            List<InstructionSpec> replacement,
            int minimumMaxStack,
            int minimumMaxLocals,
            int expectedMatches) {
        public Operation {
            match = List.copyOf(match);
            replacement = List.copyOf(replacement);
        }
    }

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
            case OP_INSTRUCTION_SEQUENCE -> rewriteInstructionSequence(rule, method, operation);
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
     * Replaces exact contiguous instruction patterns declared by an external profile.
     * Labels and frames are never silently consumed: a match crossing a control-flow boundary fails.
     */
    private static void rewriteInstructionSequence(Rule rule, MethodNode method, Operation operation)
            throws IOException {
        List<List<AbstractInsnNode>> matches = findSequenceMatches(method, operation.match());
        requireExpectedMatches(rule, operation, matches.size());

        for (int index = matches.size() - 1; index >= 0; index--) {
            List<AbstractInsnNode> matched = matches.get(index);
            ensureNoControlFlowMarkerInside(rule, matched);

            AbstractInsnNode first = matched.get(0);
            InsnList replacement = new InsnList();
            for (InstructionSpec spec : operation.replacement()) replacement.add(createInstruction(spec));
            if (replacement.size() > 0) method.instructions.insertBefore(first, replacement);
            matched.forEach(method.instructions::remove);
        }

        method.maxStack = Math.max(method.maxStack, operation.minimumMaxStack());
        method.maxLocals = Math.max(method.maxLocals, operation.minimumMaxLocals());
    }

    private static List<List<AbstractInsnNode>> findSequenceMatches(
            MethodNode method, List<InstructionSpec> pattern) {
        List<List<AbstractInsnNode>> matches = new ArrayList<>();
        for (AbstractInsnNode start : method.instructions) {
            if (start.getOpcode() < 0 || !matches(start, pattern.get(0))) continue;

            List<AbstractInsnNode> nodes = new ArrayList<>(pattern.size());
            AbstractInsnNode cursor = start;
            boolean matched = true;
            for (InstructionSpec spec : pattern) {
                while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
                if (cursor == null || !matches(cursor, spec)) {
                    matched = false;
                    break;
                }
                nodes.add(cursor);
                cursor = cursor.getNext();
            }
            if (matched) matches.add(List.copyOf(nodes));
        }
        return matches;
    }

    private static boolean matches(AbstractInsnNode instruction, InstructionSpec spec) {
        if (instruction.getOpcode() != spec.opcode()) return false;
        return switch (spec.kind()) {
            case "var" -> instruction instanceof VarInsnNode variable && variable.var == spec.variable();
            case "method" -> instruction instanceof MethodInsnNode call
                    && call.owner.equals(spec.owner())
                    && call.name.equals(spec.name())
                    && call.desc.equals(spec.descriptor());
            case "field" -> instruction instanceof FieldInsnNode field
                    && field.owner.equals(spec.owner())
                    && field.name.equals(spec.name())
                    && field.desc.equals(spec.descriptor());
            case "type" -> instruction instanceof TypeInsnNode typeInsn
                    && typeInsn.desc.equals(spec.typeName());
            case "ldc" -> instruction instanceof LdcInsnNode ldc
                    && Objects.equals(ldc.cst, constantValue(spec));
            case "insn" -> instruction instanceof InsnNode;
            default -> false;
        };
    }

    private static AbstractInsnNode createInstruction(InstructionSpec spec) throws IOException {
        return switch (spec.kind()) {
            case "var" -> new VarInsnNode(spec.opcode(), spec.variable());
            case "method" -> new MethodInsnNode(spec.opcode(), spec.owner(), spec.name(),
                    spec.descriptor(), spec.opcode() == Opcodes.INVOKEINTERFACE);
            case "field" -> new FieldInsnNode(spec.opcode(), spec.owner(), spec.name(), spec.descriptor());
            case "type" -> new TypeInsnNode(spec.opcode(), spec.typeName());
            case "ldc" -> new LdcInsnNode(constantValue(spec));
            case "insn" -> new InsnNode(spec.opcode());
            default -> throw new IOException("Unsupported instruction recipe kind: " + spec.kind());
        };
    }

    private static Object constantValue(InstructionSpec spec) throws IOException {
        try {
            return switch (spec.valueType()) {
                case "float" -> Float.valueOf(spec.value());
                case "double" -> Double.valueOf(spec.value());
                case "int" -> Integer.valueOf(spec.value());
                case "long" -> Long.valueOf(spec.value());
                case "string" -> spec.value();
                default -> throw new IOException("Unsupported LDC valueType: " + spec.valueType());
            };
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid LDC constant value '" + spec.value() + "'", invalid);
        }
    }

    private static void ensureNoControlFlowMarkerInside(Rule rule, List<AbstractInsnNode> matched)
            throws IOException {
        AbstractInsnNode cursor = matched.get(0).getNext();
        AbstractInsnNode last = matched.get(matched.size() - 1);
        while (cursor != null && cursor != last) {
            if (cursor instanceof LabelNode || cursor instanceof FrameNode
                    || cursor instanceof org.objectweb.asm.tree.JumpInsnNode
                    || cursor instanceof org.objectweb.asm.tree.TableSwitchInsnNode
                    || cursor instanceof org.objectweb.asm.tree.LookupSwitchInsnNode) {
                throw new IOException("Instruction-sequence adapter in " + rule.key()
                        + " crosses a label, frame, or control-flow instruction");
            }
            cursor = cursor.getNext();
        }
        if (cursor != last) {
            throw new IOException("Instruction-sequence adapter in " + rule.key()
                    + " contains a non-contiguous instruction sequence");
        }
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
                    List.of(), List.of(), 0, 0, expected);
            case OP_CHECKCAST_TYPE -> new Operation(
                    type, null, null, null, null,
                    requiredInternalName(json, "sourceType", location),
                    requiredInternalName(json, "replacementType", location),
                    -1, List.of(), List.of(), 0, 0, expected);
            case OP_INSTRUCTION_SEQUENCE -> {
                JsonArray match = requiredArray(json, "match", location);
                JsonArray replacement = requiredArray(json, "replaceWith", location);
                if (match.isEmpty()) throw new IOException(location + ".match must not be empty");
                List<InstructionSpec> matchSpecs = parseInstructionArray(match, location + ".match");
                List<InstructionSpec> replacementSpecs =
                        parseInstructionArray(replacement, location + ".replaceWith");
                int minimumMaxStack = optionalInt(json, "minimumMaxStack", 0, location);
                int minimumMaxLocals = optionalInt(json, "minimumMaxLocals", 0, location);
                if (minimumMaxStack < 0 || minimumMaxLocals < 0) {
                    throw new IOException(location + " minimum max values must not be negative");
                }
                yield new Operation(type, null, null, null, null, null, null, -1,
                        matchSpecs, replacementSpecs, minimumMaxStack, minimumMaxLocals, expected);
            }
            default -> throw new IOException(location + " has unsupported operation type '" + type + "'");
        };
    }

    private static List<InstructionSpec> parseInstructionArray(JsonArray array, String location)
            throws IOException {
        List<InstructionSpec> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonObject()) throw new IOException(location + "[" + index + "] must be an object");
            result.add(parseInstruction(element.getAsJsonObject(), location + "[" + index + "]"));
        }
        return List.copyOf(result);
    }

    private static InstructionSpec parseInstruction(JsonObject json, String location) throws IOException {
        String kind = requiredString(json, "kind", location);
        return switch (kind) {
            case "var" -> {
                int opcode = requiredAnyOpcode(json, location);
                if (!isVariableOpcode(opcode)) throw new IOException(location + " has a non-variable opcode");
                int variable = requiredInt(json, "var", location);
                if (variable < 0) throw new IOException(location + ".var must not be negative");
                yield new InstructionSpec(kind, opcode, variable, null, null, null, null, null, null);
            }
            case "method" -> {
                int opcode = requiredAnyOpcode(json, location);
                if (!isMethodOpcode(opcode)) throw new IOException(location + " has a non-invocation opcode");
                yield new InstructionSpec(kind, opcode, -1,
                        requiredInternalName(json, "owner", location),
                        requiredString(json, "name", location),
                        requiredString(json, "descriptor", location),
                        null, null, null);
            }
            case "field" -> {
                int opcode = requiredAnyOpcode(json, location);
                if (!isFieldOpcode(opcode)) throw new IOException(location + " has a non-field opcode");
                yield new InstructionSpec(kind, opcode, -1,
                        requiredInternalName(json, "owner", location),
                        requiredString(json, "name", location),
                        requiredString(json, "descriptor", location),
                        null, null, null);
            }
            case "type" -> {
                int opcode = requiredAnyOpcode(json, location);
                if (opcode != Opcodes.NEW && opcode != Opcodes.ANEWARRAY
                        && opcode != Opcodes.CHECKCAST && opcode != Opcodes.INSTANCEOF) {
                    throw new IOException(location + " has an unsupported type opcode");
                }
                yield new InstructionSpec(kind, opcode, -1, null, null, null,
                        requiredInternalName(json, "typeName", location), null, null);
            }
            case "ldc" -> {
                String valueType = requiredString(json, "valueType", location);
                JsonElement value = json.get("value");
                if (value == null || !value.isJsonPrimitive()) {
                    throw new IOException(location + ".value must be a JSON primitive");
                }
                if (valueType.equals("string") && !value.getAsJsonPrimitive().isString()) {
                    throw new IOException(location + ".value must be a string for valueType string");
                }
                if (!valueType.equals("string") && !value.getAsJsonPrimitive().isNumber()) {
                    throw new IOException(location + ".value must be numeric for valueType " + valueType);
                }
                String rawValue = value.getAsString();
                // Validate the supported constant type now, not only when the operation executes.
                InstructionSpec spec = new InstructionSpec(kind, -1, -1, null, null, null,
                        null, valueType, rawValue);
                constantValue(spec);
                yield spec;
            }
            case "insn" -> {
                int opcode = requiredAnyOpcode(json, location);
                if (!isSimpleOpcode(opcode)) throw new IOException(location + " has an opcode with operands");
                yield new InstructionSpec(kind, opcode, -1, null, null, null, null, null, null);
            }
            default -> throw new IOException(location + " has unsupported instruction kind '" + kind + "'");
        };
    }

    private static int requiredOpcode(JsonObject json, String property, String location, boolean field)
            throws IOException {
        int opcode = opcodeValue(requiredString(json, property, location), location);
        if (field ? !isFieldOpcode(opcode) : !isMethodOpcode(opcode)) {
            throw new IOException(location + ".opcode is not valid for " + (field ? "field" : "method") + " operations");
        }
        return opcode;
    }

    private static int requiredAnyOpcode(JsonObject json, String location) throws IOException {
        return opcodeValue(requiredString(json, "opcode", location), location);
    }

    private static int opcodeValue(String name, String location) throws IOException {
        try {
            Field field = Opcodes.class.getField(name);
            int opcode = field.getInt(null);
            if (opcode < 0 || opcode > 201) throw new IOException(location + ".opcode is not a JVM instruction: " + name);
            return opcode;
        } catch (NoSuchFieldException | IllegalAccessException invalid) {
            throw new IOException(location + ".opcode is unknown: " + name, invalid);
        }
    }

    private static boolean isVariableOpcode(int opcode) {
        return opcode == Opcodes.ILOAD || opcode == Opcodes.LLOAD || opcode == Opcodes.FLOAD
                || opcode == Opcodes.DLOAD || opcode == Opcodes.ALOAD || opcode == Opcodes.ISTORE
                || opcode == Opcodes.LSTORE || opcode == Opcodes.FSTORE || opcode == Opcodes.DSTORE
                || opcode == Opcodes.ASTORE || opcode == Opcodes.RET;
    }

    private static boolean isMethodOpcode(int opcode) {
        return opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL
                || opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKEINTERFACE;
    }

    private static boolean isFieldOpcode(int opcode) {
        return opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
                || opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD;
    }

    private static boolean isSimpleOpcode(int opcode) {
        return opcode >= Opcodes.NOP && opcode <= Opcodes.DCONST_1
                || opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD
                || opcode >= Opcodes.POP && opcode <= Opcodes.SWAP
                || opcode >= Opcodes.IADD && opcode <= Opcodes.DCMPG
                || opcode >= Opcodes.I2L && opcode <= Opcodes.D2F
                || opcode >= Opcodes.LCMP && opcode <= Opcodes.RETURN
                || opcode >= Opcodes.ARRAYLENGTH && opcode <= Opcodes.MONITOREXIT
                || opcode == Opcodes.ATHROW;
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

    private static int optionalInt(JsonObject json, String property, int defaultValue, String location)
            throws IOException {
        return json.has(property) ? requiredInt(json, property, location) : defaultValue;
    }

    private static JsonArray requiredArray(JsonObject json, String property, String location) throws IOException {
        JsonElement element = json.get(property);
        if (element == null || !element.isJsonArray()) {
            throw new IOException(location + "." + property + " must be an array");
        }
        return element.getAsJsonArray();
    }
}
