# Forge 1.20.1 Port

This branch is the working continuation of Legacy Connector for **Minecraft 1.20.1 on Forge**.
The upstream `master` branch remains the source reference and is not modified by this port.

## Target

- Minecraft: 1.20.1
- Loader: Minecraft Forge 47.3.0 baseline
- Legacy mod formats: Forge 1.7.10 and Forge 1.12.2
- Build toolchain: JDK 21.
- Runtime bytecode target: Java 21 for the root runtime, because the upstream mixin/runtime source currently uses Java 21 language and bytecode features.
- The loader subproject remains Java 17 bytecode so Forge can load the early discovery service. The bundled mod declares a Java 21 runtime feature requirement.
- Forge 1.20.1's minimum Java baseline is Java 17; this port intentionally chooses Java 21, which must be verified against the selected Forge build in local client/server tests.

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

## Current Forge input limitation

Forge's stock folder locator scans only the top-level `mods/` directory and does not expose NeoForge's path-claiming API. Until we add and verify a safe alternative, original legacy jars must be placed in `mods/1.7.10/` or `mods/1.12.2/`. Do not treat top-level legacy jar support as implemented.

## Port sequence

### A. Forge bootstrap and discovery
- [x] Add an isolated standalone Gradle build for the Forge loader module.
- [x] Implement and register the Forge `IModLocator` service.
- [x] Generate Forge `META-INF/mods.toml` metadata and Forge `@Mod` entrypoint descriptors for translated jars.
- [x] Generate entrypoint constructors using Forge's `FMLJavaModLoadingContext` and obtain the mod bus/container through its Forge methods.
- [x] Add a unit test for generated Forge metadata and entrypoint bytecode.
- [x] Build/compile the standalone loader against Forge 1.20.1 dependencies (local Gradle `clean build` succeeded; 10 tasks including unit tests).
- [ ] Add and embed the Forge runtime mod so the generated bootstrap has its real runtime implementation.
- [ ] Prove discovery with a tiny legacy fixture jar before adding the full compatibility matrix.

### B. Legacy translation pipeline
- [ ] Reuse bytecode inspection, MCP mapping, Java/JDK redirects, cross-version relocation, linking, API synthesis, caching, and report generation.
- [ ] Replace NeoForge-only discovery-pipeline calls with Forge candidate creation.
- [ ] Preserve 1.7.10 and 1.12.2 detection independently.

### C. Runtime and shim port
- [x] Add Forge `META-INF/mods.toml` runtime metadata, ForgeGradle build setup, and convert the main `@Mod` entry point to the Forge loading context (initial migration; compile verification pending).
- [ ] Finish migrating registration, event, capability, networking, client, resource, world-generation, entity, and GUI bridges; several event-bus imports and primary registry APIs have been converted, but many NeoForge-specific references remain.
- [ ] Audit Minecraft 1.21.1-to-1.20.1 API differences individually; do not use blind global replacements for class or method names.
- [ ] Keep compatibility fixes generic; mod-specific hooks remain isolated.

### D. Verification
- [x] Compile the standalone loader and pass its unit tests locally (user confirmed `BUILD SUCCESSFUL`).
- [ ] Compile the Forge runtime against Minecraft 1.20.1 / Forge 47.3.0.
- [ ] Launch a clean Forge 1.20.1 client and dedicated server.
- [ ] Load a minimal 1.7.10 fixture and a minimal 1.12.2 fixture.
- [ ] Test content registration, item/block use, entities, client rendering, GUIs, world generation, and dimensions.
- [ ] Run real legacy mod jars only after the fixture path is stable.

## Current status

- [x] Fork created from upstream.
- [x] Dedicated branch created: `port/forge-1.20.1`.
- [x] Upstream loader/runtime architecture inspected.
- [x] Forge 1.20.1 discovery contract inspected against Forge source.
- [x] Added the Forge `IModLocator` adapter and registered the service.
- [x] Added Forge `mods.toml` generation, modern-mod metadata detection, and context-based entrypoint generation.
- [x] Added a unit test and GitHub Actions build workflow for the standalone loader.
- [x] Added a standalone loader Gradle project using a JDK 21 toolchain and Java 17 bytecode output.
- [x] Compile and test the standalone Forge loader locally; user confirmed `BUILD SUCCESSFUL` (CI status not yet verified).
- [ ] Port and embed the runtime mod; the root runtime sources are still largely NeoForge-based.

This status file is intentionally explicit: a branch and a migration plan are not evidence of a successful build. Update the checklist as code and in-game tests are completed.
