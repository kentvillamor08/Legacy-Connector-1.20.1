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
    private static int mcc$vehicleHealthRightHeightBefore;
    private static boolean mcc$vehicleHealthLayerStarted;
    private static volatile int mcc$vehicleHealthRightHeightDelta;

    /** Called from RenderFrameEvent.Pre so a cancelled/missing layer cannot reuse the previous frame's offset. */
    public static void mcc$resetVehicleHealthHeightDelta() {
        mcc$vehicleHealthLayerStarted = false;
        mcc$vehicleHealthRightHeightDelta = 0;
    }

    /** Snapshot the live counter immediately before NeoForge draws its vehicle-health layer. */
    public static void mcc$beginVehicleHealthLayer(int rightHeight) {
        mcc$vehicleHealthRightHeightBefore = rightHeight;
        mcc$vehicleHealthLayerStarted = true;
        mcc$vehicleHealthRightHeightDelta = 0;
    }

    /** Record the exact counter increment after the vehicle-health layer has run. */
    public static void mcc$endVehicleHealthLayer(int rightHeight) {
        if (mcc$vehicleHealthLayerStarted) {
            mcc$vehicleHealthRightHeightDelta = Math.max(0, rightHeight - mcc$vehicleHealthRightHeightBefore);
        } else {
            mcc$vehicleHealthRightHeightDelta = 0;
        }
        mcc$vehicleHealthLayerStarted = false;
    }

    /** Height contributed by NeoForge's earlier vehicle-health layer in the current HUD frame. */
    public static int mcc$vehicleHealthRightHeightDelta() {
        return mcc$vehicleHealthRightHeightDelta;
    }

    public void mcc$renderOverlay(
            Gui liveGui,
            IGuiOverlay overlay,
            net.minecraft.client.gui.GuiGraphics graphics,
            float partialTick,
            int screenWidth,
            int screenHeight
    ) {
        mcc$renderOverlay(liveGui, overlay, graphics, partialTick, screenWidth, screenHeight, 0);
    }

    /**
     * Render a legacy overlay while presenting the layout value it would have seen
     * at its original Forge ordering point. The target Gui keeps its real counters:
     * only the callback's view is adjusted, and callback changes are applied as a
     * delta over the live value.
     */
    public void mcc$renderOverlay(
            Gui liveGui,
            IGuiOverlay overlay,
            net.minecraft.client.gui.GuiGraphics graphics,
            float partialTick,
            int screenWidth,
            int screenHeight,
            int legacyRightHeightAdjustment
    ) {
        int initialLeftHeight = liveGui.leftHeight;
        int initialRightHeight = liveGui.rightHeight;
        int exposedRightHeight = initialRightHeight - Math.max(0, legacyRightHeightAdjustment);

        this.leftHeight = initialLeftHeight;
        this.rightHeight = exposedRightHeight;
        super.leftHeight = initialLeftHeight;
        super.rightHeight = exposedRightHeight;

        try {
            overlay.render(this, graphics, partialTick, screenWidth, screenHeight);
        } finally {
            liveGui.leftHeight = changedLegacyValue(initialLeftHeight, this.leftHeight, super.leftHeight);
            liveGui.rightHeight = initialRightHeight
                    + changedLegacyDelta(exposedRightHeight, this.rightHeight, super.rightHeight);
        }
    }

    private static int changedLegacyValue(int initialValue, int forgeField, int guiField) {
        if (forgeField != initialValue) return forgeField;
        if (guiField != initialValue) return guiField;
        return initialValue;
    }

    private static int changedLegacyDelta(int initialValue, int forgeField, int guiField) {
        if (forgeField != initialValue) return forgeField - initialValue;
        if (guiField != initialValue) return guiField - initialValue;
        return 0;
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
