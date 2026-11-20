package legacyconnector.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.List;
import legacyconnector.v1_20_1.net.minecraftforge.eventbus.api.Event$Result;
import net.neoforged.neoforge.common.util.TriState;
import org.junit.jupiter.api.Test;

class Forge1201EventResultCompatibilityTest {
    @Test
    void sourceResultFacadePreservesForgeConstantOrder() {
        assertEquals(List.of("DENY", "DEFAULT", "ALLOW"),
                Arrays.stream(Event$Result.values()).map(Event$Result::name).toList());
    }

    @Test
    void genericResultSidecarDefaultsToDefaultAndIsIsolatedPerEvent() {
        Object first = new Object();
        Object second = new Object();

        assertSame(Event$Result.DEFAULT, ForgeApi1201.eventGetResult(first));
        assertSame(Event$Result.DEFAULT, ForgeApi1201.eventGetResult(second));

        ForgeApi1201.eventSetResult(first, Event$Result.DENY);
        ForgeApi1201.eventSetResult(second, Event$Result.ALLOW);

        assertSame(Event$Result.DENY, ForgeApi1201.eventGetResult(first));
        assertSame(Event$Result.ALLOW, ForgeApi1201.eventGetResult(second));
    }

    @Test
    void forgeResultMapsToTriStateByNamedValues() {
        assertSame(TriState.FALSE, ForgeApi1201.toTri(Event$Result.DENY));
        assertSame(TriState.DEFAULT, ForgeApi1201.toTri(Event$Result.DEFAULT));
        assertSame(TriState.TRUE, ForgeApi1201.toTri(Event$Result.ALLOW));

        assertSame(TriState.FALSE, ForgeApiTail1201.toTri(Event$Result.DENY));
        assertSame(TriState.DEFAULT, ForgeApiTail1201.toTri(Event$Result.DEFAULT));
        assertSame(TriState.TRUE, ForgeApiTail1201.toTri(Event$Result.ALLOW));
    }

    @Test
    void triStateMapsBackToForgeResultByNamedValues() {
        assertSame(Event$Result.DENY, ForgeApi1201.fromTri(TriState.FALSE));
        assertSame(Event$Result.DEFAULT, ForgeApi1201.fromTri(TriState.DEFAULT));
        assertSame(Event$Result.ALLOW, ForgeApi1201.fromTri(TriState.TRUE));

        assertSame(Event$Result.DENY, ForgeApiTail1201.fromTri(TriState.FALSE));
        assertSame(Event$Result.DEFAULT, ForgeApiTail1201.fromTri(TriState.DEFAULT));
        assertSame(Event$Result.ALLOW, ForgeApiTail1201.fromTri(TriState.TRUE));
    }
}
