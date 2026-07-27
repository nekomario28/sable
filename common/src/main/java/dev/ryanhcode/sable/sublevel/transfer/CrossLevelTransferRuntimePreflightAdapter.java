package dev.ryanhcode.sable.sublevel.transfer;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.joml.Vector2i;

import java.util.Objects;

/**
 * Read-only adapter that observes the Phase 01 runtime facts that can be proven
 * directly from a loaded source and target level.
 *
 * <p>This adapter deliberately leaves complete target UUID uniqueness, transaction
 * ownership, and verified snapshot availability in their fail-closed defaults.
 * Those facts require other authoritative subsystems and must be enriched in later
 * phases before validation can succeed.</p>
 */
public final class CrossLevelTransferRuntimePreflightAdapter {
    private CrossLevelTransferRuntimePreflightAdapter() {
    }

    /**
     * Observes loaded runtime state without mutating either level or sub-level.
     *
     * @param source loaded source sub-level
     * @param targetLevel loaded target server level
     * @return partial fail-closed preflight facts
     */
    public static CrossLevelTransferPreflightFacts observe(
            final ServerSubLevel source,
            final ServerLevel targetLevel
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetLevel, "targetLevel");

        final ServerLevel sourceLevel = source.getLevel();
        final ServerSubLevelContainer sourceContainer = SubLevelContainer.getContainer(sourceLevel);
        final ServerSubLevelContainer targetContainer = SubLevelContainer.getContainer(targetLevel);

        final boolean sourceContainerAvailable = sourceContainer != null &&
                sourceContainer.getSubLevel(source.getUniqueId()) == source;
        final boolean targetContainerAvailable = targetContainer != null;

        final CrossLevelTransferPreflightFacts.Builder builder = CrossLevelTransferPreflightFacts.builder(
                        sourceLevel.dimension().location().toString(),
                        targetLevel.dimension().location().toString()
                )
                .sourceRemoved(source.isRemoved())
                .sourceContainerAvailable(sourceContainerAvailable)
                .targetContainerAvailable(targetContainerAvailable)
                .targetPhysicsAvailable(hasPhysicsSystem(targetContainer))
                .dependenciesPresent(SubLevelHelper.getLoadingDependencyChain(source).size() > 1)
                .activeKinematicContraption(!source.getPlot().getContraptions().isEmpty())
                .compatibleSectionLayout(hasCompatibleSectionLayout(sourceLevel, targetLevel))
                .targetSlotOccupied(isTargetSlotOccupied(source, sourceContainer, targetContainer))
                .entitiesPresent(hasRelatedEntities(source, sourceLevel));

        // Intentionally not marked safe here:
        // - duplicateTargetUuid: unloaded target storage is not covered by the loaded UUID map
        // - transactionConflict: must come from the authoritative journal controller
        // - snapshotAvailable: must come from a verified immutable snapshot store
        return builder.build();
    }

    static boolean hasCompatibleSectionLayout(
            final int sourceMinBuildHeight,
            final int sourceMaxBuildHeight,
            final int targetMinBuildHeight,
            final int targetMaxBuildHeight
    ) {
        return sourceMinBuildHeight == targetMinBuildHeight &&
                sourceMaxBuildHeight == targetMaxBuildHeight;
    }

    static boolean isLocalPlotInBounds(final int localPlotX, final int localPlotZ, final int logSideLength) {
        if (logSideLength < 0 || logSideLength >= Integer.SIZE - 1) {
            return false;
        }
        final int sideLength = 1 << logSideLength;
        return localPlotX >= 0 && localPlotX < sideLength &&
                localPlotZ >= 0 && localPlotZ < sideLength;
    }

    private static boolean hasCompatibleSectionLayout(
            final ServerLevel sourceLevel,
            final ServerLevel targetLevel
    ) {
        return hasCompatibleSectionLayout(
                sourceLevel.getMinBuildHeight(),
                sourceLevel.getMaxBuildHeight(),
                targetLevel.getMinBuildHeight(),
                targetLevel.getMaxBuildHeight()
        );
    }

    private static boolean hasPhysicsSystem(final ServerSubLevelContainer targetContainer) {
        return targetContainer != null && targetContainer.physicsSystem() != null;
    }

    private static boolean isTargetSlotOccupied(
            final ServerSubLevel source,
            final ServerSubLevelContainer sourceContainer,
            final ServerSubLevelContainer targetContainer
    ) {
        if (sourceContainer == null || targetContainer == null) {
            return true;
        }

        final Vector2i sourceOrigin = sourceContainer.getOrigin();
        final int localPlotX = source.getPlot().plotPos.x - sourceOrigin.x;
        final int localPlotZ = source.getPlot().plotPos.z - sourceOrigin.y;

        if (!isLocalPlotInBounds(localPlotX, localPlotZ, targetContainer.getLogSideLength())) {
            return true;
        }

        return targetContainer.getOccupancy().get(targetContainer.getIndex(localPlotX, localPlotZ));
    }

    private static boolean hasRelatedEntities(
            final ServerSubLevel source,
            final ServerLevel sourceLevel
    ) {
        for (final Entity entity : sourceLevel.getAllEntities()) {
            if (Sable.HELPER.getContaining(entity) == source ||
                    Sable.HELPER.getTrackingOrVehicleSubLevel(entity) == source) {
                return true;
            }
        }
        return false;
    }
}
