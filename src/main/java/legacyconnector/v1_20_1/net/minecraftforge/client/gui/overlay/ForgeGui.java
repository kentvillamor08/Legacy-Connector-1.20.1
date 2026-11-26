package legacyconnector.v1_20_1.net.minecraftforge.client.gui.overlay;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

/** Forge 1.20.1's in-game GUI subclass, which overlays received as their first argument. A plain {@code Gui} of its own; the layer keeps one instance. */
public class ForgeGui extends Gui {
    public int leftHeight = 39;
    public int rightHeight = 39;
    private final Minecraft mcc$minecraft;

    public ForgeGui(Minecraft mc) {
        super(mc);
        this.mcc$minecraft = mc;
    }

    public Minecraft getMinecraft() {
        return mcc$minecraft;
    }

    /**
     * Render a legacy overlay against the live NeoForge HUD layout.
     *
     * <p>NeoForge 1.21.1 moved {@code leftHeight}/{@code rightHeight} onto
     * {@link Gui}. This Forge facade is a separate Gui instance, so its
     * counters must be synchronized with the actual game Gui around every
     * legacy overlay callback. The facade retains its Forge-owned fields for
     * binary compatibility, while the superclass fields are synchronized too
     * for inherited Gui methods that use them.</p>
     */
    public void mcc$renderOverlay(
            Gui liveGui,
            IGuiOverlay overlay,
            net.minecraft.client.gui.GuiGraphics graphics,
            float partialTick,
            int screenWidth,
            int screenHeight
    ) {
        int initialLeftHeight = liveGui.leftHeight;
        int initialRightHeight = liveGui.rightHeight;

        this.leftHeight = initialLeftHeight;
        this.rightHeight = initialRightHeight;
        super.leftHeight = initialLeftHeight;
        super.rightHeight = initialRightHeight;

        try {
            overlay.render(this, graphics, partialTick, screenWidth, screenHeight);
        } finally {
            liveGui.leftHeight = changedLegacyValue(initialLeftHeight, this.leftHeight, super.leftHeight);
            liveGui.rightHeight = changedLegacyValue(initialRightHeight, this.rightHeight, super.rightHeight);
        }
    }

    private static int changedLegacyValue(int initialValue, int forgeField, int guiField) {
        if (forgeField != initialValue) return forgeField;
        if (guiField != initialValue) return guiField;
        return initialValue;
    }

    public boolean shouldDrawSurvivalElements() {
        return mcc$minecraft.gameMode != null && mcc$minecraft.gameMode.canHurtPlayer() && mcc$minecraft.getCameraEntity() instanceof net.minecraft.world.entity.player.Player;
    }

    public void setupOverlayRenderState(boolean blend, boolean depthTest) {
        if (blend) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        } else {
            RenderSystem.disableBlend();
        }
        if (depthTest) RenderSystem.enableDepthTest();
        else RenderSystem.disableDepthTest();
    }

    private static ForgeGui instance;

    /** The shared instance handed to overlays. */
    public static synchronized ForgeGui mcc$instance() {
        if (instance == null) instance = new ForgeGui(Minecraft.getInstance());
        return instance;
    }
}
