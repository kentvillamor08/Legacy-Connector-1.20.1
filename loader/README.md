# Forge loader module

This standalone Gradle project compiles the early-loading translator against Minecraft Forge 1.20.1's FML/ForgeSPI APIs. It is intentionally separate from the main runtime build while the much larger runtime API migration is underway.

## Legacy mod input folders

For this Forge port, place original legacy jars in the matching versioned subfolder:

- `mods/1.7.10/` for Forge 1.7.10 mods.
- `mods/1.12.2/` for Forge 1.12.2 mods.

Do not place original legacy jars directly in the top-level `mods/` folder yet. Forge 1.20.1's built-in mods-folder locator independently scans that folder and its `IModLocator` SPI does not provide NeoForge's `addLocated` exclusion mechanism. The versioned subfolders are scanned by Legacy Connector and are not picked up by Forge's normal top-level folder locator.

## Build

From the repository root:

```bash
./gradlew -p loader clean build
```

On Windows:

```bat
gradlew.bat -p loader clean build
```

The build uses a Java 21 toolchain but compiles loader bytecode for Java 17, matching the runtime baseline of Forge 1.20.1. The produced loader module is an intermediate development artifact, not yet the distributable Legacy Connector jar: the Forge runtime mod and final bundling task still need to be ported.
