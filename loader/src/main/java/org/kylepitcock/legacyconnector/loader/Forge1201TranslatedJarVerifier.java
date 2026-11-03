package org.kylepitcock.legacyconnector.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Read-only verifier for the output of {@code :loader:transformForge1201}.
 *
 * <p>It checks references that were relocated into NeoForge package namespaces against
 * the target compile classpath. Forge API references that remain in the JAR are listed
 * separately; they are not silently treated as target types. Minecraft member changes
 * and semantic API adaptations remain outside this namespace-level check.</p>
 */
public final class Forge1201TranslatedJarVerifier {
    private Forge1201TranslatedJarVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Usage: Forge1201TranslatedJarVerifier <path-to-preliminary-translated-jar>");
        }

        Path jar = Path.of(args[0]);
        Forge1201ApiUsageAnalyzer.Analysis analysis =
                Forge1201ApiUsageAnalyzer.analyzeTranslated(jar);
        Forge1201TargetApiVerifier.Result result =
                Forge1201TargetApiVerifier.verifyTranslated(jar, ClassLoader.getSystemClassLoader());

        String version = System.getProperty("legacyconnector.targetNeoForgeVersion", "unknown");
        System.out.println("Preliminary Forge 1.20.1 -> NeoForge " + version + " translated JAR audit");
        System.out.println("Mode: read-only ASM inspection; no mod or target classes were executed.");
        System.out.println("JAR: " + jar.toAbsolutePath().normalize());
        System.out.println("Classes scanned: " + analysis.classCount());

        System.out.println("\nRelocated NeoForge API classes:");
        System.out.println("  distinct references: " + result.candidateClassCount());
        System.out.println("  target class files found: " + result.verifiedClassCount());
        System.out.println("  missing target classes: " + result.missingTargetClasses().size());
        result.missingTargetClasses().forEach(name ->
                System.out.println("  MISSING CLASS: " + name.replace('/', '.')));

        System.out.println("\nRelocated NeoForge API method references:");
        System.out.println("  unique references: " + result.uniqueMethodReferences());
        System.out.println("  exact target/inherited matches: " + result.verifiedMethodReferences());
        System.out.println("  unresolved or changed: " + result.methodIssues().size());
        result.methodIssues().forEach(issue -> System.out.println("  " + issue.display()));

        System.out.println("\nRelocated NeoForge API field references:");
        System.out.println("  unique references: " + result.uniqueFieldReferences());
        System.out.println("  exact target/inherited matches: " + result.verifiedFieldReferences());
        System.out.println("  unresolved or changed: " + result.fieldIssues().size());
        result.fieldIssues().forEach(issue -> System.out.println("  " + issue.display()));

        List<String> remainingForgeTypes = analysis.referencedApiClasses().stream()
                .filter(name -> name.startsWith("net/minecraftforge/"))
                .sorted()
                .toList();
        List<String> manualTypes = remainingForgeTypes.stream()
                .filter(name -> Forge1201MappingCatalog.classify(name).kind()
                        == Forge1201MappingCatalog.Kind.MANUAL_ADAPTER_REQUIRED)
                .toList();
        List<String> unreviewedTypes = remainingForgeTypes.stream()
                .filter(name -> Forge1201MappingCatalog.classify(name).kind()
                        == Forge1201MappingCatalog.Kind.UNMAPPED_FORGE_API)
                .toList();

        System.out.println("\nForge API references remaining after package relocation:");
        System.out.println("  distinct types remaining: " + remainingForgeTypes.size());
        System.out.println("  known semantic-adapter types: " + manualTypes.size());
        manualTypes.forEach(name -> System.out.println("  MANUAL: " + name.replace('/', '.')));
        System.out.println("  unreviewed Forge API types: " + unreviewedTypes.size());
        unreviewedTypes.forEach(name -> System.out.println("  UNREVIEWED: " + name.replace('/', '.')));

        System.out.println("\nMinecraft-version API changes are not verified by this namespace/member check.");
        System.out.println("A matching target signature does not prove semantic compatibility.");
        System.out.println("Do not load this preliminary artifact as a supported mod.");
    }
}
