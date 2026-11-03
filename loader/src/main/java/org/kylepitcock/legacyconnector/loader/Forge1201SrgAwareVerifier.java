package org.kylepitcock.legacyconnector.loader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SRG-aware front end for the read-only 1.20.1 -> 1.21.1 target API verifier.
 *
 * <p>It normalizes only a copy of the analyzer's member-reference inventory.
 * The input JAR is never modified. Source SRG IDs without an exact owner and
 * descriptor mapping are reported separately and are not mislabeled as missing
 * target members.</p>
 */
public final class Forge1201SrgAwareVerifier {
    private Forge1201SrgAwareVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: Forge1201SrgAwareVerifier <jar> <source|translated> <srg-mappings.tsv> <target-version>");
        }

        Path jar = Path.of(args[0]);
        boolean translated = switch (args[1]) {
            case "source" -> false;
            case "translated" -> true;
            default -> throw new IllegalArgumentException("Mode must be 'source' or 'translated'");
        };
        Forge1201SrgMemberNameMap names = Forge1201SrgMemberNameMap.load(Path.of(args[2]));
        Forge1201ApiUsageAnalyzer.Analysis original = translated
                ? Forge1201ApiUsageAnalyzer.analyzeTranslated(jar)
                : Forge1201ApiUsageAnalyzer.analyze(jar);

        List<Forge1201ApiUsageAnalyzer.ApiMemberReference> methods = new ArrayList<>();
        List<Forge1201ApiUsageAnalyzer.ApiMemberReference> fields = new ArrayList<>();
        List<String> unmappedMethods = new ArrayList<>();
        List<String> unmappedFields = new ArrayList<>();
        int normalizedMethods = 0;
        int normalizedFields = 0;

        for (var reference : original.methodReferences()) {
            boolean candidate = isTargetCandidate(reference.owner());
            if (candidate && Forge1201SrgMemberNameMap.isSrgMethodName(reference.name())) {
                String mapped = names.methodName(reference.owner(), reference.name(), reference.descriptor());
                if (mapped == null) {
                    unmappedMethods.add(reference.display());
                    continue;
                }
                methods.add(new Forge1201ApiUsageAnalyzer.ApiMemberReference(
                        reference.owner(), mapped, reference.descriptor(), reference.count()));
                normalizedMethods++;
            } else {
                methods.add(reference);
            }
        }

        for (var reference : original.fieldReferences()) {
            boolean candidate = isTargetCandidate(reference.owner());
            if (candidate && Forge1201SrgMemberNameMap.isSrgFieldName(reference.name())) {
                String mapped = names.fieldName(reference.owner(), reference.name(), reference.descriptor());
                if (mapped == null) {
                    unmappedFields.add(reference.display());
                    continue;
                }
                fields.add(new Forge1201ApiUsageAnalyzer.ApiMemberReference(
                        reference.owner(), mapped, reference.descriptor(), reference.count()));
                normalizedFields++;
            } else {
                fields.add(reference);
            }
        }

        Forge1201ApiUsageAnalyzer.Analysis normalized = new Forge1201ApiUsageAnalyzer.Analysis(
                original.metadata(),
                original.classCount(),
                original.modEntrypoints(),
                original.mixinConfigs(),
                original.coreModClass(),
                original.packageReferences(),
                original.referencedApiClasses(),
                original.methodCallSites(),
                methods,
                fields);

        ClassLoader targetLoader = ClassLoader.getSystemClassLoader();
        Forge1201TargetApiVerifier.Result result = Forge1201TargetApiVerifier.verify(
                normalized,
                internalName -> targetLoader.getResourceAsStream(internalName + ".class"));

        System.out.println("Forge 1.20.1 SRG member-name normalization:");
        System.out.println("  source mapping method entries: " + names.methodMappingCount());
        System.out.println("  source mapping field entries: " + names.fieldMappingCount());
        System.out.println("  member references normalized: " + normalizedMethods + " methods, "
                + normalizedFields + " fields");
        System.out.println("  candidate SRG methods without exact mapping: " + unmappedMethods.size());
        unmappedMethods.forEach(value -> System.out.println("  UNMAPPED METHOD: " + value));
        System.out.println("  candidate SRG fields without exact mapping: " + unmappedFields.size());
        unmappedFields.forEach(value -> System.out.println("  UNMAPPED FIELD: " + value));
        System.out.println();
        Forge1201TargetApiVerifier.print(result, args[3]);
    }

    private static boolean isTargetCandidate(String owner) {
        return Forge1201MappingCatalog.classify(owner).kind()
                == Forge1201MappingCatalog.Kind.PACKAGE_RELOCATION_CANDIDATE
                || Forge1201MappingCatalog.isReviewedTargetApiType(owner);
    }
}
