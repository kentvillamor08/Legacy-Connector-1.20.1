package org.kylepitcock.legacyconnector.loader.transform;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

/**
 * Read-only access to the 1.20.1 -> 1.21.1 API migration catalog for static compatibility audits.
 *
 * <p>This facade keeps the package-private transformation tables encapsulated. It does not
 * transform bytecode or claim that a recorded migration is behaviorally equivalent.</p>
 */
public final class Mc1201ApiMappings {
    private Mc1201ApiMappings() {}

    public record StaticFieldMapping(
            String targetName,
            String targetDescriptor,
            String postAdapter,
            String helperOwner,
            String helperName,
            String helperDescriptor) {
        public boolean usesHelper() {
            return helperOwner != null && helperName != null && helperDescriptor != null;
        }
    }

    /** Resolved {@code mc-calls.tsv} mapping for one observed call opcode. */
    public record CallMapping(
            String declaringOwner,
            String helperOwner,
            String helperName,
            String helperDescriptor,
            boolean inPlace,
            String ruleKey) {
        public boolean usesHelper() {
            return !inPlace && helperOwner != null && helperName != null && helperDescriptor != null;
        }
    }

    /** Returns the mapped 1.21.1 class name, or the source name when no class move is recorded. */
    public static String targetClassName(String sourceInternalName) {
        if (sourceInternalName == null || sourceInternalName.isBlank()) {
            throw new IllegalArgumentException("A source class name is required");
        }
        return Mc1201Tables.get().classes.getOrDefault(sourceInternalName, sourceInternalName);
    }

    /** Maps only Minecraft class names in a descriptor, matching the keys used by mc-fields.tsv. */
    public static String mapMinecraftClassesInDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            throw new IllegalArgumentException("A descriptor is required");
        }
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return targetClassName(internalName);
            }
        }.mapDesc(descriptor);
    }

    /** Returns the mapped target member name using the same owner/name/descriptor keys as the remapper. */
    public static String targetMemberName(String sourceOwner, String sourceName, String sourceDescriptor) {
        if (sourceOwner == null || sourceName == null || sourceDescriptor == null) {
            throw new IllegalArgumentException("A source owner, member name, and descriptor are required");
        }
        return Mc1201Tables.get().renamedMember(sourceOwner, sourceName, sourceDescriptor);
    }

    /**
     * Resolves the first applicable call helper using the same nearest-owner and opcode-specific
     * lookup order as Mc1201Migration.redirect. Descriptors must already use 1.21.1 class names.
     * Constructor mappings are returned for diagnostic purposes; callers must separately prove
     * the NEW/DUP/INVOKESPECIAL allocation shape before claiming them verified.
     */
    public static CallMapping callMapping(String owner, String name, String descriptor, int opcode) {
        if (owner == null || name == null || descriptor == null) {
            throw new IllegalArgumentException("A call owner, name, and descriptor are required");
        }
        if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE
                && opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKESPECIAL) {
            throw new IllegalArgumentException("Unsupported method-call opcode: " + opcode);
        }

        Mc1201Tables tables = Mc1201Tables.get();
        boolean constructor = "<init>".equals(name);
        boolean special = opcode == Opcodes.INVOKESPECIAL;
        List<String> owners = constructor
                ? List.of(owner)
                : new Mc1201Hierarchy().minecraftAncestors(owner);
        for (String declaringOwner : owners) {
            Mc1201Tables.Call rule = null;
            if (!constructor) {
                if (special) {
                    rule = tables.calls.get(declaringOwner + "." + name + descriptor + "@special");
                } else if (opcode != Opcodes.INVOKESTATIC) {
                    rule = tables.calls.get(declaringOwner + "." + name + descriptor + "@virtual");
                }
            }
            if (rule == null) {
                rule = tables.calls.get(declaringOwner + "." + name + descriptor);
            }
            if (rule == null) continue;

            String ruleKey = declaringOwner + "." + name + descriptor;
            if (rule.helper().startsWith("=")) {
                return new CallMapping(declaringOwner, null, null, null, true, ruleKey);
            }

            String helperDescriptor = rule.helperDesc();
            if (helperDescriptor == null) {
                if (opcode == Opcodes.INVOKESTATIC) {
                    helperDescriptor = descriptor;
                } else if (constructor) {
                    helperDescriptor = descriptor.substring(0, descriptor.length() - 1) + "L" + owner + ";";
                } else {
                    helperDescriptor = "(L" + rule.owner() + ";" + descriptor.substring(1);
                }
            }
            return new CallMapping(
                    rule.owner(), rule.helper(), rule.helperName(), helperDescriptor, false, ruleKey);
        }
        return null;
    }

    /**
     * Resolves an exact Forge API rewrite-catalog helper. This remains separate from
     * {@link #callMapping(String, String, String, int)}, whose rules come from mc-calls.tsv.
     */
    public static CallMapping forgeApiCallMapping(String owner, String name, String descriptor, int opcode) {
        return ForgeApiRewrites1201.callMapping(owner, name, descriptor, opcode);
    }

    /**
     * Returns a known static-field shape migration. These rows are consumed by the bytecode
     * migrator for GETSTATIC instructions only; callers must verify the observed opcode and
     * absence of matching field handles before treating the mapping as applicable.
     *
     * <p>The raw source descriptor is tried first. A second lookup uses only mc-classes.tsv
     * remapping because several mc-fields.tsv entries are keyed by the compatibility descriptor
     * of a mapped legacy type (for example Enchantment -> LegacyEnchantment).</p>
     */
    public static StaticFieldMapping staticFieldMapping(
            String sourceOwner, String sourceName, String sourceDescriptor) {
        if (sourceOwner == null || sourceName == null || sourceDescriptor == null) {
            throw new IllegalArgumentException("A source owner, member name, and descriptor are required");
        }
        Mc1201Tables tables = Mc1201Tables.get();
        Mc1201Tables.Field field = tables.fields.get(
                sourceOwner + "." + sourceName + ":" + sourceDescriptor);
        if (field == null) {
            String mappedDescriptor = mapMinecraftClassesInDescriptor(sourceDescriptor);
            if (!mappedDescriptor.equals(sourceDescriptor)) {
                field = tables.fields.get(sourceOwner + "." + sourceName + ":" + mappedDescriptor);
            }
        }
        if (field == null) return null;

        String mappedSourceDescriptor = mapMinecraftClassesInDescriptor(sourceDescriptor);
        String targetName = field.newName().equals("-") ? sourceName : field.newName();
        String targetDescriptor = field.newDesc().equals("-") ? sourceDescriptor : field.newDesc();
        String helperOwner = null;
        String helperName = null;
        String helperDescriptor = null;

        if ("none".equals(targetDescriptor)) {
            String[] helper = helperReference(field.post());
            if (helper != null) {
                helperOwner = helper[0];
                helperName = helper[1];
                helperDescriptor = "()" + mappedSourceDescriptor;
            }
        } else if (isHelperPostAdapter(field.post())) {
            String[] helper = helperReference(field.post());
            if (helper != null) {
                helperOwner = helper[0];
                helperName = helper[1];
                String mappedTargetDescriptor = mapMinecraftClassesInDescriptor(targetDescriptor);
                helperDescriptor = "(" + mappedTargetDescriptor + ")" + mappedSourceDescriptor;
            }
        }

        return new StaticFieldMapping(
                targetName, targetDescriptor, field.post(),
                helperOwner, helperName, helperDescriptor);
    }

    private static boolean isHelperPostAdapter(String post) {
        return post != null && post.indexOf('.') > 0
                && !"value".equals(post) && !"location".equals(post);
    }

    private static String[] helperReference(String post) {
        if (!isHelperPostAdapter(post)) return null;
        int separator = post.lastIndexOf('.');
        if (separator <= 0 || separator == post.length() - 1) return null;
        String owner = post.substring(0, separator);
        if (!owner.contains("/")) owner = Mc1201Tables.HELPER_PACKAGE + owner;
        return new String[] {owner, post.substring(separator + 1)};
    }
}
