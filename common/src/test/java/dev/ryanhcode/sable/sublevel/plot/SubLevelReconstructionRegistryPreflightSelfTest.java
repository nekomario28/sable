package dev.ryanhcode.sable.sublevel.plot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Set;

/** Assertion-based executable for target registry-reference validation. */
public final class SubLevelReconstructionRegistryPreflightSelfTest {
    private SubLevelReconstructionRegistryPreflightSelfTest() {
    }

    public static void main(final String[] args) {
        knownReferencesAreAccepted();
        unknownBiomeIsRejected();
        unknownBlockEntityTypeIsRejected();
        malformedReferencesFailClosed();
        missingChunkPayloadIsRejected();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_REGISTRY_PREFLIGHT_SELF_TEST: PASS");
    }

    private static void knownReferencesAreAccepted() {
        final SubLevelReconstructionRegistryPreflight.Result result =
                SubLevelReconstructionRegistryPreflight.validatePlotTag(
                        plot("minecraft:plains", "minecraft:chest"),
                        id -> id.toString().equals("minecraft:plains"),
                        id -> id.toString().equals("minecraft:chest")
                );

        assert result.accepted();
        assert result.failures().isEmpty();
    }

    private static void unknownBiomeIsRejected() {
        final SubLevelReconstructionRegistryPreflight.Result result =
                SubLevelReconstructionRegistryPreflight.validatePlotTag(
                        plot("missing:biome", "minecraft:chest"),
                        id -> false,
                        id -> true
                );

        assert result.failures().equals(Set.of(
                SubLevelReconstructionRegistryPreflight.Failure.UNKNOWN_TARGET_BIOME
        ));
    }

    private static void unknownBlockEntityTypeIsRejected() {
        final SubLevelReconstructionRegistryPreflight.Result result =
                SubLevelReconstructionRegistryPreflight.validatePlotTag(
                        plot("minecraft:plains", "missing:block_entity"),
                        id -> true,
                        id -> false
                );

        assert result.failures().equals(Set.of(
                SubLevelReconstructionRegistryPreflight.Failure.UNKNOWN_BLOCK_ENTITY_TYPE
        ));
    }

    private static void malformedReferencesFailClosed() {
        final SubLevelReconstructionRegistryPreflight.Result result =
                SubLevelReconstructionRegistryPreflight.validatePlotTag(
                        plot("bad biome id!", "bad block entity id!"),
                        id -> true,
                        id -> true
                );

        assert result.failures().contains(SubLevelReconstructionRegistryPreflight.Failure.UNKNOWN_TARGET_BIOME);
        assert result.failures().contains(SubLevelReconstructionRegistryPreflight.Failure.UNKNOWN_BLOCK_ENTITY_TYPE);
    }

    private static void missingChunkPayloadIsRejected() {
        final CompoundTag plot = new CompoundTag();
        plot.putString("biome", "minecraft:plains");

        final SubLevelReconstructionRegistryPreflight.Result result =
                SubLevelReconstructionRegistryPreflight.validatePlotTag(
                        plot,
                        id -> true,
                        id -> true
                );

        assert result.failures().equals(Set.of(
                SubLevelReconstructionRegistryPreflight.Failure.MISSING_PLOT_PAYLOAD
        ));
    }

    private static CompoundTag plot(final String biome, final String blockEntityId) {
        final CompoundTag plot = new CompoundTag();
        plot.putString("biome", biome);

        final CompoundTag chunks = new CompoundTag();
        final CompoundTag chunk = new CompoundTag();
        final ListTag blockEntities = new ListTag();
        final CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", blockEntityId);
        blockEntity.putInt("x", 0);
        blockEntity.putInt("y", 0);
        blockEntity.putInt("z", 0);
        blockEntities.add(blockEntity);
        chunk.put("block_entities", blockEntities);
        chunks.put("0", chunk);
        plot.put("chunks", chunks);
        return plot;
    }
}
