package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** Assertion-based executable for prepared-attempt result semantics. */
public final class SubLevelReconstructionAttemptSelfTest {
    private SubLevelReconstructionAttemptSelfTest() {
    }

    public static void main(final String[] args) {
        preflightRejectionOwnsFailureEvidence();
        payloadRejectionOwnsFailureEvidence();
        publicationRejectionOwnsFailureEvidence();
        runtimeRejectionOwnsFailureEvidence();
        runtimeCapabilitiesFailClosed();
        baselineRejectionOwnsFailureEvidence();
        emptyRejectionsAreRejected();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_ATTEMPT_SELF_TEST: PASS");
    }

    private static void preflightRejectionOwnsFailureEvidence() {
        final EnumSet<SubLevelReconstructionPreflight.Failure> source =
                EnumSet.of(SubLevelReconstructionPreflight.Failure.TARGET_SLOT_OCCUPIED);
        final SubLevelReconstructionAttempt.PreflightRejected rejected =
                new SubLevelReconstructionAttempt.PreflightRejected(source);

        source.clear();

        assert !rejected.accepted();
        assert rejected.failures().equals(Set.of(
                SubLevelReconstructionPreflight.Failure.TARGET_SLOT_OCCUPIED
        ));
        assertUnsupported(() -> rejected.failures().clear());
    }

    private static void payloadRejectionOwnsFailureEvidence() {
        final EnumSet<SubLevelReconstructionPayloadPreflight.Failure> source =
                EnumSet.of(SubLevelReconstructionPayloadPreflight.Failure.INVALID_BLOCK_STATES);
        final SubLevelReconstructionAttempt.PayloadRejected rejected =
                new SubLevelReconstructionAttempt.PayloadRejected(source);

        source.clear();

        assert !rejected.accepted();
        assert rejected.failures().equals(Set.of(
                SubLevelReconstructionPayloadPreflight.Failure.INVALID_BLOCK_STATES
        ));
        assertUnsupported(() -> rejected.failures().clear());
    }

    private static void publicationRejectionOwnsFailureEvidence() {
        final EnumSet<SubLevelReconstructionPublicationPreflight.Failure> failures =
                EnumSet.of(SubLevelReconstructionPublicationPreflight.Failure.TARGET_CHUNK_VISIBLE);
        final HashSet<Long> blocked = new HashSet<>(Set.of(42L));
        final SubLevelReconstructionAttempt.PublicationRejected rejected =
                new SubLevelReconstructionAttempt.PublicationRejected(failures, blocked);

        failures.clear();
        blocked.clear();

        assert !rejected.accepted();
        assert rejected.failures().equals(Set.of(
                SubLevelReconstructionPublicationPreflight.Failure.TARGET_CHUNK_VISIBLE
        ));
        assert rejected.blockedChunkKeys().equals(Set.of(42L));
        assertUnsupported(() -> rejected.failures().clear());
        assertUnsupported(() -> rejected.blockedChunkKeys().clear());
    }

    private static void runtimeRejectionOwnsFailureEvidence() {
        final EnumSet<SubLevelReconstructionRuntimePreflight.Failure> source =
                EnumSet.of(SubLevelReconstructionRuntimePreflight.Failure.EXACT_SECTION_ROLLBACK_UNAVAILABLE);
        final SubLevelReconstructionAttempt.RuntimeRejected rejected =
                new SubLevelReconstructionAttempt.RuntimeRejected(source);

        source.clear();

        assert !rejected.accepted();
        assert rejected.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.EXACT_SECTION_ROLLBACK_UNAVAILABLE
        ));
        assertUnsupported(() -> rejected.failures().clear());
    }

    private static void runtimeCapabilitiesFailClosed() {
        final SubLevelReconstructionRuntimePreflight.Result noPhysics =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(false, null);
        assert !noPhysics.accepted();
        assert noPhysics.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.PHYSICS_SYSTEM_UNAVAILABLE
        ));

        final SubLevelReconstructionRuntimePreflight.Result noOptIn =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(true, null);
        assert !noOptIn.accepted();
        assert noOptIn.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.EXACT_SECTION_ROLLBACK_UNAVAILABLE,
                SubLevelReconstructionRuntimePreflight.Failure.PROVISIONAL_BODY_LIFECYCLE_UNAVAILABLE
        ));

        final SubLevelReconstructionRuntimePreflight.Result sectionOnly =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(true, false)
                );
        assert !sectionOnly.accepted();
        assert sectionOnly.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.PROVISIONAL_BODY_LIFECYCLE_UNAVAILABLE
        ));

        final SubLevelReconstructionRuntimePreflight.Result bodyOnly =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(false, true)
                );
        assert !bodyOnly.accepted();
        assert bodyOnly.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.EXACT_SECTION_ROLLBACK_UNAVAILABLE
        ));

        final SubLevelReconstructionRuntimePreflight.Result complete =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(true, true)
                );
        assert complete.accepted();
        assert complete.failures().isEmpty();
    }

    private static void baselineRejectionOwnsFailureEvidence() {
        final EnumSet<SubLevelReconstructionContainerBaseline.Failure> source =
                EnumSet.of(SubLevelReconstructionContainerBaseline.Failure.PRECONDITION_DRIFT);
        final SubLevelReconstructionAttempt.BaselineRejected rejected =
                new SubLevelReconstructionAttempt.BaselineRejected(source);

        source.clear();

        assert !rejected.accepted();
        assert rejected.failures().equals(Set.of(
                SubLevelReconstructionContainerBaseline.Failure.PRECONDITION_DRIFT
        ));
        assertUnsupported(() -> rejected.failures().clear());
    }

    private static void emptyRejectionsAreRejected() {
        assertIllegalArgument(() -> new SubLevelReconstructionAttempt.PreflightRejected(Set.of()));
        assertIllegalArgument(() -> new SubLevelReconstructionAttempt.PayloadRejected(Set.of()));
        assertIllegalArgument(() -> new SubLevelReconstructionAttempt.PublicationRejected(Set.of(), Set.of()));
        assertIllegalArgument(() -> new SubLevelReconstructionAttempt.RuntimeRejected(Set.of()));
        assertIllegalArgument(() -> new SubLevelReconstructionAttempt.BaselineRejected(Set.of()));
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
