package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Assertion-based executable for canonical immutable reconstruction staging. */
public final class SubLevelReconstructionStagedPayloadSelfTest {
    private SubLevelReconstructionStagedPayloadSelfTest() {
    }

    public static void main(final String[] args) {
        chunksAreMappedAndCanonicallyOrdered();
        stagedNbtIsDefensivelyOwned();
        invalidChunkKeyFailsClosed();
        stagingRejectionOwnsFailureEvidence();
        captureEvidenceIsImmutable();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_STAGED_PAYLOAD_SELF_TEST: PASS");
    }

    private static void chunksAreMappedAndCanonicallyOrdered() {
        final SubLevelReconstructionPlan plan = planWithChunks(List.of(
                ChunkPos.asLong(2, 1),
                ChunkPos.asLong(0, 0),
                ChunkPos.asLong(1, 0)
        ));
        final SubLevelReconstructionStagedPayload.Capture capture =
                SubLevelReconstructionStagedPayload.captureFrom(plan, 10_000, 10_000, 7);

        assert capture.accepted();
        final SubLevelReconstructionStagedPayload staged = capture.payload().orElseThrow();
        assert staged.chunks().size() == 3;

        final List<Long> actual = staged.chunks().stream()
                .map(SubLevelReconstructionStagedPayload.ChunkSnapshot::targetGlobalChunkKey)
                .toList();
        final List<Long> expected = new ArrayList<>(actual);
        expected.sort(Comparator.naturalOrder());
        assert actual.equals(expected);

        for (final SubLevelReconstructionStagedPayload.ChunkSnapshot chunk : staged.chunks()) {
            final long expectedGlobal = SubLevelReconstructionPublicationPreflight.targetGlobalChunkKey(
                    plan.targetSlot().plotX(),
                    plan.targetSlot().plotZ(),
                    10_000,
                    10_000,
                    7,
                    chunk.localChunkKey()
            );
            assert chunk.targetGlobalChunkKey() == expectedGlobal;
        }
    }

    private static void stagedNbtIsDefensivelyOwned() {
        final SubLevelReconstructionPlan plan = planWithChunks(List.of(ChunkPos.asLong(0, 0)));
        final SubLevelReconstructionStagedPayload staged =
                SubLevelReconstructionStagedPayload.captureFrom(plan, 0, 0, 4)
                        .payload()
                        .orElseThrow();

        final CompoundTag metadata = staged.plotMetadata();
        assert !metadata.contains("chunks");
        assert metadata.getString("marker").equals("plot-metadata");
        metadata.putString("marker", "mutated");
        assert staged.plotMetadata().getString("marker").equals("plot-metadata");

        final SubLevelReconstructionStagedPayload.ChunkSnapshot chunk = staged.chunks().getFirst();
        final CompoundTag exported = chunk.chunkTag();
        assert exported.getString("marker").equals("chunk-0");
        exported.putString("marker", "mutated");
        assert chunk.chunkTag().getString("marker").equals("chunk-0");

        assertUnsupported(() -> staged.chunks().clear());
    }

    private static void invalidChunkKeyFailsClosed() {
        final SubLevelReconstructionPlan plan = planWithSerializedKey("not-a-long");
        final SubLevelReconstructionStagedPayload.Capture capture =
                SubLevelReconstructionStagedPayload.captureFrom(plan, 0, 0, 4);

        assert !capture.accepted();
        assert capture.payload().isEmpty();
        assert capture.failures().equals(Set.of(
                SubLevelReconstructionStagedPayload.Failure.INVALID_CHUNK_KEY
        ));
    }

    private static void stagingRejectionOwnsFailureEvidence() {
        final EnumSet<SubLevelReconstructionStagedPayload.Failure> source =
                EnumSet.of(SubLevelReconstructionStagedPayload.Failure.INVALID_CHUNK_KEY);
        final SubLevelReconstructionAttempt.StagingRejected rejected =
                new SubLevelReconstructionAttempt.StagingRejected(source);

        source.clear();

        assert !rejected.accepted();
        assert rejected.failures().equals(Set.of(
                SubLevelReconstructionStagedPayload.Failure.INVALID_CHUNK_KEY
        ));
        assertUnsupported(() -> rejected.failures().clear());
        assertIllegalArgument(() -> new SubLevelReconstructionAttempt.StagingRejected(Set.of()));
    }

    private static void captureEvidenceIsImmutable() {
        final SubLevelReconstructionStagedPayload.Capture capture =
                new SubLevelReconstructionStagedPayload.Capture(
                        Set.of(SubLevelReconstructionStagedPayload.Failure.INVALID_CHUNK_PAYLOAD),
                        java.util.Optional.empty()
                );
        assertUnsupported(() -> capture.failures().clear());
        assertIllegalArgument(() -> new SubLevelReconstructionStagedPayload.Capture(
                Set.of(),
                java.util.Optional.empty()
        ));
    }

    private static SubLevelReconstructionPlan planWithChunks(final List<Long> chunkKeys) {
        final UUID uuid = UUID.randomUUID();
        final CompoundTag fullTag = baseTag(uuid);
        final CompoundTag plot = fullTag.getCompound("plot");
        final CompoundTag chunks = new CompoundTag();
        for (int index = 0; index < chunkKeys.size(); index++) {
            final CompoundTag chunk = new CompoundTag();
            chunk.putString("marker", "chunk-" + index);
            chunks.put(String.valueOf(chunkKeys.get(index)), chunk);
        }
        plot.put("chunks", chunks);
        return freeze(uuid, fullTag);
    }

    private static SubLevelReconstructionPlan planWithSerializedKey(final String serializedKey) {
        final UUID uuid = UUID.randomUUID();
        final CompoundTag fullTag = baseTag(uuid);
        final CompoundTag chunks = new CompoundTag();
        chunks.put(serializedKey, new CompoundTag());
        fullTag.getCompound("plot").put("chunks", chunks);
        return freeze(uuid, fullTag);
    }

    private static CompoundTag baseTag(final UUID uuid) {
        final CompoundTag fullTag = new CompoundTag();
        fullTag.putUUID("uuid", uuid);
        final CompoundTag plot = new CompoundTag();
        plot.putString("marker", "plot-metadata");
        fullTag.put("plot", plot);
        return fullTag;
    }

    private static SubLevelReconstructionPlan freeze(final UUID uuid, final CompoundTag fullTag) {
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
        return SubLevelReconstructionPlan.freezeAccepted(
                data,
                new SubLevelReconstructionPreflight.TargetSlot(4, 5)
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
}
