package dev.ryanhcode.sable.api.sublevel;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.snapshot.SubLevelStorageSnapshotReader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative, read-only snapshot of every loaded or durably stored Sable sub-level known to a server.
 *
 * <p>The snapshot must be captured on the owning server thread. It never loads, unloads, allocates, saves,
 * moves, or removes a sub-level. A partial storage scan, a changing source, an occupancy mismatch, or an
 * ambiguous UUID produces a non-complete result.</p>
 */
public final class ServerSubLevelLocationSnapshot {
    private ServerSubLevelLocationSnapshot() {
    }

    public enum Status {
        COMPLETE,
        CONFLICTED,
        FAILED
    }

    public sealed interface Location permits Loaded, Stored {
        ResourceKey<Level> dimension();
    }

    /** A live sub-level, identified by dimension and local plot coordinates. */
    public record Loaded(
            ResourceKey<Level> dimension,
            int localPlotX,
            int localPlotZ,
            @Nullable GlobalSavedSubLevelPointer recoveryPointer
    ) implements Location {
        public Loaded {
            Objects.requireNonNull(dimension, "dimension");
            if (localPlotX < 0 || localPlotZ < 0) {
                throw new IllegalArgumentException("Local plot coordinates must be non-negative");
            }
        }
    }

    /** An unloaded sub-level, identified by dimension and exact Sable storage pointer. */
    public record Stored(
            ResourceKey<Level> dimension,
            GlobalSavedSubLevelPointer pointer
    ) implements Location {
        public Stored {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(pointer, "pointer");
        }
    }

    public record Conflict(UUID uuid, Location first, Location second) {
        public Conflict {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
        }
    }

    public record Snapshot(
            Status status,
            Map<UUID, Location> locations,
            List<Conflict> conflicts,
            @Nullable String failure
    ) {
        public Snapshot {
            Objects.requireNonNull(status, "status");
            locations = Map.copyOf(locations);
            conflicts = List.copyOf(conflicts);
            if (status == Status.COMPLETE && (!conflicts.isEmpty() || failure != null)) {
                throw new IllegalArgumentException("A complete snapshot cannot contain conflicts or failure information");
            }
            if (status == Status.CONFLICTED && conflicts.isEmpty()) {
                throw new IllegalArgumentException("A conflicted snapshot must contain conflict evidence");
            }
            if (status == Status.FAILED && (failure == null || failure.isBlank())) {
                throw new IllegalArgumentException("A failed snapshot must contain a failure reason");
            }
        }

        public boolean isComplete() {
            return this.status == Status.COMPLETE;
        }
    }

    /**
     * Captures one consistent server-wide UUID-to-location snapshot.
     *
     * @return a complete snapshot, conflict evidence, or an explicit failure; never a partial success
     */
    public static Snapshot capture(final MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            return failed("Sub-level location snapshots must be captured on the owning Minecraft server thread");
        }

        final Accumulator global = new Accumulator();
        for (final ServerLevel level : server.getAllLevels()) {
            final Snapshot levelSnapshot = captureLevel(level);
            if (levelSnapshot.status() == Status.FAILED) {
                return levelSnapshot;
            }
            if (levelSnapshot.status() == Status.CONFLICTED) {
                global.conflicts.addAll(levelSnapshot.conflicts());
                continue;
            }
            for (final Map.Entry<UUID, Location> entry : levelSnapshot.locations().entrySet()) {
                global.register(entry.getKey(), entry.getValue());
            }
        }

        return global.finish();
    }

    private static Snapshot captureLevel(final ServerLevel level) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return failed("Sable sub-level container is unavailable for dimension " + level.dimension().location());
        }

        final List<LoadedFingerprint> loadedBefore = captureLoaded(container);
        final BitSet occupancyBefore = (BitSet) container.getOccupancy().clone();
        if (loadedBefore.stream().anyMatch(LoadedFingerprint::removed)) {
            return failed("A removed Sable sub-level is still registered in dimension " + level.dimension().location());
        }

        final List<SubLevelStorageSnapshotReader.StoredSubLevel> stored;
        try {
            stored = SubLevelStorageSnapshotReader.capture(
                    container.getHoldingChunkMap().getStorage().getFolder()
            );
        } catch (final IOException | RuntimeException exception) {
            return failed("Unable to capture complete Sable storage for dimension " +
                    level.dimension().location() + ": " + exception.getMessage());
        }

        final List<LoadedFingerprint> loadedAfter = captureLoaded(container);
        final BitSet occupancyAfter = (BitSet) container.getOccupancy().clone();
        if (!loadedBefore.equals(loadedAfter) || !occupancyBefore.equals(occupancyAfter)) {
            return failed("Sable loaded or occupancy state changed during snapshot capture for dimension " +
                    level.dimension().location());
        }

        final Accumulator local = new Accumulator();
        final ResourceKey<Level> dimension = level.dimension();
        final Map<UUID, Integer> loadedOccurrences = new HashMap<>();
        for (final LoadedFingerprint loaded : loadedBefore) {
            final int occurrences = loadedOccurrences.merge(loaded.uuid(), 1, Integer::sum);
            if (occurrences > 1) {
                local.conflicts.add(new Conflict(
                        loaded.uuid(),
                        local.locations.get(loaded.uuid()),
                        loaded.location(dimension)
                ));
                continue;
            }
            local.register(loaded.uuid(), loaded.location(dimension));
        }
        for (final SubLevelStorageSnapshotReader.StoredSubLevel entry : stored) {
            local.register(entry.uuid(), new Stored(dimension, entry.pointer()));
        }

        final Snapshot resolved = local.finish();
        if (resolved.status() != Status.COMPLETE) {
            return resolved;
        }
        if (resolved.locations().size() != occupancyBefore.cardinality()) {
            return failed("Sable occupancy does not match the complete loaded-and-stored UUID set in dimension " +
                    dimension.location() + " (occupancy=" + occupancyBefore.cardinality() +
                    ", discovered=" + resolved.locations().size() + ")");
        }
        return resolved;
    }

    private static List<LoadedFingerprint> captureLoaded(final ServerSubLevelContainer container) {
        final Vector2i origin = container.getOrigin();
        final List<LoadedFingerprint> loaded = new ArrayList<>();
        for (final ServerSubLevel subLevel : List.copyOf(container.getAllSubLevels())) {
            final int localPlotX = subLevel.getPlot().plotPos.x - origin.x;
            final int localPlotZ = subLevel.getPlot().plotPos.z - origin.y;
            loaded.add(new LoadedFingerprint(
                    subLevel.getRuntimeId(),
                    subLevel.getUniqueId(),
                    localPlotX,
                    localPlotZ,
                    subLevel.getLastSerializationPointer(),
                    subLevel.isRemoved()
            ));
        }
        loaded.sort(Comparator
                .comparing((LoadedFingerprint value) -> value.uuid().toString())
                .thenComparingInt(LoadedFingerprint::runtimeId)
                .thenComparingInt(LoadedFingerprint::localPlotX)
                .thenComparingInt(LoadedFingerprint::localPlotZ));
        return List.copyOf(loaded);
    }

    private static Snapshot failed(final String failure) {
        return new Snapshot(Status.FAILED, Map.of(), List.of(), failure);
    }

    private record LoadedFingerprint(
            int runtimeId,
            UUID uuid,
            int localPlotX,
            int localPlotZ,
            @Nullable GlobalSavedSubLevelPointer recoveryPointer,
            boolean removed
    ) {
        private LoadedFingerprint {
            Objects.requireNonNull(uuid, "uuid");
        }

        private Loaded location(final ResourceKey<Level> dimension) {
            return new Loaded(dimension, this.localPlotX, this.localPlotZ, this.recoveryPointer);
        }
    }

    static final class Accumulator {
        private final Map<UUID, Location> locations = new LinkedHashMap<>();
        private final List<Conflict> conflicts = new ArrayList<>();

        void register(final UUID uuid, final Location candidate) {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(candidate, "candidate");
            final Location existing = this.locations.get(uuid);
            if (existing == null) {
                this.locations.put(uuid, candidate);
                return;
            }
            if (existing.equals(candidate)) {
                return;
            }

            if (existing instanceof final Loaded loaded && candidate instanceof final Stored stored &&
                    isRecoveryAlias(loaded, stored)) {
                return;
            }
            if (existing instanceof final Stored stored && candidate instanceof final Loaded loaded &&
                    isRecoveryAlias(loaded, stored)) {
                this.locations.put(uuid, loaded);
                return;
            }

            this.conflicts.add(new Conflict(uuid, existing, candidate));
        }

        Snapshot finish() {
            if (!this.conflicts.isEmpty()) {
                return new Snapshot(Status.CONFLICTED, this.locations, this.conflicts, null);
            }
            return new Snapshot(Status.COMPLETE, this.locations, List.of(), null);
        }

        private static boolean isRecoveryAlias(final Loaded loaded, final Stored stored) {
            return loaded.dimension().equals(stored.dimension()) &&
                    loaded.recoveryPointer() != null &&
                    loaded.recoveryPointer().equals(stored.pointer());
        }
    }
}
