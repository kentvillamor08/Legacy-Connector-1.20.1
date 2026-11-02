package org.kylepitcock.legacyconnector.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Forge1201JarInspectorTest {
    @TempDir
    Path tempDir;

    @Test
    void recognizesForge1201JarFromModsToml() throws IOException {
        Path jar = createJar("META-INF/mods.toml", forge1201Metadata());

        Forge1201JarInfo info = LegacyJarInspector.inspectForge1201(jar);

        assertNotNull(info);
        assertEquals("[1.20.1,1.20.2)", info.minecraftVersionRange());
        assertEquals("[47.3.0,)", info.forgeVersionRange());
        assertEquals("alien_evolution", info.mods().get(0).modId());
        assertEquals("Alien Evolution", info.displayName());
    }

    @Test
    void ignoresNeoForgeMetadata() throws IOException {
        Path jar = createJar("META-INF/neoforge.mods.toml", forge1201Metadata());

        assertNull(LegacyJarInspector.inspectForge1201(jar));
    }

    @Test
    void ignoresOtherMinecraftVersions() throws IOException {
        String metadata = forge1201Metadata().replace("[1.20.1,1.20.2)", "[1.20.2,1.20.3)");
        Path jar = createJar("META-INF/mods.toml", metadata);

        assertNull(LegacyJarInspector.inspectForge1201(jar));
    }

    @Test
    void requiresAForgeDependency() throws IOException {
        String metadata = forge1201Metadata().replace("""
                [[dependencies.alien_evolution]]
                modId="forge"
                mandatory=true
                versionRange="[47.3.0,)"
                ordering="NONE"
                side="BOTH"
                """, "");
        Path jar = createJar("META-INF/mods.toml", metadata);

        assertNull(LegacyJarInspector.inspectForge1201(jar));
    }

    private Path createJar(String metadataPath, String metadata) throws IOException {
        Path jar = Files.createTempFile(tempDir, "forge-1.20.1-", ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(metadataPath));
            out.write(metadata.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static String forge1201Metadata() {
        return """
                modLoader="javafml"
                loaderVersion="[47.2,)"
                license="MIT"

                [[mods]]
                modId="alien_evolution"
                version="1.0.0"
                displayName="Alien Evolution"
                description="Test metadata"

                [[dependencies.alien_evolution]]
                modId="minecraft"
                mandatory=true
                versionRange="[1.20.1,1.20.2)"
                ordering="NONE"
                side="BOTH"

                [[dependencies.alien_evolution]]
                modId="forge"
                mandatory=true
                versionRange="[47.3.0,)"
                ordering="NONE"
                side="BOTH"
                """;
    }
}
