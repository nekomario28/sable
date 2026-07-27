package dev.ryanhcode.sable.sublevel.transfer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds one complete, conflict-free UUID-to-location index.
 *
 * <p>The index proves nothing while building. A conflicting duplicate permanently
 * moves it to {@link Status#CONFLICTED}; only an explicitly completed index may
 * provide authoritative location evidence.</p>
 */
public final class CrossLevelTransferUuidLocationIndex {
    public enum Status {
        BUILDING,
        COMPLETE,
        CONFLICTED
    }

    public enum RegistrationResult {
        REGISTERED,
        ALREADY_REGISTERED,
        DUPLICATE_UUID_CONFLICT,
        INDEX_CONFLICTED
    }

    private final Map<UUID, CrossLevelTransferSubLevelLocation> locations = new HashMap<>();
    private Status status = Status.BUILDING;

    /**
     * Registers one discovered location during a complete index build.
     */
    public synchronized RegistrationResult register(
            final UUID subLevelId,
            final CrossLevelTransferSubLevelLocation location
    ) {
        Objects.requireNonNull(subLevelId, "subLevelId");
        Objects.requireNonNull(location, "location");

        if (this.status == Status.COMPLETE) {
            throw new IllegalStateException("Cannot register after the UUID location index is complete");
        }
        if (this.status == Status.CONFLICTED) {
            return RegistrationResult.INDEX_CONFLICTED;
        }

        final CrossLevelTransferSubLevelLocation existing = this.locations.get(subLevelId);
        if (existing == null) {
            this.locations.put(subLevelId, location);
            return RegistrationResult.REGISTERED;
        }
        if (existing.equals(location)) {
            return RegistrationResult.ALREADY_REGISTERED;
        }

        this.status = Status.CONFLICTED;
        return RegistrationResult.DUPLICATE_UUID_CONFLICT;
    }

    /**
     * Marks a conflict-free build complete. Completion is idempotent.
     */
    public synchronized void complete() {
        if (this.status == Status.CONFLICTED) {
            throw new IllegalStateException("Cannot complete a conflicted UUID location index");
        }
        this.status = Status.COMPLETE;
    }

    /**
     * Proves that the UUID has exactly the expected authoritative location.
     * Building, conflicted, missing, or different locations fail closed.
     */
    public synchronized boolean provesExactLocation(
            final UUID subLevelId,
            final CrossLevelTransferSubLevelLocation expected
    ) {
        Objects.requireNonNull(subLevelId, "subLevelId");
        Objects.requireNonNull(expected, "expected");
        return this.status == Status.COMPLETE && expected.equals(this.locations.get(subLevelId));
    }

    public synchronized Optional<CrossLevelTransferSubLevelLocation> location(final UUID subLevelId) {
        Objects.requireNonNull(subLevelId, "subLevelId");
        return Optional.ofNullable(this.locations.get(subLevelId));
    }

    public synchronized Map<UUID, CrossLevelTransferSubLevelLocation> snapshot() {
        return Map.copyOf(this.locations);
    }

    public synchronized Status status() {
        return this.status;
    }

    public synchronized int size() {
        return this.locations.size();
    }
}
