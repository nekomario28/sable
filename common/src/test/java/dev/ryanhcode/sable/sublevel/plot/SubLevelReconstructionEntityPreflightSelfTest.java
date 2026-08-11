package dev.ryanhcode.sable.sublevel.plot;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Assertion-based executable for target entity-residue preflight semantics. */
public final class SubLevelReconstructionEntityPreflightSelfTest {
    private SubLevelReconstructionEntityPreflightSelfTest() {
    }

    public static void main(final String[] args) {
        cleanTargetsAreAccepted();
        residueRetainsExactBlockedKeys();
        invalidTargetEvidenceSkipsEntityLookup();
        resultEvidenceIsImmutable();
        inconsistentEvidenceIsRejected();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_ENTITY_PREFLIGHT_SELF_TEST: PASS");
    }

    private static void cleanTargetsAreAccepted() {
        final SubLevelReconstructionEntityPreflight.Result result =
                SubLevelReconstructionEntityPreflight.validateTargets(
                        new SubLevelReconstructionEntityPreflight.TargetChunks(Set.of(11L, 22L), Set.of()),
                        ignored -> false
                );

        assert result.accepted();
        assert result.failures().isEmpty();
        assert result.blockedChunkKeys().isEmpty();
    }

    private static void residueRetainsExactBlockedKeys() {
        final SubLevelReconstructionEntityPreflight.Result result =
                SubLevelReconstructionEntityPreflight.validateTargets(
                        new SubLevelReconstructionEntityPreflight.TargetChunks(Set.of(11L, 22L, 33L), Set.of()),
                        key -> key == 11L || key == 33L
                );

        assert !result.accepted();
        assert result.failures().equals(Set.of(
                SubLevelReconstructionEntityPreflight.Failure.TARGET_ENTITY_RESIDUE
        ));
        assert result.blockedChunkKeys().equals(Set.of(11L, 33L));
    }

    private static void invalidTargetEvidenceSkipsEntityLookup() {
        final AtomicInteger calls = new AtomicInteger();
        final SubLevelReconstructionEntityPreflight.Result result =
                SubLevelReconstructionEntityPreflight.validateTargets(
                        new SubLevelReconstructionEntityPreflight.TargetChunks(
                                Set.of(),
                                Set.of(SubLevelReconstructionEntityPreflight.Failure.INVALID_CHUNK_KEY)
                        ),
                        ignored -> {
                            calls.incrementAndGet();
                            return true;
                        }
                );

        assert calls.get() == 0;
        assert result.failures().equals(Set.of(
                SubLevelReconstructionEntityPreflight.Failure.INVALID_CHUNK_KEY
        ));
        assert result.blockedChunkKeys().isEmpty();
    }

    private static void resultEvidenceIsImmutable() {
        final SubLevelReconstructionEntityPreflight.Result result =
                new SubLevelReconstructionEntityPreflight.Result(
                        Set.of(SubLevelReconstructionEntityPreflight.Failure.TARGET_ENTITY_RESIDUE),
                        Set.of(42L)
                );

        assertUnsupported(() -> result.failures().clear());
        assertUnsupported(() -> result.blockedChunkKeys().clear());
    }

    private static void inconsistentEvidenceIsRejected() {
        assertIllegalArgument(() -> new SubLevelReconstructionEntityPreflight.Result(
                Set.of(),
                Set.of(1L)
        ));
        assertIllegalArgument(() -> new SubLevelReconstructionEntityPreflight.Result(
                Set.of(SubLevelReconstructionEntityPreflight.Failure.TARGET_ENTITY_RESIDUE),
                Set.of()
        ));
        assertIllegalArgument(() -> new SubLevelReconstructionEntityPreflight.TargetChunks(
                Set.of(1L),
                Set.of(SubLevelReconstructionEntityPreflight.Failure.INVALID_CHUNK_KEY)
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
