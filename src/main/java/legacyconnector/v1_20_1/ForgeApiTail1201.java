package legacyconnector.v1_20_1;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;

/**
 * The long tail of small Forge 1.20.1 event and hook methods whose NeoForge 1.21.1 form changed shape (a float became a
 * {@code DeltaTracker}, a getter was renamed, a value moved into the registry-aware overload). Reached from
 * {@code forward/1.20.1/forge-call-rewrites.tsv} with a {@code Tail.} prefix on the helper name; instance calls get the receiver first.
 */
public final class ForgeApiTail1201 {
    private ForgeApiTail1201() {}

    // ---- living / player events

    /** Looting is an enchantment effect on the damage source's weapon now; the event no longer carries a level. */
    public static int lootingLevel(LivingDropsEvent event) {
        return 0;
    }

    public static void criticalSetDamageModifier(CriticalHitEvent event, float modifier) {
        event.setDamageMultiplier(modifier);
    }

    public static float criticalGetDamageModifier(CriticalHitEvent event) {
        return event.getDamageMultiplier();
    }

    public static void changeTargetSet(LivingChangeTargetEvent event, LivingEntity target) {
        event.setNewAboutToBeSetTarget(target);
    }

    public static LivingEntity changeTargetGet(LivingChangeTargetEvent event) {
        return event.getNewAboutToBeSetTarget();
    }

    public static MobEffect removeEffect(MobEffectEvent.Remove event) {
        return event.getEffect().value();
    }

    public static BlockState bonemealBlock(BonemealEvent event) {
        return event.getState();
    }

    /** The eye height is part of the entity dimensions now. */
    public static void sizeSetNewEyeHeight(EntityEvent.Size event, float eyeHeight) {
        EntityDimensions size = event.getNewSize();
        if (size != null) event.setNewSize(size.withEyeHeight(eyeHeight));
    }

    public static float sizeGetNewEyeHeight(EntityEvent.Size event) {
        EntityDimensions size = event.getNewSize();
        return size == null ? 0.0F : size.eyeHeight();
    }

    // ---- energy

    public static Tag energySerialize(EnergyStorage storage) {
        return storage.serializeNBT(RegistryAccess.EMPTY);
    }

    public static void energyDeserialize(EnergyStorage storage, Tag tag) {
        storage.deserializeNBT(RegistryAccess.EMPTY, tag);
    }

    // ---- items

    /** Forge's relaxed stacking: same item and same data. */
    public static boolean canItemStacksStackRelaxed(net.minecraft.world.item.ItemStack a, net.minecraft.world.item.ItemStack b) {
        if (a.isEmpty() || b.isEmpty() || !a.isStackable()) return false;
        return net.minecraft.world.item.ItemStack.isSameItemSameComponents(a, b);
    }

    public static net.minecraft.world.level.material.Fluid fluidGetRawFluid(net.neoforged.neoforge.fluids.FluidStack stack) {
        return stack.getFluid();
    }

    // ---- interaction events

    static legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result fromTri(net.neoforged.neoforge.common.util.TriState s) {
        return s == net.neoforged.neoforge.common.util.TriState.TRUE ? legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.ALLOW
                : s == net.neoforged.neoforge.common.util.TriState.FALSE ? legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.DENY
                : legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.DEFAULT;
    }

    static net.neoforged.neoforge.common.util.TriState toTri(legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result r) {
        return r == legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.ALLOW ? net.neoforged.neoforge.common.util.TriState.TRUE
                : r == legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.DENY ? net.neoforged.neoforge.common.util.TriState.FALSE
                : net.neoforged.neoforge.common.util.TriState.DEFAULT;
    }

    public static legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result leftClickGetUseBlock(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock e) {
        return fromTri(e.getUseBlock());
    }

    public static legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result leftClickGetUseItem(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock e) {
        return fromTri(e.getUseItem());
    }

    public static void leftClickSetUseBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock e,
            legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result r) {
        e.setUseBlock(toTri(r));
    }

    public static void leftClickSetUseItem(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock e,
            legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result r) {
        e.setUseItem(toTri(r));
    }

    /**
     * 1.20.1 stored cancellationResult on PlayerInteractEvent itself. 1.21.1 moved
     * storage to four concrete right-click/entity-interaction subclasses; other
     * subclasses and translated custom descendants retain the legacy value here.
     *
     * <p>Weak identity keys prevent this compatibility state from extending the
     * lifetime of posted events. A sentinel preserves the source setter's ability
     * to store null.</p>
     */
    private static final Object NULL_INTERACTION_CANCELLATION_RESULT = new Object();
    private static final java.util.Map<Object, Object> INTERACTION_CANCELLATION_RESULTS =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();

    public static net.minecraft.world.InteractionResult interactGetCancellationResult(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent e) {
        java.util.Objects.requireNonNull(e, "event");
        if (e instanceof net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock b) return b.getCancellationResult();
        if (e instanceof net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem i) return i.getCancellationResult();
        if (e instanceof net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract x) return x.getCancellationResult();
        if (e instanceof net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific x) return x.getCancellationResult();
        Object stored = INTERACTION_CANCELLATION_RESULTS.get(e);
        if (stored == null) return net.minecraft.world.InteractionResult.PASS;
        return stored == NULL_INTERACTION_CANCELLATION_RESULT ? null : (net.minecraft.world.InteractionResult) stored;
    }

    /** Restores the old parent-owned getter for target subclasses without native storage. */
    public static net.minecraft.world.InteractionResult interactGetCancellationResult(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock e) {
        return interactGetCancellationResult((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent) e);
    }

    public static net.minecraft.world.InteractionResult interactGetCancellationResult(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickEmpty e) {
        return interactGetCancellationResult((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent) e);
    }

    public static net.minecraft.world.InteractionResult interactGetCancellationResult(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty e) {
        return interactGetCancellationResult((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent) e);
    }

    /** Restores the old parent-owned setter and keeps target-specific storage where it exists. */
    public static void interactSetCancellationResult(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent e,
            net.minecraft.world.InteractionResult result) {
        java.util.Objects.requireNonNull(e, "event");
        if (e instanceof net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock b) {
            b.setCancellationResult(result);
        } else if (e instanceof net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem i) {
            i.setCancellationResult(result);
        } else if (e instanceof net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract x) {
            x.setCancellationResult(result);
        } else if (e instanceof net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific x) {
            x.setCancellationResult(result);
        } else {
            INTERACTION_CANCELLATION_RESULTS.put(e,
                    result == null ? NULL_INTERACTION_CANCELLATION_RESULT : result);
        }
    }

    public static void interactSetCancellationResult(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock e,
            net.minecraft.world.InteractionResult result) {
        interactSetCancellationResult((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent) e, result);
    }

    public static void interactSetCancellationResult(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickEmpty e,
            net.minecraft.world.InteractionResult result) {
        interactSetCancellationResult((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent) e, result);
    }

    public static void interactSetCancellationResult(
            net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty e,
            net.minecraft.world.InteractionResult result) {
        interactSetCancellationResult((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent) e, result);
    }

    // ---- sapling growth result projection

    private static final Object NULL_BLOCK_GROW_FEATURE = new Object();
    private static final java.util.Map<Object, Object> BLOCK_GROW_FEATURE_OVERRIDES =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private static void rememberBlockGrowFeature(
            net.neoforged.neoforge.event.level.BlockGrowFeatureEvent event,
            net.minecraft.core.Holder<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> feature) {
        BLOCK_GROW_FEATURE_OVERRIDES.put(event, feature == null ? NULL_BLOCK_GROW_FEATURE : feature);
    }

    private static void restoreBlockGrowFeature(
            net.neoforged.neoforge.event.level.BlockGrowFeatureEvent event) {
        if (!BLOCK_GROW_FEATURE_OVERRIDES.containsKey(event)) return;
        Object feature = BLOCK_GROW_FEATURE_OVERRIDES.remove(event);
        @SuppressWarnings("unchecked")
        net.minecraft.core.Holder<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> restored =
                feature == NULL_BLOCK_GROW_FEATURE ? null
                        : (net.minecraft.core.Holder<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>>) feature;
        event.setFeature(restored);
    }

    public static void blockGrowSetResult(
            net.neoforged.neoforge.event.level.BlockGrowFeatureEvent event,
            legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result result) {
        if (result == legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.DENY) {
            if (!BLOCK_GROW_FEATURE_OVERRIDES.containsKey(event)) rememberBlockGrowFeature(event, event.getFeature());
            event.setFeature((net.minecraft.core.Holder<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>>) null);
        } else {
            restoreBlockGrowFeature(event);
        }
    }

    /** Keep a later legacy setFeature call effective while a prior DENY result is pending. */
    public static void blockGrowSetFeature(
            net.neoforged.neoforge.event.level.BlockGrowFeatureEvent event,
            net.minecraft.core.Holder<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> feature) {
        if (BLOCK_GROW_FEATURE_OVERRIDES.containsKey(event)) {
            rememberBlockGrowFeature(event, feature);
            event.setFeature((net.minecraft.core.Holder<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>>) null);
        } else {
            event.setFeature(feature);
        }
    }

    /** ResourceKey overload mirrors the source API and preserves a pending DENY. */
    public static void blockGrowSetFeatureKey(
            net.neoforged.neoforge.event.level.BlockGrowFeatureEvent event,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>> feature) {
        event.setFeature(feature);
        if (BLOCK_GROW_FEATURE_OVERRIDES.containsKey(event)) {
            rememberBlockGrowFeature(event, event.getFeature());
            event.setFeature((net.minecraft.core.Holder<net.minecraft.world.level.levelgen.feature.ConfiguredFeature<?, ?>>) null);
        }
    }

    public static net.minecraft.util.RandomSource blockGrowGetRandom(
            net.neoforged.neoforge.event.level.BlockGrowFeatureEvent event) {
        return event.getRandom();
    }

    // ---- legacy LivingPackSizeEvent projection

    private static final java.util.Map<Object, Integer> LEGACY_MAX_PACK_SIZES =
            new com.google.common.collect.MapMaker().weakKeys().makeMap();

    public static int livingPackSizeGetMax(
            net.neoforged.neoforge.event.entity.living.SpawnClusterSizeEvent event) {
        return LEGACY_MAX_PACK_SIZES.getOrDefault(event, 0);
    }

    public static void livingPackSizeSetMax(
            net.neoforged.neoforge.event.entity.living.SpawnClusterSizeEvent event, int size) {
        LEGACY_MAX_PACK_SIZES.put(event, size);
        if (ForgeApi1201.eventGetResult(event)
                == legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.ALLOW) {
            event.setSize(size);
        }
    }

    public static void spawnClusterSetResult(
            net.neoforged.neoforge.event.entity.living.SpawnClusterSizeEvent event,
            legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result result) {
        if (result == legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.ALLOW) {
            event.setSize(livingPackSizeGetMax(event));
        } else {
            event.setSize(event.getEntity().getMaxSpawnClusterSize());
        }
    }

    // ---- attributes

    private static net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> holder(net.minecraft.world.entity.ai.attributes.Attribute a) {
        return net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.wrapAsHolder(a);
    }

    @SuppressWarnings("unchecked")
    public static void attributeAdd(net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent e, net.minecraft.world.entity.EntityType<?> type,
            net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        e.add((net.minecraft.world.entity.EntityType<? extends LivingEntity>) type, holder(attribute));
    }

    @SuppressWarnings("unchecked")
    public static void attributeAddValue(net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent e, net.minecraft.world.entity.EntityType<?> type,
            net.minecraft.world.entity.ai.attributes.Attribute attribute, double value) {
        e.add((net.minecraft.world.entity.EntityType<? extends LivingEntity>) type, holder(attribute), value);
    }

    @SuppressWarnings("unchecked")
    public static boolean attributeHas(net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent e, net.minecraft.world.entity.EntityType<?> type,
            net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        return e.has((net.minecraft.world.entity.EntityType<? extends LivingEntity>) type, holder(attribute));
    }

    // ---- fml event bus subscriber: Bus.bus() was a supplier of the bus

    public static java.util.function.Supplier<net.neoforged.bus.api.IEventBus> busBus(net.neoforged.fml.common.EventBusSubscriber.Bus bus) {
        if (bus == net.neoforged.fml.common.EventBusSubscriber.Bus.MOD) {
            return () -> net.neoforged.fml.ModLoadingContext.get().getActiveContainer().getEventBus();
        }
        return () -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
    }

    // ---- block break experience: no longer part of the event (it is a drop now); the value is kept per event so a handler reads back what it set

    private static final java.util.Map<Object, Integer> EXP = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static int breakGetExpToDrop(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) {
        return EXP.getOrDefault(e, 0);
    }

    public static void breakSetExpToDrop(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e, int exp) {
        EXP.put(e, exp);
    }

    public static int commonHooksLootingLevel(net.minecraft.world.entity.Entity target, net.minecraft.world.entity.Entity killer,
            net.minecraft.world.damagesource.DamageSource source) {
        return 0;
    }

    public static net.minecraft.core.Registry<?> registerEventVanillaRegistry(net.neoforged.neoforge.registries.RegisterEvent e) {
        return e.getRegistry();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static net.neoforged.neoforge.registries.DeferredHolder holderCreateWithNamespace(net.minecraft.resources.ResourceLocation name,
            net.minecraft.resources.ResourceKey registry, String modid) {
        return net.neoforged.neoforge.registries.DeferredHolder.create(registry, name);
    }

    /** {@code NewRegistryEvent.create(builder, onCreate)}: the callback gets the registry as soon as it exists (registries are bound at once here). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static java.util.function.Supplier newRegistryCreateCallback(net.neoforged.neoforge.registries.NewRegistryEvent event,
            legacyconnector.v1_20_1.net.minecraftforge.registries.RegistryBuilder builder, java.util.function.Consumer onCreate) {
        java.util.function.Supplier supplier = ForgeApi1201.newRegistryCreate(event, builder);
        if (onCreate != null) onCreate.accept(supplier.get());
        return supplier;
    }

    // ---- spawning, bone meal, buckets, brewing

    public static net.minecraft.world.entity.SpawnGroupData onFinalizeSpawn(net.minecraft.world.entity.Mob mob, net.minecraft.world.level.ServerLevelAccessor level,
            net.minecraft.world.DifficultyInstance difficulty, net.minecraft.world.entity.MobSpawnType type, net.minecraft.world.entity.SpawnGroupData data,
            net.minecraft.nbt.CompoundTag tag) {
        return net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, difficulty, type, data);
    }

    /** Forge's result: -1 cancelled, 1 bone meal used up, 0 nothing special. */
    public static int onApplyBonemeal(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.item.ItemStack stack) {
        var event = net.neoforged.neoforge.event.EventHooks.fireBonemealEvent(player, level, pos, state, stack);
        if (event.isCanceled()) {
            if (event.isSuccessful()) {
                if (!level.isClientSide) stack.shrink(1);
                return 1;
            }
            return -1;
        }
        legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result result = ForgeApi1201.eventGetResult(event);
        if (result == legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.ALLOW) {
            if (!level.isClientSide) stack.shrink(1);
            return 1;
        }
        // In Forge's hook, only ALLOW is special; DEFAULT and DENY both return zero here.
        return 0;
    }

    /**
     * Forge 1.20.1's legacy pickup decision hook. This deliberately posts the
     * connector-owned Forge event instead of NeoForge's native ItemEntityPickupEvent.Pre:
     * their result and cancellation contracts are not equivalent.
     *
     * @return -1 when canceled, 1 only for ALLOW, and 0 for DEFAULT or DENY.
     */
    public static int onItemPickup(net.minecraft.world.entity.item.ItemEntity entityItem,
            net.minecraft.world.entity.player.Player player) {
        return onItemPickup(net.neoforged.neoforge.common.NeoForge.EVENT_BUS, entityItem, player);
    }

    /** Shared dispatch body; the package-private overload also permits deterministic isolated-bus tests. */
    static int onItemPickup(net.neoforged.bus.api.IEventBus eventBus,
            net.minecraft.world.entity.item.ItemEntity entityItem,
            net.minecraft.world.entity.player.Player player) {
        var event = new legacyconnector.v1_20_1.net.minecraftforge.event.entity.player.EntityItemPickupEvent(player, entityItem);
        eventBus.post(event);
        if (event.isCanceled()) return -1;
        return event.getResult()
                == legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result.ALLOW ? 1 : 0;
    }

    /** Forge's bucket event could replace the result; no handler means null (the vanilla use goes on). */
    public static net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> onBucketUse(net.minecraft.world.entity.player.Player player,
            net.minecraft.world.level.Level level, net.minecraft.world.item.ItemStack stack, net.minecraft.world.phys.HitResult target) {
        return null;
    }

    /** Brewing recipes are registered through RegisterBrewingRecipesEvent now; recipes added the old way are accepted and not brewed. */
    public static boolean brewAddRecipe(net.minecraft.world.item.crafting.Ingredient ingredient, net.minecraft.world.item.crafting.Ingredient input, net.minecraft.world.item.ItemStack output) {
        return true;
    }

    public static java.util.List brewGetRecipes() {
        return new java.util.ArrayList<>();
    }
}
