package dev.ryanhcode.sable.sublevel.plot;

import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Assertion-based executable for detached decoded reconstruction payloads. */
public final class SubLevelReconstructionDecodedPayloadSelfTest {
    private SubLevelReconstructionDecodedPayloadSelfTest() {
    }

    public static void main(final String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        validChunkIsDecodedWithoutCoreNbtReuse();
        invalidSectionIndexFailsClosed();
        repeatedFailureTracksEveryChunk();
        decodedCopiesAreDefensivelyOwned();
        decodedBoundsMatchPlotChunkHolderSemantics();
        allAirDecodedBoundsFailClosed();
        decodeRejectionOwnsFailureEvidence();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_DECODED_PAYLOAD_SELF_TEST: PASS");
    }

    private static void validChunkIsDecodedWithoutCoreNbtReuse() {
        final long localKey = ChunkPos.asLong(0, 0);
        final SubLevelReconstructionStagedPayload staged = staged(List.of(
                chunk(localKey, "0", Blocks.STONE.defaultBlockState(), true)
        ));

        final SubLevelReconstructionDecodedPayload.Capture capture =
                SubLevelReconstructionDecodedPayload.decodeFrom(staged, 24);

        assert capture.accepted();
        final SubLevelReconstructionDecodedPayload decoded = capture.payload().orElseThrow();
        assert decoded.chunks().size() == 1;
        final SubLevelReconstructionDecodedPayload.DecodedChunk chunk = decoded.chunks().getFirst();
        assert chunk.sections().size() == 1;
        assert chunk.sections().getFirst().stateAt(0, 0, 0).is(Blocks.STONE);
        assert chunk.sections().getFirst().stateAt(15, 15, 15).is(Blocks.STONE);
        assert chunk.lightCorrect();
        assert chunk.platformPayload().getString("platform_marker").equals("preserved");
        assert !chunk.platformPayload().contains("sections");
        assert !chunk.platformPayload().contains("block_ticks");
        assert !chunk.platformPayload().contains("fluid_ticks");
        assert !chunk.platformPayload().contains("heightmaps");
        assert !chunk.platformPayload().contains("block_entities");
        assert !chunk.platformPayload().contains("isLightOn");
    }

    private static void invalidSectionIndexFailsClosed() {
        final long localKey = ChunkPos.asLong(0, 0);
        final SubLevelReconstructionStagedPayload staged = staged(List.of(
                chunk(localKey, "24", Blocks.STONE.defaultBlockState(), false)
        ));

        final SubLevelReconstructionDecodedPayload.Capture capture =
                SubLevelReconstructionDecodedPayload.decodeFrom(staged, 24);

        assert !capture.accepted();
        assert capture.payload().isEmpty();
        assert capture.failures().equals(Set.of(
                SubLevelReconstructionDecodedPayload.Failure.INVALID_SECTION_INDEX
        ));
        assert capture.failedChunkKeys().size() == 1;
    }

    private static void repeatedFailureTracksEveryChunk() {
        final long first = ChunkPos.asLong(0, 0);
        final long second = ChunkPos.asLong(1, 0);
        final SubLevelReconstructionStagedPayload staged = staged(List.of(
                chunk(first, "24", Blocks.STONE.defaultBlockState(), false),
                chunk(second, "24", Blocks.STONE.defaultBlockState(), false)
        ));

        final SubLevelReconstructionDecodedPayload.Capture capture =
                SubLevelReconstructionDecodedPayload.decodeFrom(staged, 24);

        assert !capture.accepted();
        assert capture.failures().equals(Set.of(
                SubLevelReconstructionDecodedPayload.Failure.INVALID_SECTION_INDEX
        ));
        assert capture.failedChunkKeys().size() == 2;
    }

    private static void decodedCopiesAreDefensivelyOwned() {
        final long localKey = ChunkPos.asLong(0, 0);
        final SubLevelReconstructionStagedPayload staged = staged(List.of(
                chunk(localKey, "0", Blocks.STONE.defaultBlockState(), false)
        ));
        final SubLevelReconstructionDecodedPayload.DecodedChunk decoded =
                SubLevelReconstructionDecodedPayload.decodeFrom(staged, 24)
                        .payload()
                        .orElseThrow()
                        .chunks()
                        .getFirst();

        final CompoundTag exportedPlatform = decoded.platformPayload();
        exportedPlatform.putString("platform_marker", "mutated");
        assert decoded.platformPayload().getString("platform_marker").equals("preserved");

        assertUnsupported(() -> decoded.sections().clear());
        assertUnsupported(() -> decoded.blockTicks().clear());
        assertUnsupported(() -> decoded.blockEntities().clear());
    }

    private static void decodedBoundsMatchPlotChunkHolderSemantics() {
        final long localKey = ChunkPos.asLong(1, 2);
        final SubLevelReconstructionDecodedPayload decoded =
                SubLevelReconstructionDecodedPayload.decodeFrom(
                                staged(List.of(chunk(localKey, "3", Blocks.STONE.defaultBlockState(), false))),
                                24
                        )
                        .payload()
                        .orElseThrow();
        final SubLevelReconstructionDecodedPayload.DecodedChunk decodedChunk = decoded.chunks().getFirst();
        final int minSection = -4;
        final SubLevelReconstructionDecodedBounds.Capture capture =
                SubLevelReconstructionDecodedBounds.computeFrom(decoded, minSection);

        assert capture.accepted();
        final BoundingBox3ic bounds = capture.bounds().orElseThrow();
        final int globalChunkX = ChunkPos.getX(decodedChunk.targetGlobalChunkKey());
        final int globalChunkZ = ChunkPos.getZ(decodedChunk.targetGlobalChunkKey());
        final int sectionY = minSection + decodedChunk.sections().getFirst().sectionIndex();
        assert bounds.minX() == globalChunkX * 16;
        assert bounds.maxX() == globalChunkX * 16 + 15;
        assert bounds.minY() == sectionY * 16;
        assert bounds.maxY() == sectionY * 16 + 15;
        assert bounds.minZ() == globalChunkZ * 16;
        assert bounds.maxZ() == globalChunkZ * 16 + 15;
    }

    private static void allAirDecodedBoundsFailClosed() {
        final long localKey = ChunkPos.asLong(0, 0);
        final SubLevelReconstructionDecodedPayload decoded =
                SubLevelReconstructionDecodedPayload.decodeFrom(
                                staged(List.of(chunk(localKey, "0", Blocks.AIR.defaultBlockState(), false))),
                                24
                        )
                        .payload()
                        .orElseThrow();
        final SubLevelReconstructionDecodedBounds.Capture capture =
                SubLevelReconstructionDecodedBounds.computeFrom(decoded, -4);

        assert !capture.accepted();
        assert capture.bounds().isEmpty();
        assert capture.failures().equals(Set.of(
                SubLevelReconstructionDecodedBounds.Failure.EMPTY_NON_AIR_CONTENT
        ));
    }

    private static void decodeRejectionOwnsFailureEvidence() {
        final EnumSet<SubLevelReconstructionDecodedPayload.Failure> failures =
                EnumSet.of(SubLevelReconstructionDecodedPayload.Failure.INVALID_SECTION_INDEX);
        final HashSet<Long> failedChunkKeys = new HashSet<>(Set.of(42L, 84L));
        final SubLevelReconstructionAttempt.DecodeRejected rejected =
                new SubLevelReconstructionAttempt.DecodeRejected(failures, failedChunkKeys);

        failures.clear();
        failedChunkKeys.clear();

        assert !rejected.accepted();
        assert rejected.failures().equals(Set.of(
                SubLevelReconstructionDecodedPayload.Failure.INVALID_SECTION_INDEX
        ));
        assert rejected.failedChunkKeys().equals(Set.of(42L, 84L));
        assertUnsupported(() -> rejected.failures().clear());
        assertUnsupported(() -> rejected.failedChunkKeys().clear());
        assertIllegalArgument(() -> new SubLevelReconstructionAttempt.DecodeRejected(Set.of(), Set.of()));
    }

    private static SubLevelReconstructionStagedPayload staged(final List<ChunkInput> chunks) {
        final UUID uuid = UUID.randomUUID();
        final CompoundTag fullTag = new CompoundTag();
        fullTag.putUUID("uuid", uuid);
        final CompoundTag plot = new CompoundTag();
        plot.putInt("log_size", 4);
        plot.putInt("data_version", 1);
        plot.putString("biome", "minecraft:plains");
        final CompoundTag chunkTags = new CompoundTag();
        for (final ChunkInput chunk : chunks) {
            chunkTags.put(String.valueOf(chunk.localKey()), chunk.payload());
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
        return SubLevelReconstructionStagedPayload.captureFrom(plan, 0, 0, 4)
                .payload()
                .orElseThrow();
    }

    private static ChunkInput chunk(
            final long localKey,
            final String sectionIndex,
            final BlockState state,
            final boolean lightCorrect
    ) {
        final CompoundTag chunk = new CompoundTag();
        chunk.putBoolean("isLightOn", lightCorrect);
        chunk.putString("platform_marker", "preserved");

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
        return new ChunkInput(localKey, chunk);
    }

    private static Codec<PalettedContainer<BlockState>> blockStateCodec() {
        return PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY,
                BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState()
        );
    }

    private static void assertUnsupported(final Runnable operation) {
        boolean threw = false;
        try {
            operation.run();
        } catch (final UnsupportedOperationException expected) {
            threw = true;
        }
        assert threw;
    }

    private static void assertIllegalArgument(final Runnable operation) {
        boolean threw = false;
        try {
            operation.run();
        } catch (final IllegalArgumentException expected) {
            threw = true;
        }
        assert threw;
    }

    private record ChunkInput(long localKey, CompoundTag payload) {
    }
}
