package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Rejects deterministic reconstruction failures before Sable allocates a target SubLevel.
 *
 * <p>This method is mutation-free. It intentionally does not duplicate plot codecs, platform
 * attachment readers or block-entity loaders; those still require transactional rollback.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionPreflight {
    private SubLevelReconstructionPreflight() {
    }

    public enum Failure {
        NOT_SERVER_THREAD,
        CONTAINER_UNAVAILABLE,
        MISSING_REQUIRED_DATA,
        UUID_MISMATCH,
        INVALID_DEPENDENCIES,
        INVALID_POSE,
        INVALID_BOUNDS,
        INVALID_TARGET_SLOT,
        TARGET_SLOT_OCCUPIED,
        TARGET_UUID_OCCUPIED,
        PLOT_SIZE_MISMATCH,
        UNSUPPORTED_PLOT_VERSION,
        EMPTY_PLOT,
        INVALID_CHUNK_KEY,
        CHUNK_OUTSIDE_PLOT,
        INVALID_SECTION_INDEX,
        INVALID_VELOCITY
    }

    public record TargetSlot(int plotX, int plotZ) {
    }

    public record Result(Set<Failure> failures, Optional<TargetSlot> targetSlot) {
        public Result {
            Objects.requireNonNull(failures, "failures");
            targetSlot = Objects.requireNonNull(targetSlot, "targetSlot");
            final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
            copy.addAll(failures);
            failures = Collections.unmodifiableSet(copy);
        }

        public boolean accepted() {
            return this.failures.isEmpty() && this.targetSlot.isPresent();
        }
    }

    /** Validates current target-container state and serialized data without mutation. */
    public static Result validate(final ServerLevel level, final SubLevelData data) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(data, "data");
        if (!level.getServer().isSameThread()) {
            return rejected(Failure.NOT_SERVER_THREAD);
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return rejected(Failure.CONTAINER_UNAVAILABLE);
        }

        final Result serialized = validateSerialized(
                data,
                container.getLogSideLength(),
                container.getLogPlotSize(),
                level.getSectionsCount()
        );
        final EnumSet<Failure> failures = copyFailures(serialized.failures());
        serialized.targetSlot().ifPresent(target -> {
            if (container.getSubLevel(target.plotX(), target.plotZ()) != null) {
                failures.add(Failure.TARGET_SLOT_OCCUPIED);
            }
            if (container.getSubLevel(data.uuid()) != null) {
                failures.add(Failure.TARGET_UUID_OCCUPIED);
            }
        });
        return new Result(failures, serialized.targetSlot());
    }

    /** Package-private pure seam for executable tests. */
    static Result validateSerialized(
            final SubLevelData data,
            final int logSideLength,
            final int logPlotSize,
            final int sectionCount
    ) {
        Objects.requireNonNull(data, "data");
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
        final CompoundTag tag = data.fullTag();
        if (tag == null || data.uuid() == null || data.pose() == null ||
                data.bounds() == null || data.dependencies() == null ||
                !tag.hasUUID("uuid") ||
                !tag.contains("plot", Tag.TAG_COMPOUND) ||
                !tag.contains("pose", Tag.TAG_COMPOUND) ||
                !tag.contains("world_bounds", Tag.TAG_COMPOUND)) {
            failures.add(Failure.MISSING_REQUIRED_DATA);
            return new Result(failures, Optional.empty());
        }

        try {
            if (!data.uuid().equals(tag.getUUID("uuid"))) {
                failures.add(Failure.UUID_MISMATCH);
            }
        } catch (final RuntimeException exception) {
            failures.add(Failure.UUID_MISMATCH);
        }
        validateDependencies(data, failures);

        final CompoundTag poseTag = tag.getCompound("pose");
        if (!validVectorTag(poseTag, "position") ||
                !validQuaternionTag(poseTag, "orientation") ||
                !validVectorTag(poseTag, "rotation_point")) {
            failures.add(Failure.INVALID_POSE);
        } else {
            final Pose3d tagPose = SableNBTUtils.readPose3d(poseTag);
            if (!finitePose(data.pose()) || !finitePose(tagPose)) {
                failures.add(Failure.INVALID_POSE);
            }
        }

        final CompoundTag boundsTag = tag.getCompound("world_bounds");
        if (!validBoundsTag(boundsTag)) {
            failures.add(Failure.INVALID_BOUNDS);
        } else {
            final BoundingBox3dc tagBounds = SableNBTUtils.readBoundingBox(boundsTag);
            if (!validBounds(data.bounds()) || !validBounds(tagBounds)) {
                failures.add(Failure.INVALID_BOUNDS);
            }
        }
        validateVelocity(tag, "linear_velocity", failures);
        validateVelocity(tag, "angular_velocity", failures);

        final CompoundTag plot = tag.getCompound("plot");
        if (!plot.contains("plot_x", Tag.TAG_INT) ||
                !plot.contains("plot_z", Tag.TAG_INT) ||
                !plot.contains("log_size", Tag.TAG_INT) ||
                !plot.contains("chunks", Tag.TAG_COMPOUND)) {
            failures.add(Failure.MISSING_REQUIRED_DATA);
            return new Result(failures, Optional.empty());
        }

        final int plotX = plot.getInt("plot_x");
        final int plotZ = plot.getInt("plot_z");
        final int sideLength = safePowerOfTwo(logSideLength);
        final Optional<TargetSlot> target;
        if (sideLength < 0 || plotX < 0 || plotX >= sideLength ||
                plotZ < 0 || plotZ >= sideLength) {
            failures.add(Failure.INVALID_TARGET_SLOT);
            target = Optional.empty();
        } else {
            target = Optional.of(new TargetSlot(plotX, plotZ));
        }

        if (plot.getInt("log_size") != logPlotSize) {
            failures.add(Failure.PLOT_SIZE_MISMATCH);
        }
        final int dataVersion = plot.contains("data_version", Tag.TAG_INT)
                ? plot.getInt("data_version") : 0;
        if (dataVersion < 0 || dataVersion > ServerLevelPlot.DATA_VERSION) {
            failures.add(Failure.UNSUPPORTED_PLOT_VERSION);
        }

        validatePlotShape(plot.getCompound("chunks"), logPlotSize, sectionCount, failures);
        return new Result(failures, target);
    }

    private static void validateDependencies(
            final SubLevelData data,
            final EnumSet<Failure> failures
    ) {
        final Set<UUID> unique = new HashSet<>();
        for (final UUID dependency : data.dependencies()) {
            if (dependency == null || dependency.equals(data.uuid()) || !unique.add(dependency)) {
                failures.add(Failure.INVALID_DEPENDENCIES);
            }
        }
    }

    private static void validatePlotShape(
            final CompoundTag chunks,
            final int logPlotSize,
            final int sectionCount,
            final EnumSet<Failure> failures
    ) {
        if (chunks.isEmpty()) {
            failures.add(Failure.EMPTY_PLOT);
            return;
        }
        final int plotSide = safePowerOfTwo(logPlotSize);
        if (plotSide < 0 || sectionCount <= 0) {
            failures.add(Failure.PLOT_SIZE_MISMATCH);
            return;
        }

        for (final String chunkKey : chunks.getAllKeys()) {
            final long packed;
            try {
                packed = Long.parseLong(chunkKey);
            } catch (final NumberFormatException exception) {
                failures.add(Failure.INVALID_CHUNK_KEY);
                continue;
            }
            final int chunkX = ChunkPos.getX(packed);
            final int chunkZ = ChunkPos.getZ(packed);
            if (chunkX < 0 || chunkX >= plotSide || chunkZ < 0 || chunkZ >= plotSide) {
                failures.add(Failure.CHUNK_OUTSIDE_PLOT);
            }
            if (!chunks.contains(chunkKey, Tag.TAG_COMPOUND)) {
                failures.add(Failure.INVALID_CHUNK_KEY);
                continue;
            }
            final CompoundTag chunk = chunks.getCompound(chunkKey);
            if (!chunk.contains("sections", Tag.TAG_COMPOUND)) {
                continue;
            }
            for (final String sectionKey : chunk.getCompound("sections").getAllKeys()) {
                try {
                    final int index = Integer.parseInt(sectionKey);
                    if (index < 0 || index >= sectionCount) {
                        failures.add(Failure.INVALID_SECTION_INDEX);
                    }
                } catch (final NumberFormatException exception) {
                    failures.add(Failure.INVALID_SECTION_INDEX);
                }
            }
        }
    }

    private static void validateVelocity(
            final CompoundTag tag,
            final String key,
            final EnumSet<Failure> failures
    ) {
        if (!tag.contains(key)) {
            return;
        }
        if (!validVectorTag(tag, key) ||
                !finiteVector(SableNBTUtils.readVector3d(tag.getCompound(key)))) {
            failures.add(Failure.INVALID_VELOCITY);
        }
    }

    private static boolean validVectorTag(final CompoundTag owner, final String key) {
        if (!owner.contains(key, Tag.TAG_COMPOUND)) {
            return false;
        }
        final CompoundTag vector = owner.getCompound(key);
        return vector.contains("x", Tag.TAG_DOUBLE) &&
                vector.contains("y", Tag.TAG_DOUBLE) &&
                vector.contains("z", Tag.TAG_DOUBLE);
    }

    private static boolean validQuaternionTag(final CompoundTag owner, final String key) {
        if (!owner.contains(key, Tag.TAG_COMPOUND)) {
            return false;
        }
        final CompoundTag quaternion = owner.getCompound(key);
        return quaternion.contains("x", Tag.TAG_DOUBLE) &&
                quaternion.contains("y", Tag.TAG_DOUBLE) &&
                quaternion.contains("z", Tag.TAG_DOUBLE) &&
                quaternion.contains("w", Tag.TAG_DOUBLE);
    }

    private static boolean validBoundsTag(final CompoundTag bounds) {
        return bounds.contains("minX", Tag.TAG_DOUBLE) &&
                bounds.contains("minY", Tag.TAG_DOUBLE) &&
                bounds.contains("minZ", Tag.TAG_DOUBLE) &&
                bounds.contains("maxX", Tag.TAG_DOUBLE) &&
                bounds.contains("maxY", Tag.TAG_DOUBLE) &&
                bounds.contains("maxZ", Tag.TAG_DOUBLE);
    }

    private static boolean finitePose(final Pose3d pose) {
        return finiteVector(pose.position()) &&
                finiteVector(pose.rotationPoint()) &&
                finiteQuaternion(pose.orientation());
    }

    private static boolean finiteVector(final Vector3dc vector) {
        return Double.isFinite(vector.x()) &&
                Double.isFinite(vector.y()) &&
                Double.isFinite(vector.z());
    }

    private static boolean finiteQuaternion(final Quaterniondc quaternion) {
        return Double.isFinite(quaternion.x()) &&
                Double.isFinite(quaternion.y()) &&
                Double.isFinite(quaternion.z()) &&
                Double.isFinite(quaternion.w()) &&
                quaternion.lengthSquared() > 1.0E-12;
    }

    private static boolean validBounds(final BoundingBox3dc bounds) {
        return Double.isFinite(bounds.minX()) &&
                Double.isFinite(bounds.minY()) &&
                Double.isFinite(bounds.minZ()) &&
                Double.isFinite(bounds.maxX()) &&
                Double.isFinite(bounds.maxY()) &&
                Double.isFinite(bounds.maxZ()) &&
                bounds.maxX() > bounds.minX() &&
                bounds.maxY() > bounds.minY() &&
                bounds.maxZ() > bounds.minZ();
    }

    private static int safePowerOfTwo(final int exponent) {
        return exponent >= 0 && exponent < 31 ? 1 << exponent : -1;
    }

    private static EnumSet<Failure> copyFailures(final Set<Failure> failures) {
        final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
        copy.addAll(failures);
        return copy;
    }

    private static Result rejected(final Failure failure) {
        return new Result(EnumSet.of(failure), Optional.empty());
    }
}
