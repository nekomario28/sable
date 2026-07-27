package dev.ryanhcode.sable.sublevel.transfer;

import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;

import java.util.Objects;

/**
 * One precise authoritative location of a sub-level UUID.
 */
public sealed interface CrossLevelTransferSubLevelLocation
        permits CrossLevelTransferSubLevelLocation.Loaded, CrossLevelTransferSubLevelLocation.Stored {
    String dimension();

    /**
     * Identifies a loaded sub-level by dimension and local plot slot.
     */
    record Loaded(String dimension, int localPlotX, int localPlotZ)
            implements CrossLevelTransferSubLevelLocation {
        public Loaded {
            dimension = requireDimension(dimension);
        }
    }

    /**
     * Identifies a stored sub-level by dimension and exact storage pointer.
     */
    record Stored(String dimension, GlobalSavedSubLevelPointer pointer)
            implements CrossLevelTransferSubLevelLocation {
        public Stored {
            dimension = requireDimension(dimension);
            Objects.requireNonNull(pointer, "pointer");
        }
    }

    private static String requireDimension(final String dimension) {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
        return dimension;
    }
}
