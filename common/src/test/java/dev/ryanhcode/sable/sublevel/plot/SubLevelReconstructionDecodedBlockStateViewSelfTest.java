package dev.ryanhcode.sable.sublevel.plot;

import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/** Assertion-based executable for detached global-coordinate block-state views. */
public final class SubLevelReconstructionDecodedBlockStateViewSelfTest {
    private SubLevelReconstructionDecodedBlockStateViewSelfTest() {
    }

    public static void main(final String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        crossChunkAndSectionNeighborsComeFromDecodedStateOnly();
        negativeGlobalCoordinatesUseFloorSectionMapping();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_DECODED_BLOCK_STATE_VIEW_SELF_TEST: PASS");
    }

    private static void crossChunkAndSectionNeighborsComeFromDecodedStateOnly() {
        final SubLevelReconstructionDecodedPayload decoded = decoded(
                List.of(
                        new ChunkInput(ChunkPos.asLong(0, 0), "4", Blocks.STONE.defaultBlockState()),
                        new ChunkInput(ChunkPos.asLong(1, 0), "4", Blocks.DEEPSLATE.defaultBlockState())
                ),
                0,
                0
        );
        final int minSection = -4;
        final SubLevelReconstructionDecodedBlockStateView view =
                SubLevelReconstructionDecodedBlockStateView.createFrom(decoded, minSection);
        assert view.sectionCount() == 2;

        final SubLevelReconstructionDecodedPayload.DecodedChunk left = decoded.chunks().getFirst();
        final SubLevelReconstructionDecodedPayload.DecodedChunk right = decoded.chunks().getLast();
        final int leftChunkX = ChunkPos.getX(left.targetGlobalChunkKey());
        final int rightChunkX = ChunkPos.getX(right.targetGlobalChunkKey());
        final int chunkZ = ChunkPos.getZ(left.targetGlobalChunkKey());
        assert rightChunkX == leftChunkX + 1;
        assert ChunkPos.getZ(right.targetGlobalChunkKey()) == chunkZ;

        final int sectionY = minSection + 4;
        final int blockY = sectionY * 16;
        final int blockZ = chunkZ * 16;
        assert view.stateAt(leftChunkX * 16 + 15, blockY, blockZ).is(Blocks.STONE);
        assert view.stateAt(rightChunkX * 16, blockY, blockZ).is(Blocks.DEEPSLATE);

        // The serialized snapshot has no section above/below and no neighboring Z chunk.
        // These positions must be air instead of falling through to the live target world.
        assert view.stateAt(leftChunkX * 16, blockY - 1, blockZ).isAir();
        assert view.stateAt(leftChunkX * 16, blockY + 16, blockZ).isAir();
        assert view.stateAt(leftChunkX * 16, blockY, blockZ - 1).isAir();
    }

    private static void negativeGlobalCoordinatesUseFloorSectionMapping() {
        final SubLevelReconstructionDecodedPayload decoded = decoded(
                List.of(new ChunkInput(ChunkPos.asLong(0, 0), "0", Blocks.OBSIDIAN.defaultBlockState())),
                -100,
                -100
        );
        final int minSection = -4;
        final SubLevelReconstructionDecodedBlockStateView view =
                SubLevelReconstructionDecodedBlockStateView.createFrom(decoded, minSection);
        final SubLevelReconstructionDecodedPayload.DecodedChunk chunk = decoded.chunks().getFirst();
        final int chunkX = ChunkPos.getX(chunk.targetGlobalChunkKey());
        final int chunkZ = ChunkPos.getZ(chunk.targetGlobalChunkKey());
        assert chunkX < 0;
        assert chunkZ < 0;

        final int blockX = chunkX * 16;
        final int blockY = minSection * 16;
        final int blockZ = chunkZ * 16;
        assert view.stateAt(blockX, blockY, blockZ).is(Blocks.OBSIDIAN);
        assert view.stateAt(blockX + 15, blockY + 15, blockZ + 15).is(Blocks.OBSIDIAN);
        assert view.stateAt(blockX - 1, blockY, blockZ).isAir();
        assert view.stateAt(blockX, blockY - 1, blockZ).isAir();
    }

    private static SubLevelReconstructionDecodedPayload decoded(
            final List<ChunkInput> chunks,
            final int originX,
            final int originZ
    ) {
        final UUID uuid = UUID.randomUUID();
        final CompoundTag fullTag = new CompoundTag();
        fullTag.putUUID("uuid", uuid);
        final CompoundTag plot = new CompoundTag();
        plot.putInt("log_size", 4);
        plot.putInt("data_version", 1);
        plot.putString("biome", "minecraft:plains");
        final CompoundTag chunkTags = new CompoundTag();
        for (final ChunkInput chunk : chunks) {
            chunkTags.put(String.valueOf(chunk.localKey()), chunk(chunk.sectionIndex(), chunk.state()));
        }
        plot.put("chunks", chunkTags);
        fullTag.put("plot", plot);

        final SubLevelData data = new SubLevelData(
                uuid,
                new BoundingBox3d(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                new Pose3d(
                        new Vector3d(1.0, 2.0, 3.0),
                        new Quaterniond(),
                        new Vector3d(0.5, 0.5, 0.5),
                        new Vector3d(1.0)
                ),
                List.of(),
                fullTag
        );
        final SubLevelReconstructionPlan plan = SubLevelReconstructionPlan.freezeAccepted(
                data,
                new SubLevelReconstructionPreflight.TargetSlot(4, 5)
        );
        final SubLevelReconstructionStagedPayload staged =
                SubLevelReconstructionStagedPayload.captureFrom(plan, originX, originZ, 4)
                        .payload()
                        .orElseThrow();
        return SubLevelReconstructionDecodedPayload.decodeFrom(staged, 24)
                .payload()
                .orElseThrow();
    }

    private static CompoundTag chunk(final String sectionIndex, final BlockState state) {
        final CompoundTag chunk = new CompoundTag();
        final CompoundTag sections = new CompoundTag();
        final CompoundTag section = new CompoundTag();
        final PalettedContainer<BlockState> states = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                state,
                PalettedContainer.Strategy.SECTION_STATES
        );
        section.put("block_states", blockStateCodec().encodeStart(NbtOps.INSTANCE, states).getOrThrow());
        sections.put(sectionIndex, section);
        chunk.put("sections", sections);
        chunk.put("block_ticks", new ListTag());
        chunk.put("fluid_ticks", new ListTag());
        chunk.put("heightmaps", new CompoundTag());
        chunk.put("block_entities", new ListTag());
        return chunk;
    }

    private static Codec<PalettedContainer<BlockState>> blockStateCodec() {
        return PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY,
                BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState()
        );
    }

    private record ChunkInput(long localKey, String sectionIndex, BlockState state) {
    }
}
