package dev.ryanhcode.sable.physics.impl.rapier;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

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
        return RUNTIME_IDS.reserve();
    }
}
