package legacyconnector.v1_20_1.net.minecraftforge.client.event;

import legacyconnector.v1_20_1.net.minecraftforge.client.gui.overlay.ForgeGui;
import legacyconnector.v1_20_1.net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.LayeredDraw;
import java.lang.reflect.Field;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/** Forge 1.20.1's overlay registration event (mod bus); posted to each legacy mod from NeoForge's {@code RegisterGuiLayersEvent}, which it forwards to. */
public class RegisterGuiOverlaysEvent extends Event implements IModBusEvent {
    private final RegisterGuiLayersEvent delegate;
    private final String namespace;

    public RegisterGuiOverlaysEvent(RegisterGuiLayersEvent delegate, String namespace) {
        this.delegate = delegate;
        this.namespace = namespace;
    }

    private ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(namespace != null ? namespace : ModLoadingContext.get().getActiveNamespace(), name);
    }

    private static volatile Field minecraftGuiField;

    private static Gui liveGui(Minecraft minecraft) {
        Field field = minecraftGuiField;
        if (field == null) {
            synchronized (RegisterGuiOverlaysEvent.class) {
                field = minecraftGuiField;
                if (field == null) {
                    field = findMinecraftGuiField();
                    minecraftGuiField = field;
                }
            }
        }

        try {
            return (Gui) field.get(minecraft);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access Minecraft's live Gui instance for a Forge HUD overlay", exception);
        }
    }

    /**
     * Minecraft.gui is package-private in NeoForge 1.21.1. Locate it by
     * declared field type rather than its name so runtime mapping does not
     * affect the bridge.
     */
    private static Field findMinecraftGuiField() {
        Field result = null;
        for (Field field : Minecraft.class.getDeclaredFields()) {
            if (field.getType() != Gui.class) continue;
            if (result != null) {
                throw new IllegalStateException("Minecraft declares more than one Gui field; cannot identify its live HUD layout");
            }
            result = field;
        }

        if (result == null) {
            throw new IllegalStateException("Minecraft does not declare a Gui field; cannot synchronize Forge HUD layout");
        }
        if (!result.trySetAccessible()) {
            throw new IllegalStateException("Minecraft's Gui field is not reflectively accessible; cannot synchronize Forge HUD layout");
        }
        return result;
    }

    private static LayeredDraw.Layer layer(IGuiOverlay overlay) {
        return (graphics, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            Gui liveGui = liveGui(mc);
            ForgeGui.mcc$instance().mcc$renderOverlay(
                    liveGui,
                    overlay,
                    graphics,
                    delta.getGameTimeDeltaPartialTick(false),
                    mc.getWindow().getGuiScaledWidth(),
                    mc.getWindow().getGuiScaledHeight());
        };
    }

    public void registerBelowAll(String name, IGuiOverlay overlay) {
        delegate.registerBelowAll(id(name), layer(overlay));
    }

    public void registerBelow(ResourceLocation other, String name, IGuiOverlay overlay) {
        delegate.registerBelow(other, id(name), layer(overlay));
    }

    public void registerAbove(ResourceLocation other, String name, IGuiOverlay overlay) {
        delegate.registerAbove(other, id(name), layer(overlay));
    }

    public void registerAboveAll(String name, IGuiOverlay overlay) {
        delegate.registerAboveAll(id(name), layer(overlay));
    }
}
