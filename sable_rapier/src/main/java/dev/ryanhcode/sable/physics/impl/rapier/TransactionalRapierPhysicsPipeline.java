package dev.ryanhcode.sable.physics.impl.rapier;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Rapier pipeline entry point that keeps normal runtime-ID allocation and reconstruction
 * reservations in one global allocator.
 *
 * <p>This class intentionally opts in only to the runtime-ID reservation operation. It does not
 * implement {@link dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport}, so
 * transactional reconstruction remains rejected until provisional body lifecycle and exact section
 * rollback are independently implemented and proven.</p>
 */
@ApiStatus.Internal
final class TransactionalRapierPhysicsPipeline extends RapierPhysicsPipeline
        implements SubLevelReconstructionRuntimeIdSupport {
    private static final RapierRuntimeIdAllocator RUNTIME_IDS = new RapierRuntimeIdAllocator();

    TransactionalRapierPhysicsPipeline(final ServerLevel level) {
        super(level);
    }

    @Override
    public int getNextRuntimeID() {
        return RUNTIME_IDS.next();
    }

    @Override
    public RuntimeIdReservation reserveReconstructionRuntimeId() {
        return new RuntimeIdReservationAdapter(this, RUNTIME_IDS.reserve());
    }

    @Override
    public <T> T withReservedRuntimeId(
            final RuntimeIdReservation reservation,
            final Supplier<T> allocation
    ) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(allocation, "allocation");
        if (!(reservation instanceof final RuntimeIdReservationAdapter adapter) || adapter.owner != this) {
            throw new IllegalArgumentException("Runtime ID reservation was not created by this physics pipeline");
        }
        return RUNTIME_IDS.withReservation(adapter.delegate, allocation);
    }

    private record RuntimeIdReservationAdapter(
            TransactionalRapierPhysicsPipeline owner,
            RapierRuntimeIdAllocator.Reservation delegate
    ) implements RuntimeIdReservation {
        private RuntimeIdReservationAdapter {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public int runtimeId() {
            return this.delegate.id();
        }

        @Override
        public boolean open() {
            return this.delegate.open();
        }

        @Override
        public void commit() {
            this.delegate.commit();
        }

        @Override
        public void rollback() {
            this.delegate.rollback();
        }
    }
}
