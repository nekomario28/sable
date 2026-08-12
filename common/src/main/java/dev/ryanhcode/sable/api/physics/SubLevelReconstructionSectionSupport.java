package dev.ryanhcode.sable.api.physics;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.ApiStatus;

/**
 * Operational contract for physics pipelines that can acquire one transaction-owned SubLevel
 * section without overwriting pre-existing native physics state.
 *
 * <p>The capability bit in {@link SubLevelReconstructionPhysicsSupport} is intentionally not enough:
 * reconstruction needs an actual owner-aware operation whose successful acquisition can be verified,
 * committed, or rolled back exactly.</p>
 */
@ApiStatus.Experimental
public interface SubLevelReconstructionSectionSupport {

    /**
     * Acquires one native physics section for the given provisional SubLevel owner.
     *
     * <p>The implementation must fail before externally visible mutation if the native section key
     * is already occupied, the owner body is unavailable, or exact rollback cannot be guaranteed.
     * If this method throws, it must leave the native physics scene observationally unchanged.</p>
     *
     * @param owner provisional target SubLevel that exclusively owns the new section
     * @param section decoded section contents to upload
     * @param sectionPos exact global section position
     * @return an open transaction-owned section reservation
     */
    ReconstructionSectionReservation acquireReconstructionSection(
            ServerSubLevel owner,
            LevelChunkSection section,
            SectionPos sectionPos
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
