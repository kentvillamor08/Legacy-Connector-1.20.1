package org.kylepitcock.legacyconnector.core.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Small item-state helpers for compatibility data which has no vanilla 1.20.1 field.
 *
 * <p>1.20.1 predates vanilla data components. The legacy subtype value is therefore
 * stored as a namespaced integer inside the stack's regular root tag.</p>
 */
public final class CompatComponents {
    private static final String LEGACY_META_KEY = "legacyconnector:legacy_meta";

    private CompatComponents() {}

    public static Integer getLegacyMeta(ItemStack stack) {
        if (stack == null) return null;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(LEGACY_META_KEY, Tag.TAG_INT) ? tag.getInt(LEGACY_META_KEY) : null;
    }

    public static void setLegacyMeta(ItemStack stack, int value) {
        if (stack == null || stack.isEmpty()) return;
        stack.getOrCreateTag().putInt(LEGACY_META_KEY, value);
    }

    public static void clearLegacyMeta(ItemStack stack) {
        if (stack == null) return;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(LEGACY_META_KEY)) return;
        tag.remove(LEGACY_META_KEY);
        if (tag.isEmpty()) stack.setTag(null);
    }
}
