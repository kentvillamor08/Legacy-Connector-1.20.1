package org.kylepitcock.legacyconnector.loader;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Version-scoped inventory of Forge 47.x to NeoForge 21.1.x symbol migration decisions.
 *
 * <p>This catalog is intentionally an audit aid, not an active bytecode remapper. A package
 * relocation is only a candidate until the exact target class and every referenced member
 * signature have been verified against the pinned NeoForge artifact. APIs with changed
 * semantics are classified separately and must be handled by adapters or source-level rewrites.</p>
 */
public final class Forge1201MappingCatalog {
    public enum Kind {
        NOT_FORGE_API,
        PACKAGE_RELOCATION_CANDIDATE,
        MANUAL_ADAPTER_REQUIRED,
        UNMAPPED_FORGE_API
    }

    public record PackageMove(String sourcePrefix, String targetPrefix, String evidence) {}

    public record Decision(
            String sourceInternalName,
            Kind kind,
            String targetInternalName,
            String reason) {}

    private static final List<PackageMove> PACKAGE_MOVES = List.of(
            new PackageMove(
                    "net/minecraftforge/api/distmarker/",
                    "net/neoforged/api/distmarker/",
                    "NeoForge 1.21.1 distribution-marker namespace"),
            new PackageMove(
                    "net/minecraftforge/eventbus/",
                    "net/neoforged/bus/",
                    "NeoForge event-bus package change"),
            new PackageMove(
                    "net/minecraftforge/fml/",
                    "net/neoforged/fml/",
                    "NeoForge FML namespace"),
            new PackageMove(
                    "net/minecraftforge/client/",
                    "net/neoforged/neoforge/client/",
                    "NeoForge client API namespace"),
            new PackageMove(
                    "net/minecraftforge/common/",
                    "net/neoforged/neoforge/common/",
                    "NeoForge common API namespace"),
            new PackageMove(
                    "net/minecraftforge/event/",
                    "net/neoforged/neoforge/event/",
                    "NeoForge event API namespace"),
            new PackageMove(
                    "net/minecraftforge/network/",
                    "net/neoforged/neoforge/network/",
                    "NeoForge networking namespace"),
            new PackageMove(
                    "net/minecraftforge/registries/",
                    "net/neoforged/neoforge/registries/",
                    "NeoForge registry API namespace")
    );

    /**
     * Types whose migration is not a safe package-only rewrite. Their behavior, signatures,
     * ownership, or lifecycle contracts need a dedicated adapter or source-level change.
     */
    private static final Map<String, String> MANUAL_ADAPTERS = Map.ofEntries(
            Map.entry("net/minecraftforge/common/MinecraftForge",
                    "Static event-bus owner changes to NeoForge; verify bus registration semantics."),
            Map.entry("net/minecraftforge/common/ForgeConfigSpec",
                    "Migrate to ModConfigSpec and register it through the injected ModContainer."),
            Map.entry("net/minecraftforge/fml/ModLoadingContext",
                    "Use the injected ModContainer for configuration and extension-point registration."),
            Map.entry("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext",
                    "Use constructor-injected IEventBus and ModContainer; the old singleton accessor is removed."),
            Map.entry("net/minecraftforge/fml/DistExecutor",
                    "No direct replacement; rework physical-side separation and class loading."),
            Map.entry("net/minecraftforge/registries/ForgeRegistries",
                    "Choose the correct vanilla BuiltInRegistries or NeoForgeRegistries key per registry."),
            Map.entry("net/minecraftforge/registries/RegistryObject",
                    "Migrate holder types and all consumers to the NeoForge DeferredHolder model."),
            Map.entry("net/minecraftforge/common/extensions/IForgeMenuType",
                    "Migrate to IMenuTypeExtension and verify menu factory descriptors."),
            Map.entry("net/minecraftforge/client/event/RegisterGuiOverlaysEvent",
                    "Rewrite overlay registration to the layered GUI API."),
            Map.entry("net/minecraftforge/client/gui/overlay/IGuiOverlay",
                    "Rewrite the callback against LayeredDraw.Layer."),
            Map.entry("net/minecraftforge/client/gui/overlay/VanillaGuiOverlay",
                    "Migrate overlay identifiers to VanillaGuiLayers."),
            Map.entry("net/minecraftforge/event/entity/player/PlayerSleepInBedEvent",
                    "Migrate to CanPlayerSleepEvent and preserve the sleep-problem semantics."),
            Map.entry("net/minecraftforge/event/entity/living/LivingHurtEvent",
                    "Split damage mutation and post-damage observation using LivingDamageEvent.Pre/Post."),
            Map.entry("net/minecraftforge/network/simple/SimpleChannel",
                    "Rewrite packets as CustomPacketPayload with StreamCodec and payload registration."),
            Map.entry("net/minecraftforge/network/NetworkRegistry",
                    "The old SimpleChannel construction path is replaced by payload registration."),
            Map.entry("net/minecraftforge/network/PacketDistributor",
                    "Rewrite recipient routing for typed payloads and the new send APIs."),
            Map.entry("net/minecraftforge/network/NetworkEvent$Context",
                    "Migrate handler execution, sender access, and packet context to IPayloadContext.")
    );

    private static final List<PackageMove> ORDERED_PACKAGE_MOVES = PACKAGE_MOVES.stream()
            .sorted(Comparator.comparingInt((PackageMove move) -> move.sourcePrefix().length()).reversed())
            .toList();

    private Forge1201MappingCatalog() {}

    public static List<PackageMove> packageMoves() {
        return PACKAGE_MOVES;
    }

    public static Set<String> manualAdapterTypes() {
        return MANUAL_ADAPTERS.keySet();
    }

    public static Decision classify(String sourceInternalName) {
        if (sourceInternalName == null || sourceInternalName.isBlank()) {
            throw new IllegalArgumentException("A source internal class name is required");
        }

        String manualReason = MANUAL_ADAPTERS.get(sourceInternalName);
        if (manualReason != null) {
            return new Decision(sourceInternalName, Kind.MANUAL_ADAPTER_REQUIRED, null, manualReason);
        }

        for (PackageMove move : ORDERED_PACKAGE_MOVES) {
            if (sourceInternalName.startsWith(move.sourcePrefix())) {
                String target = move.targetPrefix()
                        + sourceInternalName.substring(move.sourcePrefix().length());
                return new Decision(
                        sourceInternalName,
                        Kind.PACKAGE_RELOCATION_CANDIDATE,
                        target,
                        move.evidence() + "; class/member compatibility still requires verification.");
            }
        }

        if (sourceInternalName.startsWith("net/minecraftforge/")) {
            return new Decision(
                    sourceInternalName,
                    Kind.UNMAPPED_FORGE_API,
                    null,
                    "No reviewed package relocation or explicit adapter is recorded for this type.");
        }

        return new Decision(
                sourceInternalName,
                Kind.NOT_FORGE_API,
                sourceInternalName,
                "Outside the Forge API namespace; inspect separately for Minecraft-version changes.");
    }

    public static String manualAdapterReason(String sourceInternalName) {
        return MANUAL_ADAPTERS.get(sourceInternalName);
    }
}
