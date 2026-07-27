package dev.ryanhcode.sable.sublevel.transfer;

import java.util.Objects;

/**
 * Identifies one local Sable plot slot in a target dimension.
 *
 * @param dimension target dimension identifier
 * @param localPlotX local plot X coordinate
 * @param localPlotZ local plot Z coordinate
 */
public record CrossLevelTransferTargetSlot(String dimension, int localPlotX, int localPlotZ) {
    public CrossLevelTransferTargetSlot {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
    }

    public static CrossLevelTransferTargetSlot from(final CrossLevelTransferTransactionState state) {
        Objects.requireNonNull(state, "state");
        return new CrossLevelTransferTargetSlot(
                state.targetDimension(),
                state.localPlotX(),
                state.localPlotZ()
        );
    }
}
