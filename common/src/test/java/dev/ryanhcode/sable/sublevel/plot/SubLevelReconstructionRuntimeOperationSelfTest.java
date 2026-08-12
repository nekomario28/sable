package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport;

import java.util.Set;

/** Assertion-based executable for operational reconstruction physics gates. */
public final class SubLevelReconstructionRuntimeOperationSelfTest {
    private SubLevelReconstructionRuntimeOperationSelfTest() {
    }

    public static void main(final String[] args) {
        capabilityBooleanWithoutSectionOperationFailsClosed();
        sectionOperationDoesNotReplaceCapabilityProof();
        completeOperationsAndCapabilitiesAreAccepted();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_RUNTIME_OPERATION_SELF_TEST: PASS");
    }

    private static void capabilityBooleanWithoutSectionOperationFailsClosed() {
        final SubLevelReconstructionRuntimePreflight.Result result =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        true,
                        false,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(true, true)
                );

        assert !result.accepted();
        assert result.failures().equals(Set.of(
                SubLevelReconstructionRuntimePreflight.Failure.SECTION_OWNERSHIP_OPERATION_UNAVAILABLE
        ));
    }

    private static void sectionOperationDoesNotReplaceCapabilityProof() {
        final SubLevelReconstructionRuntimePreflight.Result result =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
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

    private static void completeOperationsAndCapabilitiesAreAccepted() {
        final SubLevelReconstructionRuntimePreflight.Result result =
                SubLevelReconstructionRuntimePreflight.validateCapabilities(
                        true,
                        true,
                        true,
                        new SubLevelReconstructionPhysicsSupport.Capabilities(true, true)
                );

        assert result.accepted();
        assert result.failures().isEmpty();
    }
}
