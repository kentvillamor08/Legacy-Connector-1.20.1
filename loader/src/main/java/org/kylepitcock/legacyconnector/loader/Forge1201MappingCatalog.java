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
        EXPLICIT_TYPE_REPLACEMENT,
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
     * Explicit class substitutions reviewed against the Forge 47.x and NeoForge 21.1.x APIs.
     * These are not namespace-wide moves: only the listed config type family is replaced.
     */
    private static final Map<String, String> EXPLICIT_TYPE_REPLACEMENTS = Map.of(
            "net/minecraftforge/common/ForgeConfigSpec",
            "net/neoforged/neoforge/common/ModConfigSpec",
            "net/minecraftforge/common/ForgeConfigSpec$Builder",
            "net/neoforged/neoforge/common/ModConfigSpec$Builder",
            "net/minecraftforge/common/ForgeConfigSpec$BooleanValue",
            "net/neoforged/neoforge/common/ModConfigSpec$BooleanValue",
            "net/minecraftforge/common/ForgeConfigSpec$IntValue",
            "net/neoforged/neoforge/common/ModConfigSpec$IntValue",
            "net/minecraftforge/common/ForgeConfigSpec$DoubleValue",
            "net/neoforged/neoforge/common/ModConfigSpec$DoubleValue",
            "net/minecraftforge/registries/RegistryObject",
            "java/util/function/Supplier"
    );

    /**
     * Types whose migration is not a safe package-only rewrite. Their behavior, signatures,
     * ownership, or lifecycle contracts need a dedicated adapter or source-level change.
     */
    private static final Map<String, String> MANUAL_ADAPTERS = Map.ofEntries(
            Map.entry("net/minecraftforge/common/MinecraftForge",
                    "Static event-bus owner changes to NeoForge; verify bus registration semantics."),
            Map.entry("net/minecraftforge/fml/ModLoadingContext",
                    "Use the injected ModContainer for configuration and extension-point registration."),
            Map.entry("net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext",
                    "Use constructor-injected IEventBus and ModContainer; the old singleton accessor is removed."),
            Map.entry("net/minecraftforge/fml/DistExecutor",
                    "No direct replacement; rework physical-side separation and class loading."),
            Map.entry("net/minecraftforge/registries/ForgeRegistries",
                    "Choose the correct vanilla BuiltInRegistries or NeoForgeRegistries key per registry."),
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
            Map.entry("net/minecraftforge/event/entity/living/LivingAttackEvent",
                    "NeoForge 21.1 uses LivingIncomingDamageEvent for the incoming-damage stage; migrate its DamageContainer semantics."),
            Map.entry("net/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn",
                    "NeoForge 21.1 exposes this as the top-level FinalizeSpawnEvent, not a nested MobSpawnEvent type."),
            Map.entry("net/minecraftforge/fml/common/Mod$EventBusSubscriber",
                    "The mapped nested subscriber annotation is not present in the NeoForge target; migrate to the supported subscriber registration model."),
            Map.entry("net/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus",
                    "The mapped nested subscriber Bus enum is not present in the NeoForge target; migrate bus selection with the subscriber registration model."),
            Map.entry("net/minecraftforge/network/NetworkHooks",
                    "No target class exists at the package-mapped path; rewrite menu opening against the 1.21.1 menu API and preserve extra-data synchronization."),
            Map.entry("net/minecraftforge/registries/IForgeRegistry",
                    "The old registry interface is not present at the mapped target; migrate registry access to the appropriate vanilla/NeoForge registry and holder APIs."),
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
            Map.entry("net/minecraftforge/common/ForgeMod",
                    "Migrate each referenced attribute to its exact vanilla Attributes or NeoForge equivalent."),
            Map.entry("net/minecraftforge/client/ConfigScreenHandler",
                    "Replace the old config screen extension-point API with IConfigScreenFactory registered through ModContainer."),
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

    /**
     * Vanilla target types whose members are part of the reviewed registry migration surface.
     * These are included in target API verification even though they are not NeoForge package moves.
     */
    private static final Set<String> REVIEWED_TARGET_API_TYPES = Set.of(
            "net/minecraft/core/registries/BuiltInRegistries",
            "net/minecraft/core/Registry",
            "net/minecraft/core/DefaultedRegistry"
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

    public static Map<String, String> explicitTypeReplacements() {
        return EXPLICIT_TYPE_REPLACEMENTS;
    }

    public static Decision classify(String sourceInternalName) {
        if (sourceInternalName == null || sourceInternalName.isBlank()) {
            throw new IllegalArgumentException("A source internal class name is required");
        }

        String explicitReplacement = EXPLICIT_TYPE_REPLACEMENTS.get(sourceInternalName);
        if (explicitReplacement != null) {
            return new Decision(
                    sourceInternalName,
                    Kind.EXPLICIT_TYPE_REPLACEMENT,
                    explicitReplacement,
                    "Explicit Forge ConfigSpec -> NeoForge ModConfigSpec substitution; builder and value APIs were reviewed.");
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

    /**
     * Returns whether an internal name is inside a target package introduced by one
     * of the reviewed Forge -> NeoForge package relocations.
     */
    public static boolean isNeoForgeRelocationTarget(String targetInternalName) {
        if (targetInternalName == null || targetInternalName.isBlank()) return false;
        return PACKAGE_MOVES.stream().anyMatch(move -> targetInternalName.startsWith(move.targetPrefix()))
                || EXPLICIT_TYPE_REPLACEMENTS.containsValue(targetInternalName);
    }

    public static boolean isReviewedTargetApiType(String targetInternalName) {
        if (targetInternalName == null || targetInternalName.isBlank()) return false;
        return isNeoForgeRelocationTarget(targetInternalName)
                || REVIEWED_TARGET_API_TYPES.contains(targetInternalName);
    }

    public static String manualAdapterReason(String sourceInternalName) {
        return MANUAL_ADAPTERS.get(sourceInternalName);
    }
}
