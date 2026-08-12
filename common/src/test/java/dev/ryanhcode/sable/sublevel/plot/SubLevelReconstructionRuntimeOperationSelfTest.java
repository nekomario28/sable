package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport;

import java.util.Set;

/** Assertion-based executable for operational reconstruction physics gates. */
public final class SubLevelReconstructionRuntimeOperationSelfTest {
    private SubLevelReconstructionRuntimeOperationSelfTest() {
    }

    public static void main(final String[] args) {
        capabilityBooleanWithoutSectionOperationFailsClosed();
        capabilityBooleanWithoutBodyOperationFailsClosed();
        sectionOperationDoesNotReplaceCapabilityProof();
        bodyOperationDoesNotReplaceCapabilityProof();
        completeOperationsAndCapabilitiesAreAccepted();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_RUNTIME_OPERATION_SELF_TEST: PASS");
    }

    private static void capabilityBooleanWithoutSectionOperationFailsClosed() {
        final SubLevelReconstructionRuntimePreflight.Result result =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        true,
                        false,
                        true,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(true, true)
                );

        assert !result.accepted();
        assert result.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.SECTION_OWNERSHIP_OPERATION_UNAVAILABLE
        ));
    }

    private static void capabilityBooleanWithoutBodyOperationFailsClosed() {
        final SubLevelReconstructionRuntimePreflight.Result result =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        true,
                        true,
                        false,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(true, true)
                );

        assert !result.accepted();
        assert result.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.BODY_LIFECYCLE_OPERATION_UNAVAILABLE
        ));
    }

    private static void sectionOperationDoesNotReplaceCapabilityProof() {
        final SubLevelReconstructionRuntimePreflight.Result result =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        true,
                        true,
                        true,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(false, true)
                );

        assert !result.accepted();
        assert result.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.EXACT_SECTION_ROLLBACK_UNAVAILABLE
        ));
    }

    private static void bodyOperationDoesNotReplaceCapabilityProof() {
        final SubLevelReconstructionRuntimePreflight.Result result =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        true,
                        true,
                        true,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(true, false)
                );

        assert !result.accepted();
        assert result.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.PROVISIONAL_BODY_LIFECYCLE_UNAVAILABLE
        ));
    }

    private static void completeOperationsAndCapabilitiesAreAccepted() {
        final SubLevelReconstructionRuntimePreflight.Result result =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        true,
                        true,
                        true,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(true, true)
                );

        assert result.accepted();
        assert result.failures().isEmpty();
    }
}
