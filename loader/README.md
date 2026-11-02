# Forge loader module

This standalone Gradle project compiles the early-loading translator against Minecraft Forge 1.20.1's FML/ForgeSPI APIs. It is intentionally separate from the main runtime build while the much larger runtime API migration is underway.

From the repository root:

```bash
./gradlew -p loader clean build
```

On Windows:

```bat
gradlew.bat -p loader clean build
```

The current toolchain uses Java 21 for compilation. The produced loader module is an intermediate development artifact, not yet the distributable Legacy Connector jar: the Forge runtime mod and final bundling task still need to be ported.
