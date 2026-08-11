package dev.ryanhcode.sable.physics.impl.rapier;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

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

    synchronized int next() {
        return this.nextId++;
    }

    synchronized Reservation reserve() {
        return new Reservation(this, this.nextId++);
    }

    private synchronized void rollback(final int id) {
        if (this.nextId != id + 1) {
            throw new IllegalStateException(
                    "Cannot roll back runtime ID %d after a later ID was allocated".formatted(id)
            );
        }
        this.nextId = id;
    }

    @ApiStatus.Internal
    static final class Reservation implements SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation {
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

        @Override
        public synchronized int runtimeId() {
            return this.id;
        }

        @Override
        public synchronized boolean open() {
            return this.state == State.OPEN;
        }

        @Override
        public synchronized void commit() {
            if (this.state != State.OPEN) {
                throw new IllegalStateException("Runtime ID reservation is already closed: " + this.state);
            }
            this.state = State.COMMITTED;
        }

        @Override
        public synchronized void rollback() {
            if (this.state != State.OPEN) {
                throw new IllegalStateException("Runtime ID reservation is already closed: " + this.state);
            }
            this.allocator.rollback(this.id);
            this.state = State.ROLLED_BACK;
        }
    }
}
