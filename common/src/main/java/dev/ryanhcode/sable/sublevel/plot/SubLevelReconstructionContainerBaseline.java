package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelOccupancySavedData;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only evidence of the target container immediately before transactional reconstruction.
 *
 * <p>The baseline is intentionally separate from rollback. It captures state that a later rollback
 * must reproduce exactly and can verify that state after cleanup. Capturing or verifying this
 * object does not allocate, publish, remove, load, save, or otherwise mutate a SubLevel.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionContainerBaseline {
    public enum Failure {
        NOT_SERVER_THREAD,
        CONTAINER_UNAVAILABLE,
        PRECONDITION_DRIFT,
        CONTAINER_INCONSISTENT,
        OCCUPANCY_CHANGED,
        LOADED_SUBLEVELS_CHANGED,
        UUID_INDEX_CHANGED,
        TARGET_SLOT_CHANGED,
        TARGET_UUID_CHANGED,
        OCCUPANCY_DIRTY_STATE_CHANGED
    }

    public record Capture(Set<Failure> failures, Optional<SubLevelReconstructionContainerBaseline> baseline) {
        public Capture {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(baseline, "baseline");
            failures = immutableFailures(failures);
            if (failures.isEmpty() != baseline.isPresent()) {
                throw new IllegalArgumentException(
                        "Successful capture requires a baseline and failed capture requires evidence"
                );
            }
        }

        public boolean accepted() {
            return this.failures.isEmpty() && this.baseline.isPresent();
        }
    }

    public record Verification(Set<Failure> failures) {
        public Verification {
            Objects.requireNonNull(failures, "failures");
            failures = immutableFailures(failures);
        }

        public boolean exact() {
            return this.failures.isEmpty();
        }
    }

    private final UUID targetUuid;
    private final SubLevelReconstructionPreflight.TargetSlot targetSlot;
    private final BitSet occupancy;
    private final List<SubLevel> loadedSubLevels;
    private final Map<UUID, SubLevel> uuidIndex;
    private final SubLevel targetSlotOccupant;
    private final SubLevel targetUuidOccupant;
    private final boolean occupancySavedDataDirty;

    private SubLevelReconstructionContainerBaseline(
            final UUID targetUuid,
            final SubLevelReconstructionPreflight.TargetSlot targetSlot,
            final BitSet occupancy,
            final List<SubLevel> loadedSubLevels,
            final Map<UUID, SubLevel> uuidIndex,
            final SubLevel targetSlotOccupant,
            final SubLevel targetUuidOccupant,
            final boolean occupancySavedDataDirty
    ) {
        this.targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
        this.targetSlot = Objects.requireNonNull(targetSlot, "targetSlot");
        this.occupancy = (BitSet) Objects.requireNonNull(occupancy, "occupancy").clone();
        this.loadedSubLevels = List.copyOf(Objects.requireNonNull(loadedSubLevels, "loadedSubLevels"));
        this.uuidIndex = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(uuidIndex, "uuidIndex")
        ));
        this.targetSlotOccupant = targetSlotOccupant;
        this.targetUuidOccupant = targetUuidOccupant;
        this.occupancySavedDataDirty = occupancySavedDataDirty;
    }

    /**
     * Captures a server-thread-only baseline after a reconstruction plan has been prepared.
     *
     * <p>If the target slot or target UUID became occupied since plan preparation, capture fails
     * closed with {@link Failure#PRECONDITION_DRIFT} rather than blessing a stale preflight.</p>
     */
    public static Capture capture(final ServerLevel level, final SubLevelReconstructionPlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");

        if (!level.getServer().isSameThread()) {
            return rejected(Failure.NOT_SERVER_THREAD);
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return rejected(Failure.CONTAINER_UNAVAILABLE);
        }

        final SubLevelReconstructionPreflight.TargetSlot targetSlot = plan.targetSlot();
        final SubLevel targetSlotOccupant = container.getSubLevel(targetSlot.plotX(), targetSlot.plotZ());
        final SubLevel targetUuidOccupant = container.getSubLevel(plan.uuid());
        if (targetSlotOccupant != null || targetUuidOccupant != null) {
            return rejected(Failure.PRECONDITION_DRIFT);
        }

        final List<SubLevel> loaded = snapshotLoaded(container);
        final Map<UUID, SubLevel> uuidIndex = new LinkedHashMap<>();
        for (final SubLevel subLevel : loaded) {
            final UUID uuid = subLevel.getUniqueId();
            final SubLevel previous = uuidIndex.put(uuid, subLevel);
            if (previous != null || container.getSubLevel(uuid) != subLevel) {
                return rejected(Failure.CONTAINER_INCONSISTENT);
            }
        }

        final boolean occupancyDirty = SubLevelOccupancySavedData.getOrLoad(level).isDirty();
        return new Capture(
                Set.of(),
                Optional.of(new SubLevelReconstructionContainerBaseline(
                        plan.uuid(),
                        targetSlot,
                        container.getOccupancy(),
                        loaded,
                        uuidIndex,
                        targetSlotOccupant,
                        targetUuidOccupant,
                        occupancyDirty
                ))
        );
    }

    /**
     * Verifies that the target container is observationally equal to the captured pre-state.
     */
    public Verification verify(final ServerLevel level) {
        Objects.requireNonNull(level, "level");
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);

        if (!level.getServer().isSameThread()) {
            failures.add(Failure.NOT_SERVER_THREAD);
            return new Verification(failures);
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            failures.add(Failure.CONTAINER_UNAVAILABLE);
            return new Verification(failures);
        }

        if (!this.occupancy.equals(container.getOccupancy())) {
            failures.add(Failure.OCCUPANCY_CHANGED);
        }

        final List<SubLevel> currentLoaded = snapshotLoaded(container);
        if (!sameIdentityOrder(this.loadedSubLevels, currentLoaded)) {
            failures.add(Failure.LOADED_SUBLEVELS_CHANGED);
        }

        final Map<UUID, SubLevel> currentIndex = new LinkedHashMap<>();
        boolean currentIndexConsistent = true;
        for (final SubLevel subLevel : currentLoaded) {
            final UUID uuid = subLevel.getUniqueId();
            if (currentIndex.put(uuid, subLevel) != null || container.getSubLevel(uuid) != subLevel) {
                currentIndexConsistent = false;
            }
        }
        if (!currentIndexConsistent || !sameIdentityMap(this.uuidIndex, currentIndex)) {
            failures.add(Failure.UUID_INDEX_CHANGED);
        }

        if (container.getSubLevel(this.targetSlot.plotX(), this.targetSlot.plotZ()) != this.targetSlotOccupant) {
            failures.add(Failure.TARGET_SLOT_CHANGED);
        }
        if (container.getSubLevel(this.targetUuid) != this.targetUuidOccupant) {
            failures.add(Failure.TARGET_UUID_CHANGED);
        }
        if (SubLevelOccupancySavedData.getOrLoad(level).isDirty() != this.occupancySavedDataDirty) {
            failures.add(Failure.OCCUPANCY_DIRTY_STATE_CHANGED);
        }

        return new Verification(failures);
    }

    public boolean occupancySavedDataDirty() {
        return this.occupancySavedDataDirty;
    }

    public BitSet occupancy() {
        return (BitSet) this.occupancy.clone();
    }

    static boolean sameIdentityOrder(final List<?> expected, final List<?> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (expected.get(index) != actual.get(index)) {
                return false;
            }
        }
        return true;
    }

    static boolean sameIdentityMap(final Map<UUID, ?> expected, final Map<UUID, ?> actual) {
        if (!expected.keySet().equals(actual.keySet())) {
            return false;
        }
        for (final Map.Entry<UUID, ?> entry : expected.entrySet()) {
            if (actual.get(entry.getKey()) != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static List<SubLevel> snapshotLoaded(final ServerSubLevelContainer container) {
        final List<SubLevel> loaded = new ArrayList<>(container.getLoadedCount());
        loaded.addAll(container.getAllSubLevels());
        return List.copyOf(loaded);
    }

    private static Capture rejected(final Failure failure) {
        return new Capture(Set.of(failure), Optional.empty());
    }

    private static Set<Failure> immutableFailures(final Set<Failure> failures) {
        if (failures.isEmpty()) {
            return Set.of();
        }
        final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
        copy.addAll(failures);
        return Collections.unmodifiableSet(copy);
    }
}
