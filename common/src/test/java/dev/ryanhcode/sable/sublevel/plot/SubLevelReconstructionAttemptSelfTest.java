package dev.ryanhcode.sable.sublevel.plot;

import java.util.EnumSet;
import java.util.Set;

/** Assertion-based executable for prepared-attempt result semantics. */
public final class SubLevelReconstructionAttemptSelfTest {
    private SubLevelReconstructionAttemptSelfTest() {
    }

    public static void main(final String[] args) {
        preflightRejectionOwnsFailureEvidence();
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
