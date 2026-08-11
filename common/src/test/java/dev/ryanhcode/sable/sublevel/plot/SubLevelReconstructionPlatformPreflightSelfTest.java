package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.platform.SubLevelReconstructionPlotPlatformSupport;

import java.util.Set;

/** Assertion-based executable for transactional platform callback capability semantics. */
public final class SubLevelReconstructionPlatformPreflightSelfTest {
    private SubLevelReconstructionPlatformPreflightSelfTest() {
    }

    public static void main(final String[] args) {
        missingOptInFailsClosed();
        partialPlotSupportReportsExactFailures();
        missingEventDeferralIsRejected();
        completeSupportIsAccepted();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_PLATFORM_PREFLIGHT_SELF_TEST: PASS");
    }

    private static void missingOptInFailsClosed() {
        final SubLevelReconstructionPlatformPreflight.Result result =
                SubLevelReconstructionPlatformPreflight.validateCapabilities(null, null);

        assert result.failures().equals(Set.of(
                SubLevelReconstructionPlatformPreflight.Failure.DETACHED_CHUNK_DATA_READ_UNAVAILABLE,
                SubLevelReconstructionPlatformPreflight.Failure.POST_LOAD_DEFER_UNAVAILABLE,
                SubLevelReconstructionPlatformPreflight.Failure.CHUNK_LOAD_EVENT_DEFER_UNAVAILABLE
        ));
    }

    private static void partialPlotSupportReportsExactFailures() {
        final SubLevelReconstructionPlatformPreflight.Result result =
                SubLevelReconstructionPlatformPreflight.validateCapabilities(
                        new SubLevelReconstructionPlotPlatformSupport.Capabilities(true, false),
                        true
                );

        assert result.failures().equals(Set.of(
                SubLevelReconstructionPlatformPreflight.Failure.POST_LOAD_DEFER_UNAVAILABLE
        ));
    }

    private static void missingEventDeferralIsRejected() {
        final SubLevelReconstructionPlatformPreflight.Result result =
                SubLevelReconstructionPlatformPreflight.validateCapabilities(
                        new SubLevelReconstructionPlotPlatformSupport.Capabilities(true, true),
                        false
                );

        assert result.failures().equals(Set.of(
                SubLevelReconstructionPlatformPreflight.Failure.CHUNK_LOAD_EVENT_DEFER_UNAVAILABLE
        ));
    }

    private static void completeSupportIsAccepted() {
        final SubLevelReconstructionPlotPlatformSupport.Capabilities capabilities =
                new SubLevelReconstructionPlotPlatformSupport.Capabilities(true, true);
        assert capabilities.complete();

        final SubLevelReconstructionPlatformPreflight.Result result =
                SubLevelReconstructionPlatformPreflight.validateCapabilities(capabilities, true);

        assert result.accepted();
        assert result.failures().isEmpty();
    }
}
