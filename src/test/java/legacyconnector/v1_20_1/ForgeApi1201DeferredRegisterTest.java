package legacyconnector.v1_20_1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import legacyconnector.v1_20_1.net.minecraftforge.registries.ForgeRegistries;
import legacyconnector.v1_20_1.net.minecraftforge.registries.IForgeRegistry;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.junit.jupiter.api.Test;

/**
 * Runtime-side test for the Forge 1.20.1 DeferredRegister factory adapter.
 *
 * <p>This belongs to the root NeoForge runtime module, not the loader service module:
 * the loader intentionally has no Minecraft or NeoForge runtime API dependencies.</p>
 */
class ForgeApi1201DeferredRegisterTest {
    @Test
    void factoryPreservesAllSixSymbioteBuiltinRegistryKeysAndNamespace() {
        List<IForgeRegistry<?>> registries = List.of(
                ForgeRegistries.BLOCK_ENTITY_TYPES,
                ForgeRegistries.BLOCKS,
                ForgeRegistries.ENTITY_TYPES,
                ForgeRegistries.ITEMS,
                ForgeRegistries.MENU_TYPES,
                ForgeRegistries.SOUND_EVENTS);

        for (IForgeRegistry<?> registry : registries) {
            DeferredRegister<?> actual = ForgeApi1201.createDeferred(registry, "symbiote");
            assertEquals(registry.getRegistryKey(), actual.getRegistryKey(),
                    "registry key must be preserved for " + registry.getRegistryName());
            assertEquals("symbiote", actual.getNamespace(),
                    "mod namespace must be preserved for " + registry.getRegistryName());
        }
    }
}
