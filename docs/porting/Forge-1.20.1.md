# Forge 1.20.1 Port

This branch is the working continuation of Legacy Connector for **Minecraft 1.20.1 on Forge**.
The upstream `master` branch remains the source reference and is not modified by this port.

## Target

- Minecraft: 1.20.1
- Loader: Minecraft Forge 47.3.0 baseline
- Legacy mod formats: Forge 1.7.10 and Forge 1.12.2
- Build toolchain: JDK 21.
- Runtime bytecode target: Java 17, matching Forge 1.20.1's Java baseline.

## Architecture that must be preserved

Legacy Connector has two distinct halves:

1. **Loader/service layer**: discovers legacy jars in the versioned input folders, inspects their metadata, translates bytecode and resources, synthesizes missing legacy API types, caches translations, and emits translated mod candidates.
2. **Runtime/shim layer**: recreates the old Minecraft/Forge/FML APIs and bridges legacy registrations and behavior into the modern game's registries, events, rendering, networking, world generation, menus, entities, and capabilities.

This is not a normal mod recompile. The aim is to run unmodified legacy mod jars, with their original files left unchanged.

## Verified loader migration boundary

The current upstream loader entry point implements NeoForge's
`IModFileCandidateLocator` and receives an `ILaunchContext` /
`IDiscoveryPipeline`. Forge 1.20.1 instead uses ForgeSPI's
`net.minecraftforge.forgespi.locating.IModLocator`, whose discovery contract
is `scanMods()` returning `IModLocator.ModFileOrException` candidates.

The Forge-side loader therefore needs to:

- register as a Forge `IModLocator` service;
- create proper Forge `ModFile` candidates for the runtime jar, translated legacy jars, synthesized API jars, shared-package jars, and any required bundled libraries;
- report translation failures through Forge's candidate/error contract and the log;
- preserve the existing scan, transform, link, synthesize, cache, split-package, and report behavior;
- for the initial Forge implementation, read legacy jars from `mods/1.7.10/` and `mods/1.12.2/`. Forge's top-level mods-folder locator scans independently and the Forge SPI does not offer NeoForge's `addLocated` exclusion mechanism; top-level legacy jars are therefore not supported yet.

The current NeoForge implementation must not be changed in-place until the Forge path is isolated and can be tested.

## Port sequence

### A. Forge bootstrap and discovery
- [x] Add an isolated standalone Gradle build for the Forge loader module.
- [x] Implement and register the Forge `IModLocator` service.
- [x] Generate Forge `META-INF/mods.toml` metadata and Forge `@Mod` entrypoint descriptors for translated jars.
- [ ] Build/compile the loader against the Forge 1.20.1 dependencies.
- [ ] Load the runtime mod and return translated jars as Forge mod candidates.
- [ ] Prove discovery with a tiny legacy fixture jar before adding the full compatibility matrix.

### B. Legacy translation pipeline
- [ ] Reuse bytecode inspection, MCP mapping, Java/JDK redirects, cross-version relocation, linking, API synthesis, caching, and report generation.
- [ ] Replace NeoForge-only discovery-pipeline calls with Forge candidate creation.
- [ ] Preserve 1.7.10 and 1.12.2 detection independently.

### C. Runtime and shim port
- [ ] Port runtime mod metadata and the `@Mod` entry point.
- [ ] Port registration, event, capability, networking, client, resource, world-generation, entity, and GUI bridges.
- [ ] Audit Minecraft 1.21.1-to-1.20.1 API differences individually; do not use blind global replacements for class or method names.
- [ ] Keep compatibility fixes generic; mod-specific hooks remain isolated.

### D. Verification
- [ ] Compile the loader and runtime separately.
- [ ] Launch a clean Forge 1.20.1 client and dedicated server.
- [ ] Load a minimal 1.7.10 fixture and a minimal 1.12.2 fixture.
- [ ] Test content registration, item/block use, entities, client rendering, GUIs, world generation, and dimensions.
- [ ] Run real legacy mod jars only after the fixture path is stable.

## Current status

- [x] Fork created from upstream.
- [x] Dedicated branch created: `port/forge-1.20.1`.
- [x] Upstream loader/runtime architecture inspected.
- [x] Forge 1.20.1 discovery contract inspected against Forge source.
- [x] Added the Forge `IModLocator` adapter and initial Forge metadata generation.
- [x] Added a standalone loader Gradle project with Java 17 bytecode output.
- [ ] Compile and test the Forge loader locally/CI; no successful build has been recorded yet.

This status file is intentionally explicit: a branch and a migration plan are not evidence of a successful build. Update the checklist as code and in-game tests are completed.
