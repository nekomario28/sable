package dev.ryanhcode.sable.sublevel.plot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/** Assertion-based executable for deterministic reconstruction payload validation. */
public final class SubLevelReconstructionPayloadPreflightSelfTest {
    private SubLevelReconstructionPayloadPreflightSelfTest() {
    }

    public static void main(final String[] args) {
        allAirChunkPayloadIsAccepted();
        rootLightStateIsRejected();
        malformedSectionAndLightAreRejected();
        malformedTicksAreRejected();
        malformedHeightmapAndBlockEntityAreRejected();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_PAYLOAD_PREFLIGHT_SELF_TEST: PASS");
    }

    private static void allAirChunkPayloadIsAccepted() {
        final SubLevelReconstructionPayloadPreflight.Result result =
                SubLevelReconstructionPayloadPreflight.validatePlotTag(validPlot());
        assert result.accepted();
        assert result.failures().isEmpty();
    }

    private static void rootLightStateIsRejected() {
        final CompoundTag plot = validPlot();
        plot.putBoolean("isLightOn", true);

        final SubLevelReconstructionPayloadPreflight.Result result =
                SubLevelReconstructionPayloadPreflight.validatePlotTag(plot);

        assert result.failures().equals(Set.of(
                SubLevelReconstructionPayloadPreflight.Failure.AMBIGUOUS_ROOT_LIGHT_STATE
        ));
    }

    private static void malformedSectionAndLightAreRejected() {
        final CompoundTag plot = validPlot();
        final CompoundTag chunk = firstChunk(plot);
        final CompoundTag section = new CompoundTag();
        section.put("block_states", new CompoundTag());
        section.putByteArray("BlockLight", new byte[17]);
        chunk.getCompound("sections").put("0", section);

        final SubLevelReconstructionPayloadPreflight.Result result =
                SubLevelReconstructionPayloadPreflight.validatePlotTag(plot);

        assert result.failures().contains(SubLevelReconstructionPayloadPreflight.Failure.INVALID_BLOCK_STATES);
        assert result.failures().contains(SubLevelReconstructionPayloadPreflight.Failure.INVALID_LIGHT_DATA);
    }

    private static void malformedTicksAreRejected() {
        final CompoundTag plot = validPlot();
        final CompoundTag chunk = firstChunk(plot);
        final ListTag ticks = new ListTag();
        final CompoundTag tick = new CompoundTag();
        tick.putString("i", "not a valid resource id!");
        tick.putInt("x", 0);
        tick.putInt("y", 0);
        tick.putInt("z", 0);
        tick.putInt("t", 1);
        tick.putInt("p", 0);
        ticks.add(tick);
        chunk.put("block_ticks", ticks);

        final SubLevelReconstructionPayloadPreflight.Result result =
                SubLevelReconstructionPayloadPreflight.validatePlotTag(plot);

        assert result.failures().contains(SubLevelReconstructionPayloadPreflight.Failure.INVALID_TICK_DATA);
    }

    private static void malformedHeightmapAndBlockEntityAreRejected() {
        final CompoundTag plot = validPlot();
        final CompoundTag chunk = firstChunk(plot);
        final CompoundTag heightmaps = new CompoundTag();
        heightmaps.putString("WORLD_SURFACE", "not a long array");
        chunk.put("heightmaps", heightmaps);

        final ListTag blockEntities = new ListTag();
        final CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "bad id!");
        blockEntity.putInt("x", 0);
        blockEntity.putInt("y", 0);
        blockEntity.putInt("z", 0);
        blockEntities.add(blockEntity);
        chunk.put("block_entities", blockEntities);

        final SubLevelReconstructionPayloadPreflight.Result result =
                SubLevelReconstructionPayloadPreflight.validatePlotTag(plot);

        assert result.failures().contains(SubLevelReconstructionPayloadPreflight.Failure.INVALID_HEIGHTMAP_DATA);
        assert result.failures().contains(SubLevelReconstructionPayloadPreflight.Failure.INVALID_BLOCK_ENTITY_DATA);
    }

    private static CompoundTag validPlot() {
        final CompoundTag plot = new CompoundTag();
        plot.putString("biome", "minecraft:plains");
        final CompoundTag chunks = new CompoundTag();
        final CompoundTag chunk = new CompoundTag();
        chunk.putBoolean("isLightOn", true);
        chunk.put("sections", new CompoundTag());
        chunk.put("block_ticks", new ListTag());
        chunk.put("fluid_ticks", new ListTag());
        chunk.put("heightmaps", new CompoundTag());
        chunk.put("block_entities", new ListTag());
        chunks.put(String.valueOf(ChunkPos.asLong(0, 0)), chunk);
        plot.put("chunks", chunks);
        return plot;
    }

    private static CompoundTag firstChunk(final CompoundTag plot) {
        return plot.getCompound("chunks").getCompound(String.valueOf(ChunkPos.asLong(0, 0)));
    }
}
