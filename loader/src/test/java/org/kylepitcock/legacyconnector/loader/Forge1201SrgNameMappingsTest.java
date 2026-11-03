package org.kylepitcock.legacyconnector.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Forge1201SrgNameMappingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void normalizesSourceSrgMethodsAndFieldsBeforeTargetMemberLookup() throws Exception {
        Path methodsCsv = temporaryDirectory.resolve("methods.csv");
        Path fieldsCsv = temporaryDirectory.resolve("fields.csv");
        Files.writeString(methodsCsv,
                "searge,name,side,desc\n"
                        + "m_130070_,writeUtf,0,Writes UTF text\n"
                        + "m_130277_,readUtf,0,Reads UTF text\n");
        Files.writeString(fieldsCsv,
                "searge,name,side,desc\n"
                        + "f_178333_,MAX_STRING_LENGTH,0,Maximum encoded string length\n");

        Forge1201SrgNameMappings mappings =
                Forge1201SrgNameMappings.load(methodsCsv, fieldsCsv);

        Forge1201ApiUsageAnalyzer.Analysis source = new Forge1201ApiUsageAnalyzer.Analysis(
                null,
                1,
                List.of(),
                List.of(),
                null,
                Map.of(),
                List.of("net/minecraft/network/FriendlyByteBuf"),
                List.of(),
                List.of(
                        new Forge1201ApiUsageAnalyzer.ApiMemberReference(
                                "net/minecraft/network/FriendlyByteBuf",
                                "m_130070_",
                                "(Ljava/lang/String;)Lnet/minecraft/network/FriendlyByteBuf;",
                                2),
                        new Forge1201ApiUsageAnalyzer.ApiMemberReference(
                                "net/minecraft/network/FriendlyByteBuf",
                                "m_130277_",
                                "()Ljava/lang/String;",
                                1),
                        new Forge1201ApiUsageAnalyzer.ApiMemberReference(
                                "net/minecraft/network/FriendlyByteBuf",
                                "m_999999_",
                                "()V",
                                1)),
                List.of(new Forge1201ApiUsageAnalyzer.ApiMemberReference(
                        "net/minecraft/network/FriendlyByteBuf",
                        "f_178333_",
                        "I",
                        1)));

        Forge1201SrgNameMappings.Normalization result = mappings.normalize(source);

        assertEquals("writeUtf", result.analysis().methodReferences().get(0).name());
        assertEquals("readUtf", result.analysis().methodReferences().get(1).name());
        assertEquals("m_999999_", result.analysis().methodReferences().get(2).name());
        assertEquals("MAX_STRING_LENGTH", result.analysis().fieldReferences().get(0).name());

        assertEquals(2, result.methodMappings().size());
        assertEquals(1, result.fieldMappings().size());
        assertEquals(1, result.unmappedMethods().size());
        assertTrue(result.unmappedMethods().get(0).contains("m_999999_"));
        assertTrue(result.unmappedFields().isEmpty());

        assertTrue(result.methodMappings().stream().anyMatch(value -> value.contains("m_130070_")
                && value.contains("writeUtf")));
        assertTrue(result.fieldMappings().get(0).contains("f_178333_"));
    }

    @Test
    void leavesAlreadyReadableNamesAndNonMinecraftOwnersUntouched() throws Exception {
        Path methodsCsv = temporaryDirectory.resolve("methods.csv");
        Path fieldsCsv = temporaryDirectory.resolve("fields.csv");
        Files.writeString(methodsCsv, "searge,name,side,desc\nm_130070_,writeUtf,0,\n");
        Files.writeString(fieldsCsv, "searge,name,side,desc\nf_178333_,MAX_STRING_LENGTH,0,\n");

        Forge1201SrgNameMappings mappings =
                Forge1201SrgNameMappings.load(methodsCsv, fieldsCsv);

        Forge1201ApiUsageAnalyzer.Analysis source = new Forge1201ApiUsageAnalyzer.Analysis(
                null, 1, List.of(), List.of(), null, Map.of(), List.of(), List.of(),
                List.of(
                        new Forge1201ApiUsageAnalyzer.ApiMemberReference(
                                "net/minecraft/network/FriendlyByteBuf", "writeUtf", "()V", 1),
                        new Forge1201ApiUsageAnalyzer.ApiMemberReference(
                                "net/minecraftforge/eventbus/api/IEventBus", "m_130070_", "()V", 1)),
                List.of(new Forge1201ApiUsageAnalyzer.ApiMemberReference(
                        "net/minecraftforge/eventbus/api/IEventBus", "f_178333_", "I", 1)));

        Forge1201SrgNameMappings.Normalization result = mappings.normalize(source);

        assertEquals("writeUtf", result.analysis().methodReferences().get(0).name());
        assertEquals("m_130070_", result.analysis().methodReferences().get(1).name());
        assertEquals("f_178333_", result.analysis().fieldReferences().get(0).name());
        assertTrue(result.methodMappings().isEmpty());
        assertTrue(result.fieldMappings().isEmpty());
        assertTrue(result.unmappedMethods().isEmpty());
        assertTrue(result.unmappedFields().isEmpty());
    }

    @Test
    void rejectsConflictingMappingsInsteadOfChoosingOneSilently() throws Exception {
        Path methodsCsv = temporaryDirectory.resolve("methods.csv");
        Path fieldsCsv = temporaryDirectory.resolve("fields.csv");
        Files.writeString(methodsCsv,
                "searge,name,side,desc\nm_12345_,firstName,0,\nm_12345_,secondName,0,\n");
        Files.writeString(fieldsCsv, "searge,name,side,desc\nf_12345_,fieldName,0,\n");

        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> Forge1201SrgNameMappings.load(methodsCsv, fieldsCsv));
    }
}
