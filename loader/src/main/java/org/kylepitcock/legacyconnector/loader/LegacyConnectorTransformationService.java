package org.kylepitcock.legacyconnector.loader;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformationService;
import java.util.List;
import java.util.Set;
import net.minecraftforge.forgespi.Environment;

/**
 * Installs the filtered Forge mods-folder locator before FML creates its default folder locator.
 *
 * <p>ModLauncher initializes transformation services before Forge's ModDiscoverer is constructed.
 * Forge uses computePropertyIfAbsent for MODDIRECTORYFACTORY, so installing the factory here means
 * the normal Forge scan sees ordinary modern mods but leaves original legacy jars to our translator.</p>
 */
public final class LegacyConnectorTransformationService implements ITransformationService {
    @Override
    public String name() {
        return "legacyconnector";
    }

    @Override
    public void initialize(IEnvironment environment) {
        environment.computePropertyIfAbsent(
                Environment.Keys.MODDIRECTORYFACTORY.get(),
                key -> LegacyFilteredModsFolderLocator::new
        );
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) {
        // No environment conflicts need to be declared at this stage.
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        // The loader translates legacy jars itself; no ModLauncher bytecode transformer is installed here.
        return List.of();
    }
}
