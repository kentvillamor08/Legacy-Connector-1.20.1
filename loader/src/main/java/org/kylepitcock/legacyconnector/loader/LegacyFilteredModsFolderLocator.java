package org.kylepitcock.legacyconnector.loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;
import net.minecraftforge.fml.loading.moddiscovery.AbstractModProvider;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.forgespi.locating.ModFileLoadingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge's ordinary mods-folder locator, with one important exception: original legacy Forge jars
 * belong to LegacyModLocator and must not be offered to Forge's normal metadata parser.
 *
 * <p>This does not change, move, or rename anything in the user's mods folder. The translated jars
 * are returned separately by {@link LegacyModLocator}.</p>
 */
final class LegacyFilteredModsFolderLocator extends AbstractModProvider implements IModLocator {
    private static final Logger LOG = LoggerFactory.getLogger("Legacy Connector");
    private static final String MODS_TOML = "META-INF/mods.toml";
    private static final String MANIFEST = "META-INF/MANIFEST.MF";

    private final Path modFolder;
    private final String locatorName;

    LegacyFilteredModsFolderLocator(Path modFolder, String locatorName) {
        this.modFolder = modFolder;
        this.locatorName = locatorName;
    }

    @Override
    public String name() {
        return locatorName;
    }

    @Override
    public List<IModLocator.ModFileOrException> scanMods() {
        List<IModLocator.ModFileOrException> candidates = new ArrayList<>();
        Set<Path> excluded = new HashSet<>(ModDirTransformerDiscoverer.allExcluded());

        if (!Files.isDirectory(modFolder)) return candidates;

        try (Stream<Path> files = Files.list(modFolder)) {
            for (Path path : files
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList()) {
                if (excluded.contains(path)) continue;

                try {
                    // Old Forge mods can carry legacy metadata or manifest markers which Forge's current
                    // mod parser cannot interpret. They are translated by LegacyModLocator instead.
                    if (LegacyJarInspector.inspect(path) != null) {
                        LOG.debug("Legacy Connector: leaving original legacy jar {} to the translator", path.getFileName());
                        continue;
                    }

                    // Match Forge's intended ignore-unknown behavior explicitly. Some FML versions route
                    // an unknown jar through the error path even when the caller asks to ignore it.
                    if (!looksLikeForgeCandidate(path)) {
                        LOG.debug("Legacy Connector: ignoring unrecognized jar in {}: {}", modFolder, path.getFileName());
                        continue;
                    }

                    IModLocator.ModFileOrException candidate = createMod(path);
                    if (candidate != null) candidates.add(candidate);
                } catch (ModFileLoadingException e) {
                    candidates.add(new IModLocator.ModFileOrException(null, e));
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Legacy Connector: could not inspect mod candidate {}", path, e);
                    candidates.add(new IModLocator.ModFileOrException(null,
                            new ModFileLoadingException("Could not inspect mod candidate " + path.getFileName() + ": " + e)));
                }
            }
        } catch (IOException e) {
            LOG.error("Legacy Connector: could not scan mods directory {}", modFolder, e);
            candidates.add(new IModLocator.ModFileOrException(null,
                    new ModFileLoadingException("Could not scan mods directory " + modFolder + ": " + e)));
        }

        return candidates;
    }

    private static boolean looksLikeForgeCandidate(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.getEntry(MODS_TOML) != null) return true;

            ZipEntry manifestEntry = zip.getEntry(MANIFEST);
            if (manifestEntry == null) return false;

            try (var input = zip.getInputStream(manifestEntry)) {
                Manifest manifest = new Manifest(input);
                return manifest.getMainAttributes().getValue("FMLModType") != null;
            }
        }
    }

    @Override
    public String toString() {
        return "{" + locatorName + " locator at " + modFolder + "}";
    }
}
