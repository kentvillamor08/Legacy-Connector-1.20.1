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

## First compatibility target: Symbiote: A Bonding Experience

The current pilot is **Symbiote 1.1.3**, a Minecraft 1.20.1 Forge 47.x content mod. Its original JAR is kept in the user's private test repository and is used only as a local analysis/test input. Its All-Rights-Reserved binary and assets must not be copied into this public repository.

The static report for the exact JAR records:
- 278 class files and Java 17 bytecode (compatible with the Java 21 runtime at the bytecode level).
- Two mandatory metadata dependencies: Minecraft 1.20.1 and Forge 47.x.
- 121 distinct Forge API class references and 202 distinct Forge API field/method references.
- 1,220 distinct Minecraft field/method references.
- 123 Symbiote classes with direct Forge API references.
- One Curios API class reference, in `CuriosRebondCompat`; Curios is not declared as a mandatory dependency in the source metadata.
- An `EpicFightArmorDiagnostics` class that checks mod presence, with no direct Epic Fight API class reference detected.

The report is a static symbolic inventory, not a runtime trace. It does not prove that any class or code path loads successfully.

### Symbiote dependency and integration profile

| Dependency/API | Source evidence | Planned handling |
|---|---|---|
| Minecraft | Required `minecraft` dependency in `mods.toml` | Rewrite to the host's 1.21.1 range in derived metadata |
| Forge | Required `forge` dependency in `mods.toml` | Rewrite to the `neoforge` dependency in derived metadata |
| Curios | `CurioDropsEvent` referenced only by `CuriosRebondCompat`; not declared in `mods.toml` | Keep isolated as an optional integration; test with Curios present and absent |
| Epic Fight | Presence check by mod ID; no direct API type detected | Do not add a dependency unless deeper inspection finds actual API use |

The first porting target should be a dedicated bootstrap/registry slice, followed by networking, gameplay events and client rendering. The class-level inventory is documented in the private testbench; the public project should not contain the original test JAR or its assets.
## Technical milestones

1. **Jar identification**
   - Recognize 1.20.1 Forge jars that contain `META-INF/mods.toml`.
   - Read their Minecraft/Forge requirements and mod declarations.
   - Do not capture native 1.21.1 NeoForge mods or unrelated Forge versions.
   - Detect and reject unsupported engine-patch/coremod jars with a useful report.

2. **Dedicated metadata and archive path**
   - Keep the Forge 1.20.1 family separate from the 1.7.10/1.12.2 `LegacyVersion` enum and `LegacyJarTransformer`.
   - Convert Forge `mods.toml` to NeoForge `neoforge.mods.toml`, including Forge-to-NeoForge platform dependency IDs and the `mandatory` to `type` dependency syntax conversion.
   - Create a derived JAR in a separate cache; do not alter the user's original JAR.
   - Preserve unrelated archive entries and remove stale signature files after metadata changes.
   - Keep the current safe-skip discovery behavior until all bytecode/API translation stages are ready.

3. **Modern bytecode and mapping**
   - Add a pinned 1.20.1-to-1.21.1 mapping set for class, descriptor, field and method references.
   - Do not apply the old SRG-to-MCP logic to modern Forge jars.
   - Add narrowly scoped call-site adapters only for APIs whose semantics changed; do not use a global package rename as a substitute for API adaptation.

4. **Runtime API compatibility**
   - Keep runtime shims isolated from the pre-Minecraft loader service.
   - Bridge only APIs evidenced by supported mods, prioritizing entrypoints, registries, configuration, events, networking, menus/screens and client rendering.
   - Handle 1.20.1-to-1.21.1 Minecraft API changes deliberately.

5. **Pilot validation**
   - Start with Symbiote 1.1.3 and its exact JAR/metadata.
   - Test client and dedicated-server startup separately.
   - Verify registration, models/textures, entities, abilities, networking, UI/HUD and save/reload behavior in separate stages.
   - Record each feature as working, partial or unsupported; a metadata-translated JAR is not proof of gameplay compatibility.
## Compatibility policy

This is an API compatibility layer, not a universal guarantee. A mod is supported only to the extent that the Minecraft/Forge APIs it uses have been bridged and its required dependencies are available. Compatibility reports should identify missing APIs and unsupported hooks rather than silently claiming success.

## Inspecting the first real mod jar

The loader now includes an offline bytecode inventory tool. It does not define, load or execute the target mod. It reports:

- mods.toml mod IDs and declared dependencies.
- Forge @Mod entrypoint classes.
- Manifest-declared Mixin configurations and FML coremod entrypoints.
- Minecraft/Forge/Mixin classes referenced by the bytecode.
- Frequently referenced Minecraft/Forge method and field symbols.

After placing the actual Forge 1.20.1 jar somewhere accessible on disk, run from the project root:

```powershell
.\gradlew.bat :loader:inspectForge1201 -Pforge1201Jar="F:\path\to\symbiote-1.1.3.jar"
```

The report is printed to the Gradle console. The exact jar and dependency versions used for the pilot should be recorded; do not assume the mod's latest release or a dependency page alone defines the bytecode API surface.

Run the read-only mapping decision audit against the same original input JAR:

```powershell
.\\gradlew.bat :loader:auditForge1201Mappings -Pforge1201Jar="F:\\path\\to\\symbiote-1.1.3.jar"
```

This command classifies reviewed namespace moves, known semantic adapters and unreviewed Forge API types. It does not rewrite the JAR. A package relocation is only a candidate until the exact class and member signatures are verified against the pinned NeoForge 21.1.x artifact.

After the mapping catalog has been reviewed, verify the remaining namespace-relocation candidates against the **actual target game's compile classpath** configured by the root NeoForge ModDev project. The candidate count is expected to change as additional APIs are reclassified for manual migration:

```powershell
.\\gradlew.bat :verifyForge1201TargetApi -Pforge1201Jar="C:\\path\\to\\symbiote-1.1.3.jar"
```

This task runs in the root project's target compile classpath while the `:loader` module remains independent of Minecraft/NeoForge classes. It reads class-file structures with ASM and checks target class presence plus exact method/field descriptors, including inherited members where the visible hierarchy is complete. Missing classes, missing members, incomplete hierarchy lookups, and member descriptors that contain manual-adapter types are reported separately. No target class is loaded or initialized and no JAR bytes are changed. A structural match is not proof of behavioral compatibility; manual-adapter types and Minecraft 1.20.1-to-1.21.1 changes remain separate.
## Current status

- Added a separate metadata detector for jars whose `mods.toml` declares JavaFML 47.x, Minecraft 1.20.1 and Forge 47.x.
- The loader scans both `mods/` and `mods/1.20.1/`, then reports and safely skips recognized 1.20.1 jars instead of passing them to the 1.7.10/1.12.2 translator or NeoForge's native mod discovery.
- Added detector tests for a Forge 1.20.1 example, NeoForge metadata, a different Minecraft version and a missing Forge dependency.
- The detector now extracts each `[[dependencies.<modid>]]` entry (mod ID, version range, mandatory flag, ordering and side) and includes a dependency summary in the safe-skip warning. The inspector test fixture models a multi-dependency Forge 1.20.1 mod; it is not a statement about Symbiote's dependency list.
- Added a native dependency audit that scans installed NeoForge jars for mod IDs in `[[mods]]` sections only, then classifies Forge/Minecraft platform bridges, native ID candidates, and missing required/optional dependencies. The audit explicitly does not claim that a matching mod ID proves API or version compatibility.
- Added a regression test ensuring IDs mentioned only in another mod's dependency list are not mistaken for installed mods.
- Added an offline ASM-based API usage analyzer and a :loader:inspectForge1201 Gradle task. It reports entrypoints, mixin/coremod metadata, referenced game API classes, method/field symbols, and direct Forge/optional-API invocation call sites without executing the jar. A synthetic bytecode test checks call-site ownership as well as the global symbol inventory. The latest extension adds an optional class#method filter that prints a compact ASM instruction trace for selected mod methods; its local test/run is pending.
- Palladium/PalladiumCore is not a Symbiote dependency and remains outside this pilot; any compatibility work for it belongs in a separate project.
- Added a pure Forge-to-NeoForge metadata transformer. It rewrites the JavaFML loader range, the Forge platform dependency ID, and Forge `mandatory` flags into NeoForge dependency `type` values.
- Added a metadata-only archive transformer that writes a separate derived JAR, replaces `META-INF/mods.toml`, removes stale signature files, preserves other archive entries, and records source/output SHA-256 digests. It never overwrites the source JAR.
- Added unit tests for metadata conversion and archive-copy behavior; the user ran `:loader:clean :loader:test --no-daemon` successfully after correcting Java string escaping in the archive test assertions.
- Ran `:loader:transformForge1201Metadata` against the private Symbiote 1.1.3 JAR successfully. The derived archive copied 515 entries, removed zero signature entries, and produced distinct input/output SHA-256 digests. Its generated `META-INF/neoforge.mods.toml` was manually inspected and confirmed the expected JavaFML `[1,)`, NeoForge `[21.1.258,)`, and Minecraft `[1.21.1,1.22)` ranges with required dependency types.
- The user ran `:loader:auditForge1201Mappings` against the original Symbiote 1.1.3 JAR. The initial catalog classified 120 Forge API classes as 103 package-relocation candidates, 17 manual-adapter types, and zero unclassified Forge API types; it separately found 310 Minecraft API classes. These counts describe static references, not verified runtime compatibility.
- Review of that audit exposed over-broad classifications for nested `ForgeConfigSpec` value types, the config-screen extension point, the old tick-event family, `Event.Result`, `ForgeMod`, `DeferredRegister`, and parts of the old networking API. Those have now been explicitly moved to the manual-adapter category.
- Expanded the mapping catalog to 44 explicit manual-adapter types and added focused tests for nested configuration types, tick-event Pre/Post migration, config-screen registration, Forge attributes, event results, deferred registries, and the six target types absent at the mechanically relocated NeoForge paths. The user ran `:loader:clean :loader:test --no-daemon` successfully after this refinement. The final audit reported 76 package-relocation candidates, 44 manual-adapter types, and zero unclassified Forge API types (120 Forge API classes total, plus 310 Minecraft API classes).
- Added a read-only Forge 47.x to NeoForge 21.1.x mapping catalog and `:loader:auditForge1201Mappings` task. It distinguishes reviewed namespace-relocation candidates from known semantic adapters and unreviewed Forge APIs; it does not rewrite the JAR or feed the discovery pipeline.
- Added `Forge1201TargetApiVerifier` and the root task `:verifyForge1201TargetApi`. It uses the root NeoForge ModDev compile classpath to verify candidate target class files and referenced member descriptors without defining or initializing classes. Synthetic tests cover mapped descriptors, inherited-member lookup, missing members/classes, incomplete hierarchies, exclusion of manual-adapter owners, and descriptors containing manual-adapter types.
- Fixed an ASM `Remapper.mapType()` name-resolution recursion in the verifier's descriptor remapper and corrected the Gradle task's multiline classpath expression. The user then ran `:loader:clean :loader:test --no-daemon` successfully and ran `:verifyForge1201TargetApi` successfully against the original Symbiote 1.1.3 JAR with the NeoForge 21.1.258 target classpath.
- The first target-classpath report (before the six missing target types were reclassified) covered 82 package-relocation candidates: 76 target classes were found and 6 were absent. That initial report is superseded by the final report below.
- Inspection of the six absent target names identified concrete migration work: `LivingAttackEvent` must be adapted to the NeoForge incoming-damage event model; Forge's nested `MobSpawnEvent.FinalizeSpawn` must be adapted to NeoForge's top-level `FinalizeSpawnEvent`; and the mapped nested `Mod.EventBusSubscriber` annotation/Bus types, `NetworkHooks`, and `IForgeRegistry` are absent at their mechanically relocated paths. The catalog classifies these six as manual migrations, with a regression test for those decisions.
- Final verification after the catalog refinement: the user ran `:loader:clean :loader:test --no-daemon` successfully, then `:loader:auditForge1201Mappings` successfully, and finally `:verifyForge1201TargetApi` successfully against the original Symbiote 1.1.3 JAR with NeoForge 21.1.258. The final audit reported 120 Forge API classes, 310 Minecraft API classes, 76 package-relocation candidates, 44 manual-adapter types, and zero unmapped Forge API types.
- The final target verifier found all 76 of 76 relocation-candidate classes. It checked 108 unique candidate method references: 97 matched exact target or inherited descriptors, 10 were reported missing/changed, and one was excluded from exact descriptor matching because its descriptor contains the manual-adapter `Event.Result` type. All 14 candidate field references matched. This successful static verification does not demonstrate class loading or gameplay compatibility.
- The 10 missing/changed method references are: `EntityRenderersEvent.AddLayers#getSkin(String)`; `RenderGuiEvent.Pre#getWindow()`; `RenderLevelStageEvent#getPartialTick()`; `VanillaGameEvent#getVanillaEvent()`; `EntityAttributeModificationEvent#add(EntityType, Attribute, double)`; `EntityAttributeModificationEvent#has(EntityType, Attribute)`; `EntityEvent.Size#getNewEyeHeight()`; `EntityEvent.Size#setNewEyeHeight(float)`; `PlayerInteractEvent#isCancelable()`; and `PlayerInteractEvent#setCanceled(boolean)`. Their exact target replacements and behavioral intent must be reviewed before any call-site rewrite.
- The method report is consistent with known 1.21.1 API shifts that require source-level attention: player renderer lookup uses `PlayerSkin.Model`; render event timing/context types changed; the vanilla game event is represented by `Holder<GameEvent>`; entity-attribute modification APIs use `Holder<Attribute>`; and entity-size events no longer expose the old eye-height accessors. The two base `PlayerInteractEvent` cancellation methods require special care because cancelability is expressed through concrete cancellable subevents rather than assumed uniformly on the base type.
- The static per-class inventory traces those 11 unresolved references to these Symbiote classes: `LivingArmorClientHook` (skin renderer lookup); `SculkEchoBlipRenderer` (GUI window and render partial tick); `BellCounterplayHandler` (vanilla game event holder); `WildHostAttributeFix` (attribute holder arguments); `LivingArmorSizing` (entity dimensions / eye height); and `SeizedInteractionListener` (interaction cancelability, cancellation and result). This proves class ownership but not the exact containing method or the behavior of its branch.
- Extended `Forge1201ApiUsageAnalyzer` to print direct Forge/optional-API invocation call sites, including caller class, caller method and descriptor, target owner/name/descriptor, opcode, and reference count. Added a synthetic regression assertion to `Forge1201JarInspectorTest`. The call-site enhancement has been committed but still needs a local `:loader:clean :loader:test --no-daemon` run and an `:loader:inspectForge1201` run against the private Symbiote JAR before its report can be treated as user-verified.
- The call-site scanner records direct `INVOKE*` instructions. It does not by itself identify branch intent, data values, reflective calls, or method handles; inspect those separately before writing behavior-sensitive adapters.
- The user successfully ran the enhanced `:loader:inspectForge1201` task after the analyzer test fix. Exact callers for the unresolved call sites are: `LivingArmorClientHook#onAddLayers`, `SculkEchoBlipRenderer#onRenderGui` (two `getWindow()` calls), `SculkEchoBlipRenderer#onRenderLevelStage`, `BellCounterplayHandler#onVanillaGameEvent`, `WildHostAttributeFix#onAttributeModification` (both `add` and `has`), `LivingArmorSizing#applyLargeDimensions` (both eye-height calls), and `SeizedInteractionListener#deny` (cancelability, cancellation, and result).
- The inspector also shows partial-tick calls in `HandMorphRenderer#onRenderHand`, `WildHostRenderLayer#onRenderLivingPost`, and `BloomHeadHandler#onRenderPre`; these references were not among the target verifier's unresolved signatures. Only `RenderLevelStageEvent#getPartialTick()` remains unresolved in this audit.
- The direct caller methods are now confirmed: `LivingArmorClientHook#onAddLayers`; `SculkEchoBlipRenderer#onRenderGui` (two old window calls); `SculkEchoBlipRenderer#onRenderLevelStage`; `BellCounterplayHandler#onVanillaGameEvent`; `WildHostAttributeFix#onAttributeModification`; `LivingArmorSizing#applyLargeDimensions`; and `SeizedInteractionListener#deny`. The private testbench contains a separate detailed call-site audit.
- The inspector task now accepts `-Pforge1201TraceMethods=<comma-separated-class#method-list>` to emit read-only ASM instructions for selected caller methods. This trace should be run after `:loader:clean :loader:test --no-daemon` verifies the latest code. Branch intent and runtime values still need separate review.
- The user uploaded and the project reviewed `symbiote-selected-bytecode.txt`. The method trace confirms `LivingArmorClientHook#onAddLayers` iterates skin models and adds three render layers to each `PlayerRenderer`; `SculkEchoBlipRenderer` captures its view/projection/camera state after particles and later uses GUI-scaled dimensions plus game-time partial ticks for projected blips; `BellCounterplayHandler` specifically matches `GameEvent.BLOCK_CHANGE` and verifies a `BellBlock` at the event position; `WildHostAttributeFix` adds `Attributes.ATTACK_DAMAGE` at 1.0 for every event entity type missing it; and `LivingArmorSizing#applyLargeDimensions` scales width, height, and eye height by 1.35, except fixed, sleeping, and dying cases.
- The NeoForge adaptation directions are now grounded in the actual bytecode: player skin keys become `PlayerSkin.Model`; GUI size comes from `GuiGraphics.guiWidth()/guiHeight()`; render-stage interpolation comes from `DeltaTracker.getGameTimeDeltaPartialTick(...)`; the vanilla event holder should be checked against `GameEvent.BLOCK_CHANGE`; attribute operations use `Attributes.ATTACK_DAMAGE` as a `Holder<Attribute>`; and the scaled dimensions should carry the clamped eye height via `EntityDimensions.withEyeHeight(...)`.
- The trace proves `SeizedInteractionListener#deny` always cancels a seized server player's interaction, then conditionally sets the old `Event.Result.DENY` based on cancelability. The exact concrete event handlers that call this helper are not included in the selected method trace, so the final interaction-result translation remains unconfirmed.
- Added class-wide trace selection (`ClassName#*`) and a regression test. The newest commits are `f661d9a`, `8348468`, and `b0e0a14`. The user's next required verification is `:loader:clean :loader:test --no-daemon`; if it passes, trace `com.scout.symbiote.event.SeizedInteractionListener#*` to identify the concrete caller subtypes and separate item-use-start path.
- A private call-site audit document has been added to the testbench. Exact branch conditions and argument semantics still need targeted bytecode inspection; do not choose the `DENY` replacement until `SeizedInteractionListener#deny` and its call sites have been reviewed.
- This validates metadata/archive transformation only; classes remain unmapped and the derived JAR is not loadable as a supported mod.

The existing 1.7.10/1.12.2 NeoForge 1.21.1 runtime remains the baseline. The 1.20.1 API bridge, bytecode adaptation and gameplay validation remain future milestones.
