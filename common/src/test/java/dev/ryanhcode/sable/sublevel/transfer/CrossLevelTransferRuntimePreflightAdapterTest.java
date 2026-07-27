package dev.ryanhcode.sable.sublevel.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferRuntimePreflightAdapterTest {
    @Test
    void sectionLayoutRequiresEqualMinimumAndMaximumHeights() {
        assertTrue(CrossLevelTransferRuntimePreflightAdapter.hasCompatibleSectionLayout(
                -64,
                320,
                -64,
                320
        ));
        assertFalse(CrossLevelTransferRuntimePreflightAdapter.hasCompatibleSectionLayout(
                0,
                320,
                -64,
                320
        ));
        assertFalse(CrossLevelTransferRuntimePreflightAdapter.hasCompatibleSectionLayout(
                -64,
                256,
                -64,
                320
        ));
    }

    @Test
    void localPlotBoundsUseTheTargetGridSideLength() {
        assertTrue(CrossLevelTransferRuntimePreflightAdapter.isLocalPlotInBounds(0, 0, 7));
        assertTrue(CrossLevelTransferRuntimePreflightAdapter.isLocalPlotInBounds(127, 127, 7));
        assertFalse(CrossLevelTransferRuntimePreflightAdapter.isLocalPlotInBounds(-1, 0, 7));
        assertFalse(CrossLevelTransferRuntimePreflightAdapter.isLocalPlotInBounds(0, -1, 7));
        assertFalse(CrossLevelTransferRuntimePreflightAdapter.isLocalPlotInBounds(128, 0, 7));
        assertFalse(CrossLevelTransferRuntimePreflightAdapter.isLocalPlotInBounds(0, 128, 7));
    }

    @Test
    void invalidLogSideLengthsFailClosed() {
        assertFalse(CrossLevelTransferRuntimePreflightAdapter.isLocalPlotInBounds(0, 0, -1));
        assertFalse(CrossLevelTransferRuntimePreflightAdapter.isLocalPlotInBounds(0, 0, 31));
        assertFalse(CrossLevelTransferRuntimePreflightAdapter.isLocalPlotInBounds(0, 0, Integer.MAX_VALUE));
    }

    @Test
    void omittedAuthoritativeFactsRemainBlocking() {
        final CrossLevelTransferPreflightFacts partial = CrossLevelTransferPreflightFacts.builder(
                        "minecraft:overworld",
                        "starlance:space"
                )
                .sourceRemoved(false)
                .sourceContainerAvailable(true)
                .targetContainerAvailable(true)
                .targetPhysicsAvailable(true)
                .activeKinematicContraption(false)
                .compatibleSectionLayout(true)
                .targetSlotOccupied(false)
                .entitiesPresent(false)
                .build();

        final CrossLevelTransferValidation validation = CrossLevelTransferPreflightValidator.validate(partial);

        assertFalse(validation.isAccepted());
        assertTrue(validation.failures().contains(CrossLevelTransferFailure.DUPLICATE_TARGET_UUID));
        assertTrue(validation.failures().contains(CrossLevelTransferFailure.DEPENDENCIES_PRESENT));
        assertTrue(validation.failures().contains(CrossLevelTransferFailure.TRANSACTION_CONFLICT));
        assertTrue(validation.failures().contains(CrossLevelTransferFailure.SNAPSHOT_UNAVAILABLE));
    }

    @Test
    void observeRejectsNullRuntimeObjectsBeforeInspection() {
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferRuntimePreflightAdapter.observe(null, null)
        );
    }
}
