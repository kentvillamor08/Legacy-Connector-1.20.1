package org.kylepitcock.legacyconnector.loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the isolated Forge 1.20.1 preparation stages in order:
 * metadata conversion first, then reviewed ASM package relocations.
 *
 * <p>The output is an intermediate compatibility artifact, not a claim that every
 * referenced API has been adapted or that the JAR is ready for mod discovery.</p>
 */
public final class Forge1201JarTransformer {
    private Forge1201JarTransformer() {}

    public record Result(
            Path input,
            Path output,
            String inputSha256,
            String outputSha256,
            int metadataEntriesCopied,
            int signaturesRemoved,
            Forge1201BytecodeTransformer.Result bytecode) {}

    public static Result transform(
            Path input,
            Path output,
            String neoForgeLoaderRange,
            String neoForgeVersionRange,
            String minecraftVersionRange) throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("Input and output paths are required");
        }

        Path source = input.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (source.equals(destination)) {
            throw new IllegalArgumentException("The Forge 1.20.1 transform must not overwrite its source JAR");
        }
        Forge1201JarInfo metadata = LegacyJarInspector.inspectForge1201(source);
        if (metadata == null) {
            throw new IllegalArgumentException("Input is not a recognized Forge 1.20.1 mod JAR: " + source);
        }

        Path parent = destination.getParent();
        if (parent == null) throw new IOException("Output JAR must have a parent directory: " + destination);
        Files.createDirectories(parent);

        String prefix = destination.getFileName().toString();
        if (prefix.length() < 3) prefix = (prefix + "___").substring(0, 3);
        Path metadataStage = Files.createTempFile(parent, prefix, ".metadata.jar");
        try {
            Forge1201JarArchiveTransformer.Result metadataResult = Forge1201JarArchiveTransformer.transform(
                    source,
                    metadataStage,
                    neoForgeLoaderRange,
                    neoForgeVersionRange,
                    minecraftVersionRange);
            Forge1201BytecodeTransformer.Result bytecodeResult =
                    Forge1201BytecodeTransformer.transform(metadataStage, destination);

            return new Result(
                    source,
                    destination,
                    metadataResult.inputSha256(),
                    bytecodeResult.outputSha256(),
                    metadataResult.copiedEntries(),
                    metadataResult.removedSignatureEntries() + bytecodeResult.signaturesRemoved(),
                    bytecodeResult);
        } finally {
            Files.deleteIfExists(metadataStage);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: transformForge1201 <input.jar> <output.jar> "
                            + "<javafml-range> <neoforge-range> <minecraft-range>");
        }

        Result result = transform(
                Path.of(args[0]),
                Path.of(args[1]),
                args[2],
                args[3],
                args[4]);
        Forge1201BytecodeTransformer.Result bytecode = result.bytecode();

        System.out.println("Created preliminary Forge 1.20.1 -> NeoForge 1.21.1 artifact: " + result.output());
        System.out.println("  input SHA-256:  " + result.inputSha256());
        System.out.println("  output SHA-256: " + result.outputSha256());
        System.out.println("  classes scanned: " + bytecode.classesScanned());
        System.out.println("  classes rewritten: " + bytecode.classesChanged());
        System.out.println("  class entry paths relocated: " + bytecode.classEntriesRelocated());
        System.out.println("  resources copied: " + bytecode.resourcesCopied());
        System.out.println("  signature entries removed: " + result.signaturesRemoved());
        System.out.println("  reviewed API types package-relocated: " + bytecode.relocatedTypes().size());

        printTypes("Types requiring semantic adapters (left unchanged)", bytecode.manualAdapterTypes());
        printTypes("Unreviewed Forge API types (left unchanged)", bytecode.unmappedForgeApiTypes());

        System.out.println();
        System.out.println("WARNING: This is a preliminary mechanical transformation only.");
        System.out.println("Manual Forge API adapters, Minecraft 1.20.1 API changes, mod bootstrap,");
        System.out.println("networking, registration, and runtime behavior are not yet completed.");
        System.out.println("Do not add this artifact to a playable modpack or enable automatic discovery.");
    }

    private static void printTypes(String heading, List<String> types) {
        System.out.println(heading + ": " + types.size());
        for (String type : types) System.out.println("  " + type);
    }
}
