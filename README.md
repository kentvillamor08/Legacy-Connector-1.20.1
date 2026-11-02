# Legacy Connector — Forge 1.20.1 Port

A development fork of [Legacy Connector](https://github.com/kylepitcock/Legacy-Connector), exploring support for running **unmodified legacy Forge mods on Minecraft 1.20.1 with Minecraft Forge**.

The goal is to bring the original project's legacy-mod translation approach to Forge 1.20.1, with support targeted at Forge 1.7.10 and Forge 1.12.2 mods.

> [!WARNING]
> **Work in progress — not ready for players.** This branch is an active porting effort. The Forge 1.20.1 loader, runtime bridges, build configuration, and compatibility layer are not yet complete. There is no usable release jar at this stage. Do not treat the upstream NeoForge compatibility results as proof that a mod works in this port.

## Project status

| Component | Current status |
|---|---|
| Target Minecraft | 1.20.1 |
| Target mod loader | Minecraft Forge 47.3.x |
| Legacy versions being targeted | Forge 1.7.10 and Forge 1.12.2 |
| Development JDK | Java 21 (provisional toolchain choice) |
| Legacy jar discovery on Forge | In progress |
| Translation pipeline on Forge | Not yet ported/verified |
| Runtime API bridges | Not yet ported/verified |
| Player release | Not available |

Track the implementation milestones in the [Forge 1.20.1 port roadmap](docs/porting/Forge-1.20.1.md).

## What this project is trying to do

Legacy Forge mods were built against older Minecraft and Forge APIs. They cannot normally be dropped into a modern Forge instance and expected to load.

The original Legacy Connector addresses that problem by inspecting legacy mod jars and translating them at load time. Its architecture includes:

- **Legacy jar discovery:** identifies old Forge mod jars and determines which legacy Minecraft/Forge generation they target.
- **Bytecode translation:** updates legacy class references and applies compatibility transformations without modifying the original jar.
- **API shims:** supplies implementations of older Minecraft, Forge, and FML APIs that legacy mods expect.
- **Missing API synthesis:** generates compatibility classes and members when the shim does not yet provide them.
- **Runtime bridges:** connects legacy registration and behavior to modern game systems, including registries, events, items, blocks, entities, GUIs, capabilities, networking, rendering, and world generation.
- **Translation cache and reports:** avoids repeating completed translation work and records unresolved references or skipped mods.

The goal of this fork is to preserve and adapt that architecture for **Forge 1.20.1**, not to replace it with a simple remapping-only loader.

## What carries over from upstream

The upstream project's development has demonstrated a broad compatibility approach for legacy 1.7.10 and 1.12.2 mods on **NeoForge 1.21.1**. Its documentation describes work on content registration, items and blocks, entities, GUIs, world generation, dimensions, recipes, capabilities, rendering, and many mod-specific compatibility cases.

Those results and implementation notes are valuable references for this port, but they are **upstream NeoForge results**. They do not mean the same mods or features already work on Forge 1.20.1.

For the original project's compatibility history, see the [upstream compatibility and status notes](https://github.com/kylepitcock/Legacy-Connector/blob/master/docs/wiki/Compatibility-and-Status.md) and [upstream documentation](https://github.com/kylepitcock/Legacy-Connector/tree/master/docs/wiki).

## Development

This port is being developed on the `port/forge-1.20.1` branch.

The build system is still being migrated from the upstream NeoForge 1.21.1 setup to Forge 1.20.1. Consequently, the upstream Gradle run and build instructions are not yet valid for this branch.

Once the Forge build is ready, this section will document:

1. Required JDK and Gradle setup.
2. How to import the project into IntelliJ IDEA.
3. How to run a Forge 1.20.1 development client and server.
4. How to build the distributable loader.
5. How to test the 1.7.10 and 1.12.2 fixture mods.

### Porting principles

- Keep the original legacy mod jars unchanged.
- Do not claim compatibility until it has been tested on Forge 1.20.1.
- Keep the upstream NeoForge implementation available as a reference while building the Forge implementation.
- Prefer isolated compatibility adapters over blind global package or method replacements.
- Test both client and dedicated-server behavior.
- Record unsupported APIs and unresolved references clearly instead of silently pretending they work.

## Documentation

- [Forge 1.20.1 port roadmap](docs/porting/Forge-1.20.1.md)
- [Original upstream architecture](https://github.com/kylepitcock/Legacy-Connector/blob/master/docs/wiki/Architecture.md)
- [Original upstream API bridges](https://github.com/kylepitcock/Legacy-Connector/blob/master/docs/wiki/Bridges.md)
- [Original upstream development notes](https://github.com/kylepitcock/Legacy-Connector/blob/master/docs/wiki/Development.md)
- [Original upstream compatibility notes](https://github.com/kylepitcock/Legacy-Connector/blob/master/docs/wiki/Compatibility-and-Status.md)

Some existing documentation and screenshots in this repository describe the upstream NeoForge 1.21.1 implementation. Treat them as historical/technical references until they are reviewed and updated for the Forge 1.20.1 port.

## Reporting issues

Please include:

- Minecraft version and Forge version.
- Java version.
- The legacy mod jar name and its original Minecraft/Forge version.
- Whether the issue occurs on a clean Forge instance or with other mods installed.
- The relevant part of `latest.log` and, when available, `logs/legacyconnector-report.txt`.

Do not upload or commit third-party legacy mod jars. Provide their names and versions instead.

## Credits and license

This project is a fork of [Legacy Connector by Kyle Pitcock](https://github.com/kylepitcock/Legacy-Connector). The original project and its implementation are the foundation for this work; the upstream author and contributors retain their copyright notices.

This fork is maintained separately and is not an official release of, or endorsed by, the upstream project.

The project is distributed under the [MIT License](LICENSE). The original license and copyright notice are retained. Please preserve the license and attribution when redistributing this fork or substantial portions of its source.
