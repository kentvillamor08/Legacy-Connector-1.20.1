package org.kylepitcock.legacyconnector.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Offline bytecode inventory for a Forge 1.20.1 mod.
 *
 * <p>This tool does not load or execute mod classes. It inventories the Minecraft/Forge symbols
 * referenced by a jar so a version-specific adapter can be designed from evidence rather than guesses.</p>
 */
public final class Forge1201ApiUsageAnalyzer {
    private static final String FORGE_MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";

    private Forge1201ApiUsageAnalyzer() {}

    public record ApiMemberReference(String owner, String name, String descriptor, int count) {
        public String display() {
            return owner.replace('/', '.') + "#" + name + descriptor + (count > 1 ? "  (x" + count + ")" : "");
        }
    }

    /**
     * A direct bytecode invocation from a mod-owned method to a Forge/optional API method.
     * This preserves the caller method, unlike the global constant-pool member inventory.
     */
    public record ApiMethodCallSite(
            String callerClass,
            String callerMethod,
            String callerDescriptor,
            String targetOwner,
            String targetName,
            String targetDescriptor,
            int opcode,
            int count) {
        public String display() {
            return targetOwner.replace('/', '.') + "#" + targetName + targetDescriptor
                    + " <= " + callerClass.replace('/', '.') + "#" + callerMethod + callerDescriptor
                    + " [" + opcodeName(opcode) + "]"
                    + (count > 1 ? " (x" + count + ")" : "");
        }
    }

    private record CallSiteKey(
            String callerClass,
            String callerMethod,
            String callerDescriptor,
            String targetOwner,
            String targetName,
            String targetDescriptor,
            int opcode) {}

    public record Analysis(
            Forge1201JarInfo metadata,
            int classCount,
            List<String> modEntrypoints,
            List<String> mixinConfigs,
            String coreModClass,
            Map<String, Integer> packageReferences,
            List<String> referencedApiClasses,
            List<ApiMethodCallSite> methodCallSites,
            List<ApiMemberReference> methodReferences,
            List<ApiMemberReference> fieldReferences) {

        public Analysis {
            modEntrypoints = List.copyOf(modEntrypoints);
            mixinConfigs = List.copyOf(mixinConfigs);
            packageReferences = Map.copyOf(packageReferences);
            referencedApiClasses = List.copyOf(referencedApiClasses);
            methodCallSites = List.copyOf(methodCallSites);
            methodReferences = List.copyOf(methodReferences);
            fieldReferences = List.copyOf(fieldReferences);
        }
    }

    private record MemberKey(String owner, String name, String descriptor) {}

    private static final class ApiReferenceCollector extends Remapper {
        private final Set<String> classes = new LinkedHashSet<>();
        private final Map<String, Integer> packages = new TreeMap<>();
        private final Map<MemberKey, Integer> methods = new HashMap<>();
        private final Map<MemberKey, Integer> fields = new HashMap<>();
        private final Map<CallSiteKey, Integer> callSites = new HashMap<>();

        private void recordClass(String internalName) {
            if (!isGameApi(internalName)) return;
            classes.add(internalName);
            String[] pieces = internalName.split("/");
            String packageName = String.join("/", java.util.Arrays.copyOf(pieces, Math.min(3, pieces.length)));
            packages.merge(packageName, 1, Integer::sum);
        }

        @Override
        public String map(String internalName) {
            recordClass(internalName);
            return internalName;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            // ClassRemapper's member-name callback is also an authoritative reference to the owner.
            // Record it here rather than relying on the order in which ASM visits descriptors and instructions.
            recordClass(owner);
            if (isGameApi(owner)) methods.merge(new MemberKey(owner, name, descriptor), 1, Integer::sum);
            return name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            recordClass(owner);
            if (isGameApi(owner)) fields.merge(new MemberKey(owner, name, descriptor), 1, Integer::sum);
            return name;
        }

        private void recordCallSite(
                String callerClass,
                String callerMethod,
                String callerDescriptor,
                int opcode,
                String targetOwner,
                String targetName,
                String targetDescriptor) {
            if (!isForgeOrOptionalApi(targetOwner)) return;
            CallSiteKey key = new CallSiteKey(
                    callerClass, callerMethod, callerDescriptor,
                    targetOwner, targetName, targetDescriptor, opcode);
            callSites.merge(key, 1, Integer::sum);
        }

        private static boolean isForgeOrOptionalApi(String name) {
            return name.startsWith("net/minecraftforge/")
                    || name.startsWith("top/theillusivec4/curios/")
                    || name.startsWith("com/illusivesoulworks/")
                    || name.startsWith("com/gelred/");
        }

        private static boolean isGameApi(String name) {
            return name.startsWith("net/minecraft/")
                    || name.startsWith("net/minecraftforge/")
                    || name.startsWith("com/mojang/blaze3d/")
                    || name.startsWith("com/mojang/math/")
                    || name.startsWith("org/spongepowered/asm/mixin/");
        }

        List<String> classList() {
            return classes.stream().sorted().toList();
        }

        Map<String, Integer> packageCounts() {
            return Map.copyOf(packages);
        }

        List<ApiMethodCallSite> methodCallSiteList() {
            return callSites.entrySet().stream()
                    .map(entry -> new ApiMethodCallSite(
                            entry.getKey().callerClass(),
                            entry.getKey().callerMethod(),
                            entry.getKey().callerDescriptor(),
                            entry.getKey().targetOwner(),
                            entry.getKey().targetName(),
                            entry.getKey().targetDescriptor(),
                            entry.getKey().opcode(),
                            entry.getValue()))
                    .sorted(Comparator.comparing(ApiMethodCallSite::targetOwner)
                            .thenComparing(ApiMethodCallSite::targetName)
                            .thenComparing(ApiMethodCallSite::targetDescriptor)
                            .thenComparing(ApiMethodCallSite::callerClass)
                            .thenComparing(ApiMethodCallSite::callerMethod)
                            .thenComparing(ApiMethodCallSite::callerDescriptor)
                            .thenComparingInt(ApiMethodCallSite::opcode))
                    .toList();
        }

        List<ApiMemberReference> methodList() {
            return toReferences(methods);
        }

        List<ApiMemberReference> fieldList() {
            return toReferences(fields);
        }

        private static List<ApiMemberReference> toReferences(Map<MemberKey, Integer> source) {
            return source.entrySet().stream()
                    .map(entry -> new ApiMemberReference(
                            entry.getKey().owner(),
                            entry.getKey().name(),
                            entry.getKey().descriptor(),
                            entry.getValue()))
                    .sorted(Comparator.comparingInt(ApiMemberReference::count).reversed()
                            .thenComparing(ApiMemberReference::owner)
                            .thenComparing(ApiMemberReference::name)
                            .thenComparing(ApiMemberReference::descriptor))
                    .toList();
        }
    }

    public static Analysis analyze(Path jarPath) throws IOException {
        Forge1201JarInfo metadata = LegacyJarInspector.inspectForge1201(jarPath);
        if (metadata == null) {
            throw new IllegalArgumentException("The file is not recognized as a Forge 1.20.1 mod jar: " + jarPath);
        }

        ApiReferenceCollector references = new ApiReferenceCollector();
        List<String> entrypoints = new ArrayList<>();
        List<String> mixinConfigs = new ArrayList<>();
        String coreModClass = null;
        int classCount = 0;

        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry manifestEntry = zip.getEntry(JarFile.MANIFEST_NAME);
            if (manifestEntry != null) {
                try (var in = zip.getInputStream(manifestEntry)) {
                    Manifest manifest = new Manifest(in);
                    coreModClass = manifest.getMainAttributes().getValue("FMLCorePlugin");
                    String configs = manifest.getMainAttributes().getValue("MixinConfigs");
                    if (configs != null) {
                        for (String config : configs.split(",")) {
                            if (!config.isBlank()) mixinConfigs.add(config.trim());
                        }
                    }
                }
            }

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                classCount++;
                try (var in = zip.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(in);
                    reader.accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9) {
                        private String className;

                        @Override
                        public void visit(int version, int access, String name, String signature,
                                          String superName, String[] interfaces) {
                            className = name;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            // Preserve the containing mod method for each direct Forge/optional API call.
                            // This is separate from the global member inventory, which only knows that
                            // a symbolic reference exists somewhere in the class.
                            String callerClass = className;
                            String callerMethod = name;
                            String callerDescriptor = descriptor;
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitMethodInsn(int opcode, String owner, String targetName,
                                                            String targetDescriptor, boolean isInterface) {
                                    references.recordCallSite(
                                            callerClass, callerMethod, callerDescriptor,
                                            opcode, owner, targetName, targetDescriptor);
                                }
                            };
                        }

                        @Override
                        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!FORGE_MOD_ANNOTATION.equals(descriptor)) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String name, Object value) {
                                    if (name.equals("value") || name.equals("modid")) {
                                        entrypoints.add(className + " (" + value + ")");
                                    }
                                }
                            };
                        }
                    }, references), 0);
                } catch (RuntimeException malformedClass) {
                    throw new IOException("Cannot analyze class " + entry.getName() + " in " + jarPath, malformedClass);
                }
            }
        }

        return new Analysis(
                metadata,
                classCount,
                entrypoints.stream().distinct().sorted().toList(),
                mixinConfigs.stream().distinct().sorted().toList(),
                coreModClass,
                references.packageCounts(),
                references.classList(),
                references.methodCallSiteList(),
                references.methodList(),
                references.fieldList());
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            default -> "OPCODE_" + opcode;
        };
    }

    public static void print(Analysis analysis) {
        System.out.println("Forge 1.20.1 API inventory");
        System.out.println("Jar: " + analysis.metadata().path().getFileName());
        System.out.println("Mods: " + analysis.metadata().mods().stream()
                .map(mod -> mod.modId() + " " + mod.version() + " (" + mod.displayName() + ")")
                .reduce((a, b) -> a + ", " + b).orElse("none"));
        System.out.println("Declared dependencies:");
        for (Forge1201JarInfo.Mod mod : analysis.metadata().mods()) {
            for (Forge1201JarInfo.Dependency dependency : mod.dependencies()) {
                System.out.println("  " + (dependency.mandatory() ? "required " : "optional ")
                        + dependency.modId() + " " + dependency.versionRange()
                        + " [" + dependency.ordering() + ", " + dependency.side() + "]");
            }
        }
        System.out.println("Classes scanned: " + analysis.classCount());
        System.out.println("Forge @Mod entrypoints: " + analysis.modEntrypoints());
        System.out.println("Mixin configs: " + (analysis.mixinConfigs().isEmpty() ? "none declared" : analysis.mixinConfigs()));
        System.out.println("Coremod class: " + (analysis.coreModClass() == null ? "none" : analysis.coreModClass()));

        System.out.println("\nReferenced API packages:");
        analysis.packageReferences().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> System.out.println("  " + entry.getKey().replace('/', '.') + ": " + entry.getValue()));

        System.out.println("\nReferenced API classes (" + analysis.referencedApiClasses().size() + "):");
        analysis.referencedApiClasses().forEach(name -> System.out.println("  " + name.replace('/', '.')));

        System.out.println("\nForge / optional API method call sites (" + analysis.methodCallSites().size() + "):");
        analysis.methodCallSites().forEach(callSite -> System.out.println("  " + callSite.display()));

        System.out.println("\nMost-used API method references:");
        analysis.methodReferences().stream().limit(80).forEach(reference -> System.out.println("  " + reference.display()));

        System.out.println("\nMost-used API field references:");
        analysis.fieldReferences().stream().limit(50).forEach(reference -> System.out.println("  " + reference.display()));
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: inspectForge1201 -Pforge1201Jar=<path-to-forge-1.20.1-jar>");
        }
        print(analyze(Path.of(args[0])));
    }
}
