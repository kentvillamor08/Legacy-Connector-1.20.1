package org.kylepitcock.legacyconnector.loader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins the Forge 1.20.1 SRG table to Mojang's official 1.20.1 names.
 *
 * <p>The join uses the obfuscated owner and exact obfuscated descriptor from
 * MCPConfig. The generated table is owner- and descriptor-scoped; a numeric
 * SRG identifier is never guessed globally. Mapping data is generated under
 * build/ and is not part of the public source tree.</p>
 */
public final class Forge1201SrgMemberMappingGenerator {
    private static final Pattern CLASS_LINE = Pattern.compile("^(\\S+) -> (\\S+):$");
    private static final Pattern LINE_NUMBER_PREFIX = Pattern.compile("^\\d+:\\d+:(.*)$");

    public record Result(int methodMappings, int fieldMappings, int ambiguousMappings,
                         int unmatchedSrgEntries) {}

    private record MemberKey(String owner, String srgName, String descriptor) {}
    private record OfficialMethodKey(String obfuscatedName, String obfuscatedDescriptor) {}
    private record RawField(String name, String type, String obfuscatedName) {}
    private record FieldMapping(String name, String type) {}
    private record MethodMapping(String name, String namedDescriptor) {}
    private record RawMethod(String name, String returnType, List<String> arguments, String obfuscatedName) {}
    private record RawClass(String namedOwner, List<RawField> fields, List<RawMethod> methods) {}
    private record OfficialClass(String namedOwner, Map<String, FieldMapping> fields,
                                 Map<OfficialMethodKey, MethodMapping> methods) {}
    private record OfficialMappings(Map<String, OfficialClass> classes) {}

    private Forge1201SrgMemberMappingGenerator() {}

    public static Result generate(Path joinedTsrg, Path clientMappings, Path serverMappings, Path output)
            throws IOException {
        if (joinedTsrg == null || !Files.isRegularFile(joinedTsrg)) {
            throw new IOException("Forge 1.20.1 joined.tsrg does not exist: " + joinedTsrg);
        }
        OfficialMappings client = parseOfficialMappings(clientMappings);
        OfficialMappings server = parseOfficialMappings(serverMappings);
        Map<MemberKey, Set<String>> candidates = new HashMap<>();
        int unmatched = 0;

        try (var reader = Files.newBufferedReader(joinedTsrg, StandardCharsets.UTF_8)) {
            String line;
            String currentObfuscatedOwner = null;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("tsrg2")) continue;
                if (line.charAt(0) != 9) {
                    String[] classParts = line.trim().split("\\s+");
                    currentObfuscatedOwner = classParts.length == 0 ? null : classParts[0];
                    continue;
                }
                if ((line.length() > 1 && line.charAt(1) == 9) || currentObfuscatedOwner == null) continue;

                String[] parts = line.trim().split("\\s+");
                if (parts.length == 3 && Forge1201SrgMemberNameMap.isSrgFieldName(parts[1])) {
                    boolean matched = false;
                    for (OfficialMappings mappings : List.of(client, server)) {
                        OfficialClass owner = mappings.classes().get(currentObfuscatedOwner);
                        if (owner == null) continue;
                        FieldMapping field = owner.fields().get(parts[0]);
                        if (field == null) continue;
                        String descriptor = typeDescriptor(field.type(), Map.of());
                        MemberKey key = new MemberKey(owner.namedOwner(), parts[1], descriptor);
                        candidates.computeIfAbsent(key, ignored -> new HashSet<>()).add(field.name());
                        matched = true;
                    }
                    if (!matched) unmatched++;
                } else if (parts.length == 4 && Forge1201SrgMemberNameMap.isSrgMethodName(parts[2])) {
                    boolean matched = false;
                    for (OfficialMappings mappings : List.of(client, server)) {
                        OfficialClass owner = mappings.classes().get(currentObfuscatedOwner);
                        if (owner == null) continue;
                        MethodMapping method = owner.methods().get(new OfficialMethodKey(parts[0], parts[1]));
                        if (method == null) continue;
                        MemberKey key = new MemberKey(owner.namedOwner(), parts[2], method.namedDescriptor());
                        candidates.computeIfAbsent(key, ignored -> new HashSet<>()).add(method.name());
                        matched = true;
                    }
                    if (!matched) unmatched++;
                }
            }
        }

        Map<MemberKey, String> unique = new TreeMap<>((a, b) -> {
            int c = a.owner().compareTo(b.owner());
            if (c != 0) return c;
            c = a.srgName().compareTo(b.srgName());
            if (c != 0) return c;
            return a.descriptor().compareTo(b.descriptor());
        });
        int ambiguous = 0;
        for (Map.Entry<MemberKey, Set<String>> entry : candidates.entrySet()) {
            if (entry.getValue().size() == 1) unique.put(entry.getKey(), entry.getValue().iterator().next());
            else ambiguous++;
        }

        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Mapping output must have a parent directory: " + output);
        Files.createDirectories(parent);
        List<String> lines = new ArrayList<>();
        lines.add("# Generated from Forge MCPConfig 1.20.1-20230612.114412 and Mojang 1.20.1 official mappings.");
        lines.add("# KIND<TAB>named-owner<TAB>source-SRG-name<TAB>source-descriptor<TAB>Mojang-name");
        String tab = String.valueOf((char) 9);
        for (Map.Entry<MemberKey, String> entry : unique.entrySet()) {
            MemberKey key = entry.getKey();
            String kind = Forge1201SrgMemberNameMap.isSrgMethodName(key.srgName()) ? "METHOD" : "FIELD";
            lines.add(String.join(tab, kind, key.owner(), key.srgName(), key.descriptor(), entry.getValue()));
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
        int methodCount = (int) unique.keySet().stream()
                .filter(k -> Forge1201SrgMemberNameMap.isSrgMethodName(k.srgName())).count();
        int fieldCount = unique.size() - methodCount;
        return new Result(methodCount, fieldCount, ambiguous, unmatched);
    }

    private static OfficialMappings parseOfficialMappings(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Mojang official mapping file does not exist: " + path);
        }

        Map<String, String> namedToObfuscated = new HashMap<>();
        Map<String, RawClass> rawClasses = new LinkedHashMap<>();
        String currentObfuscatedOwner = null;
        List<RawField> currentFields = null;
        List<RawMethod> currentMethods = null;

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            Matcher classMatcher = CLASS_LINE.matcher(line);
            if (classMatcher.matches()) {
                String namedOwner = classMatcher.group(1).replace('.', '/');
                currentObfuscatedOwner = classMatcher.group(2).replace('.', '/');
                namedToObfuscated.put(namedOwner, currentObfuscatedOwner);
                currentFields = new ArrayList<>();
                currentMethods = new ArrayList<>();
                rawClasses.put(currentObfuscatedOwner, new RawClass(namedOwner, currentFields, currentMethods));
                continue;
            }
            if (line.isEmpty() || !Character.isWhitespace(line.charAt(0))) continue;
            if (currentFields == null || currentMethods == null) continue;

            String memberLine = line.trim();
            Matcher numberPrefix = LINE_NUMBER_PREFIX.matcher(memberLine);
            if (numberPrefix.matches()) memberLine = numberPrefix.group(1).trim();
            int arrow = memberLine.lastIndexOf(" -> ");
            if (arrow < 0) continue;
            String declaration = memberLine.substring(0, arrow).trim();
            String obfuscatedName = memberLine.substring(arrow + 4).trim();
            int open = declaration.indexOf('(');
            if (open >= 0) {
                int close = declaration.lastIndexOf(')');
                if (close < open) continue;
                String beforeArgs = declaration.substring(0, open).trim();
                int split = beforeArgs.lastIndexOf(' ');
                if (split < 0) continue;
                String returnType = beforeArgs.substring(0, split).trim();
                String methodName = beforeArgs.substring(split + 1).trim();
                String argsText = declaration.substring(open + 1, close).trim();
                List<String> args = argsText.isEmpty()
                        ? List.of()
                        : java.util.Arrays.stream(argsText.split(",")).map(String::trim).toList();
                currentMethods.add(new RawMethod(methodName, returnType, args, obfuscatedName));
            } else {
                int split = declaration.lastIndexOf(' ');
                if (split < 0) continue;
                String fieldType = declaration.substring(0, split).trim();
                String fieldName = declaration.substring(split + 1).trim();
                currentFields.add(new RawField(fieldName, fieldType, obfuscatedName));
            }
        }

        Map<String, OfficialClass> classes = new HashMap<>();
        for (Map.Entry<String, RawClass> entry : rawClasses.entrySet()) {
            RawClass raw = entry.getValue();
            Map<String, FieldMapping> fields = new HashMap<>();
            for (RawField rawField : raw.fields()) {
                fields.put(rawField.obfuscatedName(), new FieldMapping(rawField.name(), rawField.type()));
            }

            Map<OfficialMethodKey, MethodMapping> methods = new HashMap<>();
            for (RawMethod rawMethod : raw.methods()) {
                String namedDescriptor = methodDescriptor(rawMethod.returnType(), rawMethod.arguments(), Map.of());
                String obfuscatedDescriptor = methodDescriptor(
                        rawMethod.returnType(), rawMethod.arguments(), namedToObfuscated);
                methods.put(new OfficialMethodKey(rawMethod.obfuscatedName(), obfuscatedDescriptor),
                        new MethodMapping(rawMethod.name(), namedDescriptor));
            }
            classes.put(entry.getKey(),
                    new OfficialClass(raw.namedOwner(), Map.copyOf(fields), Map.copyOf(methods)));
        }
        return new OfficialMappings(Map.copyOf(classes));
    }

    private static String methodDescriptor(
            String returnType, List<String> arguments, Map<String, String> namedToObfuscated) {
        StringBuilder descriptor = new StringBuilder("(");
        for (String argument : arguments) descriptor.append(typeDescriptor(argument, namedToObfuscated));
        return descriptor.append(')').append(typeDescriptor(returnType, namedToObfuscated)).toString();
    }

    private static String typeDescriptor(String sourceType, Map<String, String> namedToObfuscated) {
        String type = sourceType.trim();
        StringBuilder array = new StringBuilder();
        while (type.endsWith("[]")) {
            array.append('[');
            type = type.substring(0, type.length() - 2).trim();
        }
        String primitive = switch (type) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> null;
        };
        if (primitive != null) return array + primitive;
        String namedInternal = type.replace('.', '/');
        String mappedInternal = namedToObfuscated.getOrDefault(namedInternal, namedInternal);
        return array + "L" + mappedInternal + ";";
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: Forge1201SrgMemberMappingGenerator <joined.tsrg> <client.txt> <server.txt> <output.tsv>");
        }
        Result result = generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        System.out.println("Generated Forge 1.20.1 SRG member-name mappings:");
        System.out.println("  methods: " + result.methodMappings());
        System.out.println("  fields: " + result.fieldMappings());
        System.out.println("  ambiguous owner/name/descriptor entries skipped: " + result.ambiguousMappings());
        System.out.println("  unmatched SRG entries: " + result.unmatchedSrgEntries());
    }
}
