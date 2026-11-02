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
            Map.entry("net/minecraftforge/network/NetworkEvent",
                    "The old SimpleChannel event context is replaced by the typed custom-payload handling model."),
            Map.entry("net/minecraftforge/network/NetworkEvent$Context",
                    "Migrate handler execution, sender access, and packet context to IPayloadContext."),
            Map.entry("net/minecraftforge/network/NetworkDirection",
                    "Old SimpleChannel direction descriptors do not map directly to payload registration flow."),
            Map.entry("net/minecraftforge/network/simple/SimpleChannel$MessageBuilder",
                    "The message-builder registration API is removed; define typed payloads, codecs, and handlers."),
            Map.entry("net/minecraftforge/network/PacketDistributor$PacketTarget",
                    "Old packet-target objects do not map directly to typed payload sending."),
            Map.entry("net/minecraftforge/registries/DeferredRegister",
                    "Review registration method descriptors and migrate all RegistryObject consumers to DeferredHolder."),
            Map.entry("net/minecraftforge/common/ForgeMod",
                    "Migrate each referenced attribute to its exact vanilla Attributes or NeoForge equivalent."),
            Map.entry("net/minecraftforge/common/ForgeConfigSpec$Builder",
                    "The ForgeConfigSpec type family is replaced by ModConfigSpec; migrate the builder and value types."),
            Map.entry("net/minecraftforge/common/ForgeConfigSpec$BooleanValue",
                    "Migrate config value declarations and consumers to ModConfigSpec.BooleanValue."),
            Map.entry("net/minecraftforge/common/ForgeConfigSpec$DoubleValue",
                    "Migrate config value declarations and consumers to ModConfigSpec.DoubleValue."),
            Map.entry("net/minecraftforge/common/ForgeConfigSpec$IntValue",
                    "Migrate config value declarations and consumers to ModConfigSpec.IntValue."),
            Map.entry("net/minecraftforge/client/ConfigScreenHandler$ConfigScreenFactory",
                    "Replace the old config screen factory with IConfigScreenFactory registered through ModContainer."),
            Map.entry("net/minecraftforge/client/gui/overlay/ForgeGui",
                    "The old relative overlay-height fields have no equivalent contract; adapt HUD layout."),
            Map.entry("net/minecraftforge/eventbus/api/Event$Result",
                    "Do not globally map Result; inspect each concrete interaction event and use its event-specific controls."),
            Map.entry("net/minecraftforge/event/TickEvent",
                    "The old unified tick event model is split into context-specific Pre/Post event types."),
            Map.entry("net/minecraftforge/event/TickEvent$Phase",
                    "The old phase enum is replaced by distinct Pre/Post event classes."),
            Map.entry("net/minecraftforge/event/TickEvent$ClientTickEvent",
                    "Migrate to net.neoforged.neoforge.client.event.ClientTickEvent.Pre/Post."),
            Map.entry("net/minecraftforge/event/TickEvent$LevelTickEvent",
                    "Migrate to the level tick event Pre/Post types under net.neoforged.neoforge.event.tick."),
            Map.entry("net/minecraftforge/event/TickEvent$PlayerTickEvent",
                    "Migrate to PlayerTickEvent.Pre/Post under net.neoforged.neoforge.event.tick; phase logic must change."),
            Map.entry("net/minecraftforge/event/TickEvent$RenderTickEvent",
                    "Migrate render-frame callbacks to RenderFrameEvent.Pre/Post under the client event package."),
            Map.entry("net/minecraftforge/event/TickEvent$ServerTickEvent",
                    "Migrate to ServerTickEvent.Pre/Post under net.neoforged.neoforge.event.tick.")
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
