package dev.ryanhcode.sable.sublevel.plot;

import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/** Assertion-based executable for target ChunkMap publication preflight seams. */
public final class SubLevelReconstructionPublicationPreflightSelfTest {
    private SubLevelReconstructionPublicationPreflightSelfTest() {
    }

    public static void main(final String[] args) {
        localChunkMapsIntoReservedTargetPlot();
        publicationFailureRetainsBlockedChunkEvidence();
        environmentFailureNeedsNoChunkEvidence();
        inconsistentEvidenceIsRejected();
        invalidPlotScaleIsRejected();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_PUBLICATION_PREFLIGHT_SELF_TEST: PASS");
    }

    private static void localChunkMapsIntoReservedTargetPlot() {
        final long mapped = SubLevelReconstructionPublicationPreflight.targetGlobalChunkKey(
                5,
                7,
                10_000,
                10_000,
                7,
                ChunkPos.asLong(2, 3)
        );

        assert ChunkPos.getX(mapped) == ((10_005 << 7) + 2);
        assert ChunkPos.getZ(mapped) == ((10_007 << 7) + 3);
    }

    private static void publicationFailureRetainsBlockedChunkEvidence() {
        final long key = ChunkPos.asLong(12, 34);
        final SubLevelReconstructionPublicationPreflight.Result result =
                new SubLevelReconstructionPublicationPreflight.Result(
                        Set.of(
                                SubLevelReconstructionPublicationPreflight.Failure.TARGET_CHUNK_UPDATING,
                                SubLevelReconstructionPublicationPreflight.Failure.TARGET_CHUNK_VISIBLE
                        ),
                        Set.of(key)
                );

        assert !result.accepted();
        assert result.blockedChunkKeys().equals(Set.of(key));
        assertUnsupported(() -> result.blockedChunkKeys().clear());
    }

    private static void environmentFailureNeedsNoChunkEvidence() {
        final SubLevelReconstructionPublicationPreflight.Result result =
                new SubLevelReconstructionPublicationPreflight.Result(
                        Set.of(SubLevelReconstructionPublicationPreflight.Failure.CONTAINER_UNAVAILABLE),
                        Set.of()
                );
        assert !result.accepted();
    }

    private static void inconsistentEvidenceIsRejected() {
        assertIllegalArgument(() -> new SubLevelReconstructionPublicationPreflight.Result(
                Set.of(),
                Set.of(1L)
        ));
        assertIllegalArgument(() -> new SubLevelReconstructionPublicationPreflight.Result(
                Set.of(SubLevelReconstructionPublicationPreflight.Failure.TARGET_CHUNK_PENDING_UNLOAD),
                Set.of()
        ));
    }

    private static void invalidPlotScaleIsRejected() {
        assertIllegalArgument(() -> SubLevelReconstructionPublicationPreflight.targetGlobalChunkKey(
                0,
                0,
                0,
                0,
                31,
                ChunkPos.asLong(0, 0)
        ));
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
