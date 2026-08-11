package dev.ryanhcode.sable.physics.impl.rapier;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Monotonic runtime-ID allocator with an explicit transaction-only reservation primitive.
 *
 * <p>Normal allocation remains exactly equivalent to the historical {@code counter++} behavior.
 * A reservation may be rolled back only while it is still the most recent allocation. If any
 * unrelated runtime ID was allocated afterwards, rollback fails closed instead of risking an ID
 * collision or rewinding observable allocator state past another owner.</p>
 */
@ApiStatus.Internal
final class RapierRuntimeIdAllocator {
    private int nextId;
    private ClaimScope activeClaimScope;

    synchronized int next() {
        if (this.activeClaimScope != null) {
            return this.activeClaimScope.claim();
        }
        return this.nextId++;
    }

    synchronized Reservation reserve() {
        if (this.activeClaimScope != null) {
            throw new IllegalStateException("Cannot reserve another runtime ID inside an adoption scope");
        }
        return new Reservation(this, this.nextId++);
    }

    synchronized <T> T withReservation(final Reservation reservation, final Supplier<T> allocation) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(allocation, "allocation");
        if (reservation.allocator != this) {
            throw new IllegalArgumentException("Runtime ID reservation belongs to another allocator");
        }
        if (!reservation.open()) {
            throw new IllegalStateException("Runtime ID reservation is already closed");
        }
        if (this.activeClaimScope != null) {
            throw new IllegalStateException("Nested runtime ID adoption scopes are not allowed");
        }

        final ClaimScope scope = new ClaimScope(reservation.id());
        this.activeClaimScope = scope;
        try {
            final T result = allocation.get();
            if (!scope.claimed()) {
                throw new IllegalStateException("Reserved runtime ID adoption consumed no runtime ID");
            }
            if (!reservation.open()) {
                throw new IllegalStateException("Runtime ID reservation was closed inside its adoption scope");
            }
            return result;
        } finally {
            this.activeClaimScope = null;
        }
    }

    private synchronized void rollback(final int id) {
        if (this.activeClaimScope != null && this.activeClaimScope.runtimeId == id) {
            throw new IllegalStateException("Cannot roll back a runtime ID while its adoption scope is active");
        }
        if (this.nextId != id + 1) {
            throw new IllegalStateException(
                    "Cannot roll back runtime ID %d after a later ID was allocated".formatted(id)
            );
        }
        this.nextId = id;
    }

    private static final class ClaimScope {
        private final int runtimeId;
        private boolean claimed;

        private ClaimScope(final int runtimeId) {
            this.runtimeId = runtimeId;
        }

        private int claim() {
            if (this.claimed) {
                throw new IllegalStateException("Reserved runtime ID adoption consumed more than one runtime ID");
            }
            this.claimed = true;
            return this.runtimeId;
        }

        private boolean claimed() {
            return this.claimed;
        }
    }

    @ApiStatus.Internal
    static final class Reservation {
        private enum State {
            OPEN,
            COMMITTED,
            ROLLED_BACK
        }

        private final RapierRuntimeIdAllocator allocator;
        private final int id;
        private State state = State.OPEN;

        private Reservation(final RapierRuntimeIdAllocator allocator, final int id) {
            this.allocator = Objects.requireNonNull(allocator, "allocator");
            this.id = id;
        }

        synchronized int id() {
            return this.id;
        }

        synchronized boolean open() {
            return this.state == State.OPEN;
        }

        synchronized void commit() {
            if (this.state != State.OPEN) {
                throw new IllegalStateException("Runtime ID reservation is already closed: " + this.state);
            }
            this.state = State.COMMITTED;
        }

        synchronized void rollback() {
            if (this.state != State.OPEN) {
                throw new IllegalStateException("Runtime ID reservation is already closed: " + this.state);
            }
            this.allocator.rollback(this.id);
            this.state = State.ROLLED_BACK;
        }
    }
}
