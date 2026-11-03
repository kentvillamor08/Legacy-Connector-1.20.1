package org.kylepitcock.legacyconnector.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Exact source-version SRG member-name lookup for the Forge 1.20.1 verifier.
 *
 * <p>Keys include the named source owner and descriptor. This deliberately avoids
 * unsafe global substitutions based only on the numeric SRG identifier.</p>
 */
public final class Forge1201SrgMemberNameMap {
    private record Key(String owner, String name, String descriptor) {}

    private final Map<Key, String> methods;
    private final Map<Key, String> fields;

    private Forge1201SrgMemberNameMap(Map<Key, String> methods, Map<Key, String> fields) {
        this.methods = Map.copyOf(methods);
        this.fields = Map.copyOf(fields);
    }

    public static Forge1201SrgMemberNameMap empty() {
        return new Forge1201SrgMemberNameMap(Map.of(), Map.of());
    }

    public static Forge1201SrgMemberNameMap load(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Forge 1.20.1 SRG member mapping file does not exist: " + path);
        }

        Map<Key, String> methods = new HashMap<>();
        Map<Key, String> fields = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) continue;

                String[] parts = line.split("\\t", -1);
                if (parts.length != 5 || parts[1].isBlank() || parts[2].isBlank()
                        || parts[3].isBlank() || parts[4].isBlank()) {
                    throw new IOException("Malformed SRG member mapping at " + path + ":" + lineNumber);
                }

                Map<Key, String> destination = switch (parts[0]) {
                    case "METHOD" -> methods;
                    case "FIELD" -> fields;
                    default -> throw new IOException(
                            "Unknown SRG member mapping kind '" + parts[0] + "' at " + path + ":" + lineNumber);
                };

                Key key = new Key(parts[1], parts[2], parts[3]);
                String previous = destination.putIfAbsent(key, parts[4]);
                if (previous != null && !previous.equals(parts[4])) {
                    throw new IOException("Conflicting SRG member mapping for " + key
                            + " at " + path + ":" + lineNumber);
                }
            }
        }
        return new Forge1201SrgMemberNameMap(methods, fields);
    }

    public String methodName(String owner, String sourceName, String descriptor) {
        return methods.get(new Key(owner, sourceName, descriptor));
    }

    public String fieldName(String owner, String sourceName, String descriptor) {
        return fields.get(new Key(owner, sourceName, descriptor));
    }

    public int methodMappingCount() {
        return methods.size();
    }

    public int fieldMappingCount() {
        return fields.size();
    }

    public static boolean isSrgMethodName(String name) {
        return name != null && (name.matches("m_[0-9]+_") || name.matches("func_[0-9]+_[a-zA-Z]"));
    }

    public static boolean isSrgFieldName(String name) {
        return name != null && (name.matches("f_[0-9]+_") || name.matches("field_[0-9]+_[a-zA-Z]"));
    }
}
