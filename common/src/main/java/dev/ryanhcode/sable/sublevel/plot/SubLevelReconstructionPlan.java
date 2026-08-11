package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable reconstruction input captured after mutation-free preflight succeeds.
 *
 * <p>A plan owns defensive copies of every mutable part of {@link SubLevelData}. This prevents
 * callers, storage recovery, or later orchestration code from changing reconstruction input after
 * the target slot has been validated but before a future transactional materialization begins.</p>
 *
 * <p>Creating a plan does not allocate a {@code SubLevel}, consume a runtime ID, mutate plot
 * occupancy, notify observers, touch physics, load chunks, or mark SavedData dirty.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionPlan {
    private final UUID uuid;
    private final SubLevelReconstructionPreflight.TargetSlot targetSlot;
    private final Pose3d pose;
    private final BoundingBox3d bounds;
    private final List<UUID> dependencies;
    private final CompoundTag fullTag;

    private SubLevelReconstructionPlan(
            final UUID uuid,
            final SubLevelReconstructionPreflight.TargetSlot targetSlot,
            final Pose3d pose,
            final BoundingBox3d bounds,
            final List<UUID> dependencies,
            final CompoundTag fullTag
    ) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.targetSlot = Objects.requireNonNull(targetSlot, "targetSlot");
        this.pose = new Pose3d(Objects.requireNonNull(pose, "pose"));
        this.bounds = new BoundingBox3d(Objects.requireNonNull(bounds, "bounds"));
        this.dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        this.fullTag = Objects.requireNonNull(fullTag, "fullTag").copy();
    }

    /**
     * Runs the existing mutation-free preflight and freezes accepted input into a reconstruction plan.
     */
    public static Preparation prepare(final ServerLevel level, final SubLevelData data) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(data, "data");

        final SubLevelReconstructionPreflight.Result preflight =
                SubLevelReconstructionPreflight.validate(level, data);
        if (!preflight.accepted()) {
            return Preparation.rejected(preflight.failures());
        }

        final SubLevelReconstructionPreflight.TargetSlot targetSlot =
                preflight.targetSlot().orElseThrow();
        return Preparation.accepted(freezeAccepted(data, targetSlot));
    }

    /** Package-private pure seam used by executable tests after preflight-equivalent input is established. */
    static SubLevelReconstructionPlan freezeAccepted(
            final SubLevelData data,
            final SubLevelReconstructionPreflight.TargetSlot targetSlot
    ) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(targetSlot, "targetSlot");
        return new SubLevelReconstructionPlan(
                data.uuid(),
                targetSlot,
                data.pose(),
                data.bounds(),
                data.dependencies(),
                data.fullTag()
        );
    }

    public UUID uuid() {
        return this.uuid;
    }

    public SubLevelReconstructionPreflight.TargetSlot targetSlot() {
        return this.targetSlot;
    }

    /** Returns a defensive copy. */
    public Pose3d pose() {
        return new Pose3d(this.pose);
    }

    /** Returns a defensive copy. */
    public BoundingBox3d bounds() {
        return new BoundingBox3d(this.bounds);
    }

    public List<UUID> dependencies() {
        return this.dependencies;
    }

    /** Returns a deep NBT copy. */
    public CompoundTag fullTag() {
        return this.fullTag.copy();
    }

    /**
     * Produces a fresh mutable {@link SubLevelData} view for a future materialization attempt.
     * Mutating the returned object cannot alter this plan.
     */
    public SubLevelData toData() {
        return new SubLevelData(
                this.uuid,
                new BoundingBox3d(this.bounds),
                new Pose3d(this.pose),
                this.dependencies,
                this.fullTag.copy()
        );
    }

    public record Preparation(
            Set<SubLevelReconstructionPreflight.Failure> failures,
            Optional<SubLevelReconstructionPlan> plan
    ) {
        public Preparation {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(plan, "plan");
            failures = Set.copyOf(failures);
            if (failures.isEmpty() != plan.isPresent()) {
                throw new IllegalArgumentException(
                        "Accepted preparation must contain a plan and rejected preparation must contain failures"
                );
            }
        }

        public boolean accepted() {
            return this.failures.isEmpty() && this.plan.isPresent();
        }

        private static Preparation accepted(final SubLevelReconstructionPlan plan) {
            return new Preparation(Set.of(), Optional.of(plan));
        }

        private static Preparation rejected(final Set<SubLevelReconstructionPreflight.Failure> failures) {
            if (failures.isEmpty()) {
                throw new IllegalArgumentException("Rejected preparation requires at least one failure");
            }
            return new Preparation(failures, Optional.empty());
        }
    }
}
