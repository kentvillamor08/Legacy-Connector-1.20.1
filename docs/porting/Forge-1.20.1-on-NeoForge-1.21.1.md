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

## Alien Evolution dependency profile

The current published Alien Evolution listing identifies these required dependencies: Palladium, GeckoLib, KubeJS, Rhino, Architectury API and Pehkui.

| Dependency | 1.21.1 NeoForge availability | Pilot handling |
|---|---|---|
| Palladium | The project listing is still on Minecraft 1.20.1; its NeoForge listing refers to that game version, not 1.21.1. | Treat as a core 1.20.1 compatibility target alongside Alien Evolution. |
| GeckoLib | A 1.21.1 NeoForge release is listed. | Prefer the native 1.21.1 release. |
| KubeJS | A 1.21.1 NeoForge release is listed. | Prefer the native 1.21.1 release. Confirm the exact required Rhino/other dependencies for the selected KubeJS file. |
| Rhino | A 1.21.1 NeoForge release is listed. | Prefer the native 1.21.1 release. |
| Architectury API | 1.21.x NeoForge support is listed. | Prefer a matching native 1.21.1 release. |
| Pehkui | A 1.21.1 NeoForge release is listed. | Prefer the native 1.21.1 release. |

This is a release-listing audit, not a successful runtime test. Pin exact file versions and check their dependency metadata when building the test pack. In particular, do not assume that a dependency's general project page means a particular file supports both the same Minecraft version and loader.

Primary references: Alien Evolution's [Modrinth page](https://modrinth.com/mod/alienevo) and [CurseForge page](https://www.curseforge.com/minecraft/mc-mods/alienevo); dependency projects' Modrinth pages for [Palladium](https://modrinth.com/mod/threetag-palladium), [GeckoLib](https://modrinth.com/mod/geckolib), [KubeJS](https://modrinth.com/mod/kubejs), [Rhino](https://modrinth.com/mod/rhino), [Architectury API](https://modrinth.com/mod/architectury-api) and [Pehkui](https://modrinth.com/mod/pehkui).

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
- The detector now extracts each `[[dependencies.<modid>]]` entry (mod ID, version range, mandatory flag, ordering and side) and includes a dependency summary in the safe-skip warning. The test fixture models Alien Evolution's six declared required libraries.
- Added a native dependency audit that scans installed NeoForge jars for mod IDs in `[[mods]]` sections only, then classifies Forge/Minecraft platform bridges, native ID candidates, and missing required/optional dependencies. The audit explicitly does not claim that a matching mod ID proves API or version compatibility.
- Added a regression test ensuring IDs mentioned only in another mod's dependency list are not mistaken for installed mods.
- These changes still need a local loader test run. They do **not** translate or load Alien Evolution yet.

The existing 1.7.10/1.12.2 NeoForge 1.21.1 runtime remains the baseline. The 1.20.1 API bridge, bytecode adaptation and gameplay validation remain future milestones.
