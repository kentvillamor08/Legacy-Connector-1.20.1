package legacyconnector.v1_20_1.net.minecraftforge.client.gui.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import net.minecraft.client.gui.Gui;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class ForgeGuiLayoutBridgeTest {
    @Test
    void mirrorsLiveLayoutBeforeCallbackAndPropagatesBothForgeAndGuiFieldWrites() throws Exception {
        Gui liveGui = allocate(Gui.class);
        ForgeGui forgeGui = allocate(ForgeGui.class);
        liveGui.leftHeight = 64;
        liveGui.rightHeight = 76;

        forgeGui.mcc$renderOverlay(liveGui, (gui, graphics, partialTick, width, height) -> {
            // Legacy mod bytecode resolves ForgeGui.leftHeight/rightHeight.
            assertEquals(64, gui.leftHeight);
            assertEquals(76, gui.rightHeight);

            // Inherited Gui methods resolve the fields declared by Gui itself.
            assertEquals(64, ((Gui) gui).leftHeight);
            assertEquals(76, ((Gui) gui).rightHeight);

            gui.leftHeight = 70;
            ((Gui) gui).rightHeight = 88;
        }, null, 0.5F, 320, 180);

        assertEquals(70, liveGui.leftHeight);
        assertEquals(88, liveGui.rightHeight);
    }

    @Test
    void commitsInheritedGuiFieldChangesWhenLegacyFieldWasNotChanged() throws Exception {
        Gui liveGui = allocate(Gui.class);
        ForgeGui forgeGui = allocate(ForgeGui.class);
        liveGui.leftHeight = 51;
        liveGui.rightHeight = 62;

        forgeGui.mcc$renderOverlay(liveGui, (gui, graphics, partialTick, width, height) -> {
            ((Gui) gui).leftHeight = 55;
            ((Gui) gui).rightHeight = 69;
        }, null, 0.0F, 0, 0);

        assertEquals(55, liveGui.leftHeight);
        assertEquals(69, liveGui.rightHeight);
    }

    @Test
    void commitsLayoutChangesEvenWhenOverlayThrows() throws Exception {
        Gui liveGui = allocate(Gui.class);
        ForgeGui forgeGui = allocate(ForgeGui.class);
        liveGui.leftHeight = 39;
        liveGui.rightHeight = 39;

        assertThrows(IllegalStateException.class, () -> forgeGui.mcc$renderOverlay(
                liveGui,
                (gui, graphics, partialTick, width, height) -> {
                    gui.leftHeight = 48;
                    gui.rightHeight = 53;
                    throw new IllegalStateException("overlay failure");
                },
                null, 0.0F, 0, 0));

        assertEquals(48, liveGui.leftHeight);
        assertEquals(53, liveGui.rightHeight);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return (T) unsafe.allocateInstance(type);
    }
}
