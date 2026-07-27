package dev.ryanhcode.sable.sublevel.transfer;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import org.joml.Vector2i;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Explicit, guarded live snapshot capture for one loaded server sub-level.
 *
 * <p>This adapter is not registered to a lifecycle hook. It performs no plot
 * allocation, target load, source removal, entity mutation, or journal update.</p>
 */
public final class CrossLevelTransferLiveSnapshotCaptureAdapter {
    private CrossLevelTransferLiveSnapshotCaptureAdapter() {
    }

    /**
     * Captures one dependency-free source only after its immutable transaction
     * identity and current loaded location are proven.
     */
    public static CrossLevelTransferSnapshotCaptureResult capture(
            final ServerSubLevel source,
            final CrossLevelTransferTransactionState expected
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(expected, "expected");

        if (expected.phase() != CrossLevelTransferPhase.PREPARING) {
            return failed(CrossLevelTransferSnapshotCaptureStatus.TRANSACTION_NOT_PREPARING);
        }
        if (source.isRemoved()) {
            return failed(CrossLevelTransferSnapshotCaptureStatus.SOURCE_REMOVED);
        }
        if (!expected.subLevelId().equals(source.getUniqueId())) {
            return failed(CrossLevelTransferSnapshotCaptureStatus.SOURCE_ID_MISMATCH);
        }
        if (!expected.sourceDimension().equals(source.getLevel().dimension().location().toString())) {
            return failed(CrossLevelTransferSnapshotCaptureStatus.SOURCE_DIMENSION_MISMATCH);
        }

        final ServerSubLevelContainer sourceContainer = SubLevelContainer.getContainer(source.getLevel());
        if (sourceContainer == null || sourceContainer.getSubLevel(source.getUniqueId()) != source) {
            return failed(CrossLevelTransferSnapshotCaptureStatus.SOURCE_CONTAINER_UNAVAILABLE);
        }
        if (!matchesExpectedLocalSlot(source, sourceContainer, expected)) {
            return failed(CrossLevelTransferSnapshotCaptureStatus.SOURCE_SLOT_MISMATCH);
        }

        final Collection<ServerSubLevel> dependencyChain;
        try {
            dependencyChain = SubLevelHelper.getLoadingDependencyChain(source);
        } catch (final RuntimeException exception) {
            return failed(CrossLevelTransferSnapshotCaptureStatus.SOURCE_INSPECTION_FAILED);
        }
        if (!containsOnlySource(dependencyChain, source)) {
            return failed(CrossLevelTransferSnapshotCaptureStatus.DEPENDENCIES_PRESENT);
        }

        final SubLevelData data;
        try {
            data = SubLevelSerializer.toData(source, List.of(source.getUniqueId()));
        } catch (final RuntimeException exception) {
            return failed(CrossLevelTransferSnapshotCaptureStatus.SERIALIZATION_FAILED);
        }

        return CrossLevelTransferSubLevelDataEnvelopeAdapter.create(data, expected)
                .map(CrossLevelTransferSnapshotCaptureResult::captured)
                .orElseGet(() -> failed(CrossLevelTransferSnapshotCaptureStatus.SERIALIZED_DATA_INVALID));
    }

    static boolean matchesExpectedLocalSlot(
            final int globalPlotX,
            final int globalPlotZ,
            final int originX,
            final int originZ,
            final int expectedLocalPlotX,
            final int expectedLocalPlotZ
    ) {
        return globalPlotX - originX == expectedLocalPlotX &&
                globalPlotZ - originZ == expectedLocalPlotZ;
    }

    static boolean containsOnlyIdentity(
            final Collection<?> values,
            final Object expected
    ) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(expected, "expected");
        return values.size() == 1 && values.iterator().next() == expected;
    }

    private static boolean matchesExpectedLocalSlot(
            final ServerSubLevel source,
            final ServerSubLevelContainer sourceContainer,
            final CrossLevelTransferTransactionState expected
    ) {
        final Vector2i origin = sourceContainer.getOrigin();
        return matchesExpectedLocalSlot(
                source.getPlot().plotPos.x,
                source.getPlot().plotPos.z,
                origin.x,
                origin.y,
                expected.localPlotX(),
                expected.localPlotZ()
        );
    }

    private static boolean containsOnlySource(
            final Collection<ServerSubLevel> dependencyChain,
            final ServerSubLevel source
    ) {
        return containsOnlyIdentity(dependencyChain, source);
    }

    private static CrossLevelTransferSnapshotCaptureResult failed(
            final CrossLevelTransferSnapshotCaptureStatus status
    ) {
        return CrossLevelTransferSnapshotCaptureResult.failed(status);
    }
}
