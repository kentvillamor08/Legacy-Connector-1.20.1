# Forge 1.20.1 mods on NeoForge 1.21.1

## Goal

Keep Legacy Connector's host runtime on **Minecraft 1.21.1 + NeoForge 21.1.x + Java 21**. Add a new compatibility family that can translate selected **Minecraft 1.20.1 Forge 47.x gameplay/content mods** so they can run inside that host.

This is not a Forge 1.20.1 build of Legacy Connector, and it is not a plan to downgrade the runtime.

## Scope

In scope:

- Gameplay and content mods: items, blocks, entities, abilities, recipes, GUIs, rendering, networking, world content and their ordinary gameplay hooks.
- Required libraries/APIs when there is no usable 1.21.1 NeoForge release.
- Reusing native 1.21.1 NeoForge dependencies where they are available and compatible.
- Keeping the original input jars untouched; translate/cache derived jars as the existing loader does.

Out of scope for the initial implementation:

- Performance/optimization mods.
- Old-engine patches, launch tweakers, and coremods that modify vanilla internals.
- General support for every 1.20.1 mod on the first milestone.
- Loading both a translated old copy and a native 1.21.1 copy of the same mod at once.

A native 1.21.1 NeoForge mod should be used instead of its older 1.20.1 build. A 1.20.1 jar should only be translated when it supplies functionality that is not already available natively in the pack.

## First compatibility target: Alien Evolution

Alien Evolution is a useful first end-to-end target because it is a content-heavy 1.20.1 Forge mod, rather than an optimization or engine-patch mod.

Its published dependency list includes Palladium, GeckoLib, KubeJS, Rhino, Architectury API and Pehkui. The compatibility work must therefore distinguish:

1. Dependencies with usable 1.21.1 NeoForge builds, which should be supplied natively by the pack.
2. Dependencies with no suitable 1.21.1 build, which need their own compatibility work or a focused port.
3. Alien Evolution itself, whose Palladium-facing code and content must be made functional on the 1.21.1 host.

Palladium is expected to be a major part of this pilot because its currently listed game-version support is 1.20.1.

## Technical milestones

1. **Jar identification**
   - Recognize 1.20.1 Forge jars that contain `META-INF/mods.toml`.
   - Read their Minecraft/Forge requirements and mod declarations.
   - Do not capture native 1.21.1 NeoForge mods or unrelated Forge versions.
   - Detect and reject unsupported engine-patch/coremod jars with a useful report.

2. **Translation and metadata**
   - Add a distinct `V1_20_1` compatibility family.
   - Translate Forge 1.20.1 metadata and entrypoints into valid NeoForge 1.21.1 metadata/entrypoints.
   - Add version-aware class/member remapping without applying the old SRG-to-MCP logic blindly to modern Forge jars.
   - Keep dependency declarations accurate, including native dependency substitution and duplicate-mod protection.

3. **API compatibility**
   - Build a version-isolated 1.20.1 Forge/FML shim over the existing 1.21.1 NeoForge runtime.
   - Bridge only APIs needed by real content mods, prioritizing registration, items/blocks, entities, recipes, capabilities, events, networking, menus/screens and client rendering.
   - Handle 1.20.1-to-1.21.1 Minecraft API changes deliberately; do not use global package renames as a substitute for API adaptation.

4. **Pilot validation**
   - Start with the actual Alien Evolution Forge jar and its exact dependency set.
   - Test client and dedicated-server startup separately.
   - Verify registration, model/texture loading, transformations/abilities, animation, UI/HUD, networking and save/reload behavior.
   - Record each dependency and feature as working, partial or unsupported.

## Compatibility policy

This is an API compatibility layer, not a universal guarantee. A mod is supported only to the extent that the Minecraft/Forge APIs it uses have been bridged and its required dependencies are available. Compatibility reports should identify missing APIs and unsupported hooks rather than silently claiming success.

## Current status

- Added a separate metadata detector for jars whose `mods.toml` declares JavaFML 47.x, Minecraft 1.20.1 and Forge 47.x.
- The loader scans both `mods/` and `mods/1.20.1/`, then reports and safely skips recognized 1.20.1 jars instead of passing them to the 1.7.10/1.12.2 translator or NeoForge's native mod discovery.
- Added detector tests for a Forge 1.20.1 example, NeoForge metadata, a different Minecraft version and a missing Forge dependency.
- These changes still need a local loader test run. They do **not** translate or load Alien Evolution yet.

The existing 1.7.10/1.12.2 NeoForge 1.21.1 runtime remains the baseline. The 1.20.1 API bridge, bytecode adaptation and gameplay validation remain future milestones.
