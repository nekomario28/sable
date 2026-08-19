package dev.ryanhcode.sable.api.physics;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Operational contract for physics pipelines that can acquire one transaction-owned SubLevel
 * section without overwriting pre-existing native physics state.
 *
 * <p>The capability bit in {@link SubLevelReconstructionPhysicsSupport} is intentionally not enough:
 * reconstruction needs an actual owner-aware operation whose successful acquisition can be verified,
 * committed, or rolled back exactly. The operation also receives an immutable detached block-state
 * view so provider-specific neighborhood/collider encoding never needs to read the live target world.</p>
 */
@ApiStatus.Experimental
public interface SubLevelReconstructionSectionSupport {

    /**
     * Immutable reconstruction block-state source. Missing decoded positions must read as air.
     * Implementations may query positions outside the section to derive neighborhood-dependent
     * physics state, but must not retain or mutate the view.
     */
    @FunctionalInterface
    interface ReconstructionBlockStateView {
        @NotNull BlockState stateAt(int globalBlockX, int globalBlockY, int globalBlockZ);
    }

    /**
     * Acquires one native physics section for the given provisional SubLevel owner.
     *
     * <p>The implementation must fail before externally visible mutation if the native section key
     * is already occupied, the owner body is unavailable, or exact rollback cannot be guaranteed.
     * If this method throws, it must leave the native physics scene observationally unchanged.</p>
     *
     * @param owner provisional target SubLevel that exclusively owns the new section
     * @param sectionPos exact global section position
     * @param blockStates immutable detached state view containing this section and its neighbors
     * @return an open transaction-owned section reservation
     */
    ReconstructionSectionReservation acquireReconstructionSection(
            ServerSubLevel owner,
            SectionPos sectionPos,
            ReconstructionBlockStateView blockStates
    );

    interface ReconstructionSectionReservation {
        /** @return exact global section position owned by this reservation */
        SectionPos sectionPos();

        /** @return runtime ID of the provisional SubLevel owner */
        int ownerRuntimeId();

        /** @return whether this reservation is still transaction-owned */
        boolean open();

        /**
         * Read-only proof that the native section still exists and is owned by this reservation.
         * A failed verification must leave the reservation open.
         */
        void verify();

        /**
         * Makes the verified section permanent without changing its section contents.
         * If this method throws, the reservation must remain open and rollbackable.
         */
        void commit();

        /**
         * Removes only this transaction-owned native section and restores exact pre-acquisition
         * section state. If exact restoration cannot be proven, this method must throw before
         * pretending the reservation is closed.
         */
        void rollback();
    }
}
