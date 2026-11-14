# Forge 1.20.1 Preflight Analyzer — Slice 3 Design

Status: design checkpoint only. No Slice 3 production implementation is claimed by this document.

## Goal

Extend the existing Slice 1/2 preflight pipeline so one report inventories the source Forge 1.20.1 JAR and its bounded nested mod/library archives. Preserve where each reference came from, identify additional bytecode reference contexts (including method handles, invokedynamic bootstrap data, and ConstantDynamic), and normalize source Minecraft SRG member names to the readable Minecraft 1.20.1 Mojang namespace before any 1.20.1 → 1.21.1 target comparison.

The analyzer remains static and read-only. It must never load or initialize scanned mod classes. A reference match is evidence, not proof of semantic or gameplay compatibility.

## Findings from the implementation audit

### Existing API scanner to extend

`Forge1201ApiUsageAnalyzer` is the existing ASM scanner and must remain the single source of bytecode inventory. It currently:
- scans `.class` entries in the outer JAR;
- uses ASM 9 `ClassReader` and `ClassRemapper`;
- records referenced game/API classes and package counts through `Remapper.map`;
- records method and field member references through `mapMethodName` / `mapFieldName`;
- records direct `visitMethodInsn` / `visitFieldInsn` call sites with caller class/method/descriptor and opcode;
- detects the main `@Mod` entrypoint, the manifest `MixinConfigs` attribute and `FMLCorePlugin` value.

Its current CLI bytecode trace printer can display `INVOKEDYNAMIC` bootstrap handles/arguments and `LDC` values, but these contextual records are not part of the preflight result. The Slice 3 implementation must verify exactly which type/member forms are already captured by `ClassRemapper` (including annotation/signature/header descriptors, Handles, method Types and nested ConstantDynamic values) before adding collectors. Do not double-count or replace existing member-reference semantics without a regression proving the need.

### Existing archive walker to extend

`Forge1201ArchiveGraph` already performs bounded recursive archive inspection. It reads nested JAR bytes in memory, follows nested archive candidates, preserves archive path/provenance, and enforces depth/count/size/inflated-byte limits. Its current nested snapshot retains metadata and child-JAR bytes but does not retain/scan `.class` entries. The outer source JAR's bytecode is scanned separately by `Forge1201ApiUsageAnalyzer`.

Add an optional nested-class consumer to this existing traversal. Do not create another independent ZIP/Jar-in-Jar walker and do not persist extracted files. The archive walker should continue owning archive safety, cycle protection, provenance, and budgets; the ASM analyzer should own bytecode interpretation.

### Existing mapping table to reuse

`loader/src/main/java/org/kylepitcock/legacyconnector/loader/mapping/SrgMojangTable.java` already generates and packages `legacyconnector/mappings/1.20.1/srg-mojang.tsv`. The table is a source-version mapping from Forge 1.20.1 SRG identifiers (`m_####_` / `f_####_`) to readable 1.20.1 Mojang member names. The build generated 64,225 mappings during the Slice 2 consolidated build.

Use `SrgMojangTable.load()` as the one integrated preflight mapping source. Do not create a third mapper or require users to provide a second CSV path for preflight. The older `Forge1201SrgNameMappings` CSV-based class is currently used by the translated-JAR verifier; review whether its parsing/mapping logic can eventually delegate to the shared table, but do not change that CLI contract in this slice unless required and covered by tests.

Keep the original SRG name and the normalized Mojang name side-by-side in diagnostics. Never destroy the raw value or map Forge member names just because they resemble an SRG token. Only apply this source mapping to recognized Minecraft/Mojang owners.

## Proposed implementation shape

### 1. One shared ASM inventory with explicit provenance

Refactor `Forge1201ApiUsageAnalyzer` around a reusable per-run inventory/collector. Its entry points should support:
- scanning the outer source JAR from a `ZipFile`;
- scanning one already-bounded nested class entry supplied as bytes by `Forge1201ArchiveGraph`;
- finishing one deterministic combined result.

Each class scan receives at least:
- archive path (for the source root, the stable `source/<filename>` path; for nested archives, the full parent `!/entry.jar` chain);
- class entry path within that archive;
- class internal name (read from the class file, not inferred solely from the ZIP path);
- byte length and scan outcome.

Preserve root-vs-nested class counts and per-archive counts. Do not treat installed top-level NeoForge provider JARs as old-mod bytecode to migrate. For this report's source dependency graph, scan nested archives discovered inside the input Forge JAR (mod JARs and library JARs); do not recursively scan the separate installed-mod directory as part of the input mod's bytecode inventory.

The scanner must process byte arrays synchronously and release them after ASM returns. It must not define/load classes.

### 2. Reference model and context

Keep the current aggregate member references and direct call-site models stable where possible. Add provenance-rich records for contexts that cannot be reconstructed from the aggregates. At minimum distinguish:
- class header: superclass and implemented interfaces;
- class/member/method descriptors and generic signatures;
- class, field, method, parameter, record-component, and type annotations (including annotation class literals, enum types, nested annotations, and arrays);
- declared method exceptions and try/catch exception types;
- type instructions, multi-dimensional array descriptors, frame object types, and local-variable descriptors/signatures;
- direct method/field instructions (retain opcode and caller);
- class literals and method-type constants in `LDC`;
- method handles and field handles (retain handle tag, owner, name, descriptor and interface bit);
- `INVOKEDYNAMIC` sites (retain caller, method name/descriptor, bootstrap handle, and recursively inspected bootstrap arguments);
- `ConstantDynamic` values (retain constant name/descriptor, bootstrap handle, and recursively inspected bootstrap arguments).

Recursively inspect ASM `Type`, `Handle`, and `ConstantDynamic` values with a defensive nesting-depth limit and null/unknown handling. Do not stringify a handle and then attempt to parse the text back into members.

For a source location, ASM's visitor API reliably provides instruction order but not the original bytecode byte offset for every instruction. Record an explicit zero-based instruction ordinal, not a fabricated byte offset. If a future low-level class-file reader obtains exact offsets, it can add a distinct nullable offset field.

For global type/member references, preserve existing deduplication/counting behavior. For contextual references, record stable source provenance and sort deterministically by archive path, class entry, caller, reference kind, and symbolic target.

### 3. Nested class traversal and safety

Extend `Forge1201ArchiveGraph` with an optional class-entry consumer invoked while each nested archive is already being decompressed by the existing bounded `snapshotFromBytes` pass. The consumer should only be invoked for the source input's nested archives, not for installed top-level provider mods.

Requirements:
- Reuse the current max depth 4, max nested archive count 128, max nested JAR payload 32 MiB, max inflated per nested archive 64 MiB, and max aggregate nested inflated 256 MiB limits.
- Add an explicit maximum individual class-entry payload and/or total captured class bytes if needed; every skipped class must produce a deterministic `ANALYSIS_INCOMPLETE` finding/warning, never silently disappear.
- Keep the current archive SHA-256 cycle protection. If identical nested content is skipped by the graph, it must not be scanned a second time.
- Scan class entries only after their containing nested archive is classified and only if the archive is a recognized nested mod or a library/unrecognized archive that passed ZIP/path/size safety checks.
- Preserve archive and class entry paths in every nested result.
- Detect duplicate class entry names within an archive and report ambiguity instead of counting multiple arbitrary versions.
- Do not extract classes to disk or reopen/reinflate the same nested archive through a competing walker.

### 4. SRG → Mojang normalization

Use the existing classpath resource via `SrgMojangTable.load()`. Add a deterministic normalization result that records, separately for methods and fields:
- original source SRG name;
- normalized 1.20.1 Mojang name when present;
- owner and descriptor;
- occurrence count and source locations;
- explicit unmapped status if the source name matches the SRG pattern but has no table entry.

Only normalize members whose owner is in the Minecraft/Mojang namespaces (currently `net/minecraft/` and relevant `com/mojang/` namespaces). Do not silently normalize Forge, NeoForge, third-party, or connector shim owners. Do not equate a mapped source name with a 1.21.1 target match; target-side aliases and structural verifier findings remain later/independent evidence.

### 5. Reporting / schema

The current report is Schema 1.2 / Analyzer 1.2.0. Treat Slice 3's nested class provenance, new contextual reference sections, and explicit SRG normalization as an additive report-contract change and bump the report to Schema 1.3 / Analyzer 1.3.0 when the first integrated output is committed. Preserve the existing fields and their meaning:
- `classCount` should continue to mean outer source JAR classes for backward interpretation;
- add explicit nested/total counts and per-archive class scan summaries;
- keep existing method/field aggregate reference and direct call-site sections stable;
- add separate contextual/dynamic reference sections and source-name normalization sections;
- state limits and skipped/unsupported evidence explicitly.

Both JSON and text report output must use the same immutable result model and stable ordering. Do not add timestamps to canonical report JSON/text. The text report should summarize counts before printing detailed references to avoid exploding normal output; detailed lists should still be present in JSON.

## Test plan

Add focused synthetic ASM + ZIP fixtures. The scanner tests must independently prove:
1. superclass, interface, class signature, field/method descriptors, generic signatures, and declared exceptions;
2. class/method/field/parameter/type annotations, enum values, nested annotations and annotation class literals;
3. direct method and field instructions with the correct caller, opcode and instruction ordinal;
4. type instructions, array types, local variables, try/catch and frame object types;
5. class literals and method-type constants;
6. method handles from `LDC`, invokedynamic bootstrap handles and each supported bootstrap argument value type;
7. nested `ConstantDynamic` values, including nested handle/type arguments;
8. unknown/unmapped SRG identifiers remain visible and are never guessed;
9. identical outer/nested class bytes retain different archive provenance when actually present as separate non-cyclic archives, while graph duplicate-content protection skips repeated identical nested archive content;
10. maximum nested class bytes / per-class size limits produce explicit incomplete findings;
11. deterministic JSON/text output across repeated runs and stable class/archive ordering.

Use ASM `ClassWriter` to build bytecode fixtures; do not compile fixture source with javac when the test is specifically about a rare opcode/value shape. Use `@TempDir` and in-memory nested JAR bytes like the existing archive graph tests.

## Exit gates

1. The new scanner tests pass with `--rerun-tasks`.
2. Existing Slice 1/2 loader tests still pass.
3. The Slice 3 Symbiote report is generated using a new unique run filename (never overwrite Slice 2 run-3), and JSON/text outputs are parsed and reviewed.
4. The report confirms outer plus nested class counts/provenance and shows any SRG normalization/unmapped results without claiming target compatibility.
5. The consolidated clean build succeeds after code changes.
6. Runtime compatibility remains unverified until an actual NeoForge test-profile launch and gameplay checks.

## Explicit non-goals for this slice

- No automatic Forge-to-NeoForge API migration or bytecode rewriting.
- No target-side compatibility verdict from SRG normalization alone.
- No semantic Mixin target/handler validation (Slice 4).
- No reflection string rewriting; reflection/resource strings can be a later heuristic layer.
- No installed native provider bytecode scanning as part of the source mod's bytecode inventory.
