package org.kylepitcock.legacyconnector.loader.tools.forge1201;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Bounded, read-only static Mixin configuration and target discovery for Forge 1.20.1 archives.
 *
 * <p>This first implementation slice analyzes the outer source archive only. It resolves declared
 * config resources and mixin class entries, reads both visible and invisible @Mixin annotations,
 * and checks mapped target class presence through the shared target index. It never loads or
 * executes mod classes or Mixin plugins. Nested archive resources, member annotations, injection
 * selectors, refmap content, and unified preflight integration are later increments.</p>
 */
public final class Forge1201MixinAnalyzer {
    public static final int MAX_ARCHIVE_ENTRIES = 100_000;
    public static final int MAX_CONFIG_COUNT = 256;
    public static final int MAX_CONFIG_BYTES = 1024 * 1024;
    public static final long MAX_TOTAL_CONFIG_BYTES = 8L * 1024 * 1024;
    public static final int MAX_MIXIN_CLASS_COUNT = 20_000;
    public static final int MAX_CLASS_BYTES = 8 * 1024 * 1024;
    public static final long MAX_TOTAL_CLASS_BYTES = 128L * 1024 * 1024;

    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";

    private Forge1201MixinAnalyzer() {}

    public enum DiscoverySource {
        MANIFEST,
        FORGE_METADATA,
        RESOURCE_HEURISTIC
    }

    public enum ConfigStatus {
        PARSED,
        CONFIG_MISSING,
        CONFIG_INVALID,
        CONFIG_LIMIT_EXCEEDED,
        CONFIG_DUPLICATE_ENTRY,
        UNSAFE_CONFIG_PATH
    }

    public enum MixinClassStatus {
        PRESENT,
        MISSING,
        INVALID_NAME,
        DUPLICATE_ENTRY,
        CLASS_LIMIT_EXCEEDED,
        INVALID_CLASS
    }

    public enum TargetSelectorForm {
        CLASS_LITERAL,
        STRING_TARGET
    }

    public enum TargetStatus {
        TARGET_CLASS_PRESENT,
        TARGET_CLASS_MISSING,
        TARGET_REMAP_UNRESOLVED,
        NON_PLATFORM_TARGET
    }

    public enum ResourceStatus {
        NOT_DECLARED,
        PRESENT_VALID_JSON,
        MISSING,
        INVALID_PATH,
        DUPLICATE_ENTRY,
        LIMIT_EXCEEDED,
        INVALID_JSON
    }

    public enum PluginStatus {
        NOT_DECLARED,
        PRESENT_NOT_EXECUTED,
        MISSING,
        INVALID_CLASS_NAME,
        DUPLICATE_ENTRY
    }

    public record ConfigDeclaration(String path, DiscoverySource source) {
        public ConfigDeclaration {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("Config path is required");
            if (source == null) throw new IllegalArgumentException("Config discovery source is required");
        }
    }

    public record ConfigFinding(
            String archivePath,
            String configPath,
            List<DiscoverySource> discoverySources,
            ConfigStatus status,
            String packageName,
            Boolean required,
            String minimumVersion,
            String compatibilityLevel,
            String pluginClass,
            PluginStatus pluginStatus,
            String refmapPath,
            ResourceStatus refmapStatus,
            List<String> warnings) {
        public ConfigFinding {
            discoverySources = List.copyOf(discoverySources);
            warnings = List.copyOf(warnings);
        }
    }

    public record MixinClassFinding(
            String archivePath,
            String configPath,
            String section,
            String mixinClassName,
            String classEntryPath,
            MixinClassStatus status,
            boolean hasMixinAnnotation,
            List<String> warnings) {
        public MixinClassFinding {
            warnings = List.copyOf(warnings);
        }
    }

    public record TargetFinding(
            String archivePath,
            String configPath,
            String mixinClassName,
            String sourceTargetInternalName,
            String targetInternalName,
            TargetSelectorForm selectorForm,
            boolean remap,
            boolean pluginDependent,
            TargetStatus status,
            String mappingVersion,
            String evidence) {}

    public record Result(
            String sourceArchive,
            String targetProfile,
            String classMappingVersion,
            List<ConfigFinding> configs,
            List<MixinClassFinding> mixinClasses,
            List<TargetFinding> targets,
            List<String> warnings) {
        public Result {
            configs = List.copyOf(configs);
            mixinClasses = List.copyOf(mixinClasses);
            targets = List.copyOf(targets);
            warnings = List.copyOf(warnings);
        }
    }

    private record IndexedEntry(ZipEntry entry, int occurrences) {}

    private record ParsedConfig(
            String packageName,
            Boolean required,
            String minimumVersion,
            String compatibilityLevel,
            String pluginClass,
            String refmapPath,
            Map<String, List<String>> mixins,
            List<String> warnings) {}

    private record ParsedTarget(String selector, TargetSelectorForm form) {}

    /**
     * Analyze declared and heuristic config candidates. Minecraft class targets are only checked
     * when the caller supplies an explicit source-to-target class mapping. An absent mapping is
     * unresolved, never silently treated as an identity mapping.
     */
    public static Result analyze(
            Path sourceJar,
            Collection<ConfigDeclaration> declarations,
            Forge1201TargetApiVerifier.TargetIndex targetIndex,
            Map<String, String> sourceToTargetClassMappings,
            String classMappingVersion) throws IOException {
        if (sourceJar == null || !java.nio.file.Files.isRegularFile(sourceJar)) {
            throw new IllegalArgumentException("Mixin analysis input must be an existing archive: " + sourceJar);
        }
        if (declarations == null) throw new IllegalArgumentException("Mixin config declarations are required");
        if (targetIndex == null) throw new IllegalArgumentException("Target class index is required");

        Map<String, String> classMappings = sourceToTargetClassMappings == null
                ? Map.of() : Map.copyOf(sourceToTargetClassMappings);
        String mappingVersion = classMappingVersion == null || classMappingVersion.isBlank()
                ? "unavailable" : classMappingVersion;
        String archivePath = "source/" + sourceJar.getFileName();

        List<String> warnings = new ArrayList<>();
        Map<String, LinkedHashSet<DiscoverySource>> grouped = new TreeMap<>();
        for (ConfigDeclaration declaration : declarations) {
            grouped.computeIfAbsent(declaration.path(), ignored -> new LinkedHashSet<>()).add(declaration.source());
        }

        Map<String, IndexedEntry> entries = new HashMap<>();
        try (ZipFile zip = new ZipFile(sourceJar.toFile())) {
            var enumeration = zip.entries();
            int count = 0;
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (++count > MAX_ARCHIVE_ENTRIES) {
                    warnings.add("Archive entry inventory reached the " + MAX_ARCHIVE_ENTRIES
                            + " entry limit; remaining entries were not indexed.");
                    break;
                }
                entries.compute(entry.getName(), (name, current) ->
                        current == null ? new IndexedEntry(entry, 1)
                                : new IndexedEntry(current.entry(), current.occurrences() + 1));
            }

            List<ConfigFinding> configFindings = new ArrayList<>();
            List<MixinClassFinding> classFindings = new ArrayList<>();
            List<TargetFinding> targetFindings = new ArrayList<>();
            long totalConfigBytes = 0;
            long totalClassBytes = 0;
            int processedConfigs = 0;
            int processedMixins = 0;

            for (Map.Entry<String, LinkedHashSet<DiscoverySource>> candidate : grouped.entrySet()) {
                String configPath = candidate.getKey();
                List<DiscoverySource> sources = candidate.getValue().stream().sorted().toList();
                List<String> configWarnings = new ArrayList<>();

                if (!isSafeArchivePath(configPath)) {
                    configFindings.add(configFailure(archivePath, configPath, sources,
                            ConfigStatus.UNSAFE_CONFIG_PATH, "Config path is absolute, traversing, or malformed."));
                    continue;
                }
                IndexedEntry configEntry = entries.get(configPath);
                if (configEntry == null) {
                    configFindings.add(configFailure(archivePath, configPath, sources,
                            ConfigStatus.CONFIG_MISSING, "Discovered config resource is absent from the source archive."));
                    continue;
                }
                if (configEntry.occurrences() != 1) {
                    configFindings.add(configFailure(archivePath, configPath, sources,
                            ConfigStatus.CONFIG_DUPLICATE_ENTRY, "Multiple ZIP entries use the same config path."));
                    continue;
                }
                if (processedConfigs >= MAX_CONFIG_COUNT) {
                    configFindings.add(configFailure(archivePath, configPath, sources,
                            ConfigStatus.CONFIG_LIMIT_EXCEEDED, "Maximum config count was reached."));
                    continue;
                }
                processedConfigs++;

                byte[] configBytes;
                try {
                    configBytes = readBounded(zip, configEntry.entry(), MAX_CONFIG_BYTES);
                } catch (EntryLimitException exception) {
                    configFindings.add(configFailure(archivePath, configPath, sources,
                            ConfigStatus.CONFIG_LIMIT_EXCEEDED, exception.getMessage()));
                    continue;
                }
                totalConfigBytes += configBytes.length;
                if (totalConfigBytes > MAX_TOTAL_CONFIG_BYTES) {
                    configFindings.add(configFailure(archivePath, configPath, sources,
                            ConfigStatus.CONFIG_LIMIT_EXCEEDED, "Aggregate config byte budget was exceeded."));
                    continue;
                }

                ParsedConfig parsed;
                try {
                    parsed = parseConfig(configBytes);
                } catch (RuntimeException exception) {
                    configFindings.add(configFailure(archivePath, configPath, sources,
                            ConfigStatus.CONFIG_INVALID, "Invalid Mixin JSON: " + conciseMessage(exception)));
                    continue;
                }
                configWarnings.addAll(parsed.warnings());

                PluginStatus pluginStatus = inspectPlugin(parsed.pluginClass(), entries);
                ResourceStatus refmapStatus = inspectRefmap(parsed.refmapPath(), entries, zip);
                configFindings.add(new ConfigFinding(
                        archivePath, configPath, sources, ConfigStatus.PARSED,
                        parsed.packageName(), parsed.required(), parsed.minimumVersion(),
                        parsed.compatibilityLevel(), parsed.pluginClass(), pluginStatus,
                        parsed.refmapPath(), refmapStatus, configWarnings));

                boolean pluginDependent = parsed.pluginClass() != null;
                for (String section : List.of("mixins", "client", "server")) {
                    for (String mixinName : parsed.mixins().getOrDefault(section, List.of())) {
                        if (processedMixins >= MAX_MIXIN_CLASS_COUNT) {
                            classFindings.add(new MixinClassFinding(
                                    archivePath, configPath, section, mixinName, null,
                                    MixinClassStatus.CLASS_LIMIT_EXCEEDED, false,
                                    List.of("Maximum mixin class count was reached.")));
                            continue;
                        }
                        processedMixins++;

                        String classEntryPath = mixinClassEntry(parsed.packageName(), mixinName);
                        if (classEntryPath == null) {
                            classFindings.add(new MixinClassFinding(
                                    archivePath, configPath, section, mixinName, null,
                                    MixinClassStatus.INVALID_NAME, false,
                                    List.of("Mixin class name cannot be safely resolved from the config package.")));
                            continue;
                        }
                        IndexedEntry classEntry = entries.get(classEntryPath);
                        if (classEntry == null) {
                            classFindings.add(new MixinClassFinding(
                                    archivePath, configPath, section, mixinName, classEntryPath,
                                    MixinClassStatus.MISSING, false,
                                    List.of("Mixin class named by the config is absent from the source archive.")));
                            continue;
                        }
                        if (classEntry.occurrences() != 1) {
                            classFindings.add(new MixinClassFinding(
                                    archivePath, configPath, section, mixinName, classEntryPath,
                                    MixinClassStatus.DUPLICATE_ENTRY, false,
                                    List.of("Multiple ZIP entries use the same mixin class path.")));
                            continue;
                        }

                        byte[] classBytes;
                        try {
                            classBytes = readBounded(zip, classEntry.entry(), MAX_CLASS_BYTES);
                        } catch (EntryLimitException exception) {
                            classFindings.add(new MixinClassFinding(
                                    archivePath, configPath, section, mixinName, classEntryPath,
                                    MixinClassStatus.CLASS_LIMIT_EXCEEDED, false,
                                    List.of(exception.getMessage())));
                            continue;
                        }
                        totalClassBytes += classBytes.length;
                        if (totalClassBytes > MAX_TOTAL_CLASS_BYTES) {
                            classFindings.add(new MixinClassFinding(
                                    archivePath, configPath, section, mixinName, classEntryPath,
                                    MixinClassStatus.CLASS_LIMIT_EXCEEDED, false,
                                    List.of("Aggregate mixin class byte budget was exceeded.")));
                            continue;
                        }

                        ClassNode mixin = new ClassNode();
                        try {
                            new ClassReader(classBytes).accept(
                                    mixin, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        } catch (RuntimeException exception) {
                            classFindings.add(new MixinClassFinding(
                                    archivePath, configPath, section, mixinName, classEntryPath,
                                    MixinClassStatus.INVALID_CLASS, false,
                                    List.of("Invalid class file: " + conciseMessage(exception))));
                            continue;
                        }

                        List<ParsedTarget> parsedTargets = readMixinTargets(mixin);
                        boolean hasMixinAnnotation = hasMixinAnnotation(mixin);
                        List<String> classWarnings = new ArrayList<>();
                        if (!hasMixinAnnotation) {
                            classWarnings.add("Resolved class has no visible or invisible @Mixin annotation.");
                        }
                        classFindings.add(new MixinClassFinding(
                                archivePath, configPath, section, mixin.name.replace('/', '.'),
                                classEntryPath, MixinClassStatus.PRESENT, hasMixinAnnotation, classWarnings));

                        for (ParsedTarget target : parsedTargets) {
                            targetFindings.add(resolveTarget(
                                    archivePath, configPath, mixin.name.replace('/', '.'),
                                    target, parsedTargetsRemap(mixin), pluginDependent,
                                    classMappings, mappingVersion, targetIndex));
                        }
                    }
                }
            }

            configFindings.sort(Comparator.comparing(ConfigFinding::configPath));
            classFindings.sort(Comparator.comparing(MixinClassFinding::configPath)
                    .thenComparing(MixinClassFinding::section)
                    .thenComparing(MixinClassFinding::mixinClassName));
            targetFindings.sort(Comparator.comparing(TargetFinding::configPath)
                    .thenComparing(TargetFinding::mixinClassName)
                    .thenComparing(TargetFinding::sourceTargetInternalName)
                    .thenComparing(TargetFinding::selectorForm));
            return new Result(archivePath, "Minecraft 1.21.1 / NeoForge 21.1.258",
                    mappingVersion, configFindings, classFindings, targetFindings,
                    warnings.stream().distinct().sorted().toList());
        }
    }

    private static ConfigFinding configFailure(
            String archivePath, String configPath, List<DiscoverySource> sources,
            ConfigStatus status, String warning) {
        return new ConfigFinding(archivePath, configPath, sources, status,
                null, null, null, null, null, PluginStatus.NOT_DECLARED,
                null, ResourceStatus.NOT_DECLARED, List.of(warning));
    }

    private static ParsedConfig parseConfig(byte[] bytes) {
        JsonElement root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) throw new IllegalArgumentException("Config root must be a JSON object");
        JsonObject object = root.getAsJsonObject();
        String packageName = stringValue(object, "package");
        if (packageName == null || !isValidDottedName(packageName)) {
            throw new IllegalArgumentException("Config must contain a valid package name");
        }

        Map<String, List<String>> mixins = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        for (String section : List.of("mixins", "client", "server")) {
            List<String> names = new ArrayList<>();
            JsonElement value = object.get(section);
            if (value != null) {
                if (!value.isJsonArray()) {
                    warnings.add("Config section '" + section + "' is not an array and was not resolved.");
                } else {
                    for (JsonElement item : value.getAsJsonArray()) {
                        if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                            names.add(item.getAsString());
                        } else {
                            warnings.add("Config section '" + section + "' contains a non-string mixin entry.");
                        }
                    }
                }
            }
            names.sort(String::compareTo);
            mixins.put(section, List.copyOf(names));
        }
        return new ParsedConfig(
                packageName,
                booleanValue(object, "required"),
                stringValue(object, "minVersion"),
                stringValue(object, "compatibilityLevel"),
                stringValue(object, "plugin"),
                stringValue(object, "refmap"),
                Map.copyOf(mixins),
                List.copyOf(warnings));
    }

    private static PluginStatus inspectPlugin(String pluginClass, Map<String, IndexedEntry> entries) {
        if (pluginClass == null || pluginClass.isBlank()) return PluginStatus.NOT_DECLARED;
        String path = classResourcePath(pluginClass);
        if (path == null) return PluginStatus.INVALID_CLASS_NAME;
        IndexedEntry entry = entries.get(path);
        if (entry == null) return PluginStatus.MISSING;
        if (entry.occurrences() != 1) return PluginStatus.DUPLICATE_ENTRY;
        // Static analysis intentionally does not load, instantiate, or execute the plugin.
        return PluginStatus.PRESENT_NOT_EXECUTED;
    }

    private static ResourceStatus inspectRefmap(
            String refmapPath, Map<String, IndexedEntry> entries, ZipFile zip) {
        if (refmapPath == null || refmapPath.isBlank()) return ResourceStatus.NOT_DECLARED;
        if (!isSafeArchivePath(refmapPath)) return ResourceStatus.INVALID_PATH;
        IndexedEntry entry = entries.get(refmapPath);
        if (entry == null) return ResourceStatus.MISSING;
        if (entry.occurrences() != 1) return ResourceStatus.DUPLICATE_ENTRY;
        try {
            byte[] bytes = readBounded(zip, entry.entry(), MAX_CONFIG_BYTES);
            JsonElement json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return json.isJsonObject() ? ResourceStatus.PRESENT_VALID_JSON : ResourceStatus.INVALID_JSON;
        } catch (EntryLimitException exception) {
            return ResourceStatus.LIMIT_EXCEEDED;
        } catch (RuntimeException | IOException exception) {
            return ResourceStatus.INVALID_JSON;
        }
    }

    private static List<ParsedTarget> readMixinTargets(ClassNode mixin) {
        List<ParsedTarget> targets = new ArrayList<>();
        for (List<AnnotationNode> annotations : List.of(
                mixin.visibleAnnotations == null ? List.of() : mixin.visibleAnnotations,
                mixin.invisibleAnnotations == null ? List.of() : mixin.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_ANNOTATION.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    String key = String.valueOf(annotation.values.get(i));
                    Object value = annotation.values.get(i + 1);
                    if (!(value instanceof List<?> values)) continue;
                    for (Object item : values) {
                        if (key.equals("value") && item instanceof Type type
                                && type.getSort() == Type.OBJECT) {
                            targets.add(new ParsedTarget(type.getInternalName(), TargetSelectorForm.CLASS_LITERAL));
                        } else if (key.equals("targets") && item instanceof String name) {
                            targets.add(new ParsedTarget(name.replace('.', '/'), TargetSelectorForm.STRING_TARGET));
                        }
                    }
                }
            }
        }
        return targets.stream().distinct()
                .sorted(Comparator.comparing(ParsedTarget::selector).thenComparing(ParsedTarget::form)).toList();
    }

    private static boolean hasMixinAnnotation(ClassNode mixin) {
        return hasAnnotation(mixin.visibleAnnotations) || hasAnnotation(mixin.invisibleAnnotations);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(annotation -> MIXIN_ANNOTATION.equals(annotation.desc));
    }

    private static boolean parsedTargetsRemap(ClassNode mixin) {
        for (List<AnnotationNode> annotations : List.of(
                mixin.visibleAnnotations == null ? List.of() : mixin.visibleAnnotations,
                mixin.invisibleAnnotations == null ? List.of() : mixin.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_ANNOTATION.equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if ("remap".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof Boolean value) {
                        return value;
                    }
                }
            }
        }
        return true;
    }

    private static TargetFinding resolveTarget(
            String archivePath, String configPath, String mixinClassName,
            ParsedTarget selector, boolean remap, boolean pluginDependent,
            Map<String, String> classMappings, String mappingVersion,
            Forge1201TargetApiVerifier.TargetIndex targetIndex) throws IOException {
        String source = selector.selector();
        String target = null;
        String evidence;
        boolean platformTarget = isPlatformTarget(source);

        if (source.startsWith("net/minecraftforge/")) {
            Forge1201MappingCatalog.Decision decision = Forge1201MappingCatalog.classify(source);
            if (decision.targetInternalName() != null
                    && (decision.kind() == Forge1201MappingCatalog.Kind.PACKAGE_RELOCATION_CANDIDATE
                            || decision.kind() == Forge1201MappingCatalog.Kind.EXPLICIT_TYPE_REPLACEMENT)) {
                target = decision.targetInternalName();
                evidence = decision.reason();
            } else {
                evidence = decision.reason();
            }
        } else if (source.startsWith("net/neoforged/")) {
            target = source;
            evidence = "Selector already uses the NeoForge target namespace.";
        } else if (source.startsWith("net/minecraft/") || source.startsWith("com/mojang/")) {
            target = classMappings.get(source);
            evidence = target == null
                    ? "No explicit source-to-target class mapping was supplied; identity was not assumed."
                    : "Resolved by the supplied versioned source-to-target class mapping.";
        } else {
            evidence = platformTarget
                    ? "Target belongs to a platform namespace without a reviewed mapping."
                    : "Target is outside the Minecraft/Forge/NeoForge namespaces inspected by this analyzer.";
        }

        TargetStatus status;
        if (!platformTarget) {
            status = TargetStatus.NON_PLATFORM_TARGET;
        } else if (target == null) {
            status = TargetStatus.TARGET_REMAP_UNRESOLVED;
        } else {
            status = targetIndex.classInfo(target).isPresent()
                    ? TargetStatus.TARGET_CLASS_PRESENT : TargetStatus.TARGET_CLASS_MISSING;
        }
        return new TargetFinding(archivePath, configPath, mixinClassName, source, target,
                selector.form(), remap, pluginDependent, status, mappingVersion, evidence);
    }

    private static boolean isPlatformTarget(String internalName) {
        return internalName.startsWith("net/minecraft/")
                || internalName.startsWith("com/mojang/")
                || internalName.startsWith("net/minecraftforge/")
                || internalName.startsWith("net/neoforged/");
    }

    private static String mixinClassEntry(String packageName, String className) {
        if (className == null || className.isBlank() || className.indexOf('\0') >= 0) return null;
        String dotted = packageName + "." + className.replace('/', '.');
        if (!isValidDottedName(dotted)) return null;
        return dotted.replace('.', '/') + ".class";
    }

    private static String classResourcePath(String dottedClassName) {
        if (dottedClassName == null || !isValidDottedName(dottedClassName)) return null;
        return dottedClassName.replace('.', '/') + ".class";
    }

    private static boolean isValidDottedName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
                || name.contains("..") || name.indexOf('\0') >= 0) return false;
        for (String segment : name.split("\\.", -1)) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) return false;
            for (int i = 1; i < segment.length(); i++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(i)) && segment.charAt(i) != '$') return false;
            }
        }
        return true;
    }

    private static boolean isSafeArchivePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("\\") || path.indexOf('\0') >= 0 || path.matches("^[A-Za-z]:.*")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : null;
    }

    private static byte[] readBounded(ZipFile zip, ZipEntry entry, int limit) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new EntryLimitException("Archive entry exceeded " + limit + " bytes.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static final class EntryLimitException extends IOException {
        private EntryLimitException(String message) {
            super(message);
        }
    }
}
