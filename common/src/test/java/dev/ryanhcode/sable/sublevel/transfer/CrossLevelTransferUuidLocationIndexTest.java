package dev.ryanhcode.sable.sublevel.transfer;

import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferUuidLocationIndexTest {
    @Test
    void buildingIndexCannotProveLocation() {
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferSubLevelLocation location = loaded(1, 2);

        assertEquals(
                CrossLevelTransferUuidLocationIndex.RegistrationResult.REGISTERED,
                index.register(subLevelId, location)
        );
        assertEquals(CrossLevelTransferUuidLocationIndex.Status.BUILDING, index.status());
        assertFalse(index.provesExactLocation(subLevelId, location));
    }

    @Test
    void completeConflictFreeIndexProvesOnlyExactLocation() {
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferSubLevelLocation location = loaded(3, 4);

        index.register(subLevelId, location);
        index.complete();
        index.complete();

        assertEquals(CrossLevelTransferUuidLocationIndex.Status.COMPLETE, index.status());
        assertTrue(index.provesExactLocation(subLevelId, location));
        assertFalse(index.provesExactLocation(subLevelId, loaded(4, 3)));
        assertFalse(index.provesExactLocation(UUID.randomUUID(), location));
        assertThrows(IllegalStateException.class, () -> index.register(UUID.randomUUID(), loaded(5, 6)));
    }

    @Test
    void identicalRegistrationIsIdempotent() {
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferSubLevelLocation location = stored(7, 8, (short) 2, (short) 3);

        assertEquals(
                CrossLevelTransferUuidLocationIndex.RegistrationResult.REGISTERED,
                index.register(subLevelId, location)
        );
        assertEquals(
                CrossLevelTransferUuidLocationIndex.RegistrationResult.ALREADY_REGISTERED,
                index.register(subLevelId, location)
        );
        assertEquals(1, index.size());
    }

    @Test
    void conflictingDuplicatePermanentlyFailsClosed() {
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        final UUID subLevelId = UUID.randomUUID();
        final CrossLevelTransferSubLevelLocation original = loaded(9, 10);
        final CrossLevelTransferSubLevelLocation duplicate = stored(11, 12, (short) 4, (short) 5);

        index.register(subLevelId, original);
        assertEquals(
                CrossLevelTransferUuidLocationIndex.RegistrationResult.DUPLICATE_UUID_CONFLICT,
                index.register(subLevelId, duplicate)
        );

        assertEquals(CrossLevelTransferUuidLocationIndex.Status.CONFLICTED, index.status());
        assertFalse(index.provesExactLocation(subLevelId, original));
        assertEquals(
                CrossLevelTransferUuidLocationIndex.RegistrationResult.INDEX_CONFLICTED,
                index.register(UUID.randomUUID(), loaded(13, 14))
        );
        assertThrows(IllegalStateException.class, index::complete);
        assertEquals(original, index.location(subLevelId).orElseThrow());
    }

    @Test
    void snapshotsAreImmutableCopies() {
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        final UUID subLevelId = UUID.randomUUID();
        index.register(subLevelId, loaded(15, 16));

        final Map<UUID, CrossLevelTransferSubLevelLocation> snapshot = index.snapshot();

        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () ->
                snapshot.put(UUID.randomUUID(), loaded(17, 18)));
    }

    @Test
    void locationAndIndexNullContractsAreRejected() {
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        final UUID subLevelId = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> index.register(null, loaded(1, 1)));
        assertThrows(NullPointerException.class, () -> index.register(subLevelId, null));
        assertThrows(NullPointerException.class, () -> index.provesExactLocation(null, loaded(1, 1)));
        assertThrows(NullPointerException.class, () -> index.provesExactLocation(subLevelId, null));
        assertThrows(NullPointerException.class, () -> new CrossLevelTransferSubLevelLocation.Loaded(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CrossLevelTransferSubLevelLocation.Loaded(" ", 0, 0));
        assertThrows(NullPointerException.class, () -> new CrossLevelTransferSubLevelLocation.Stored(
                "minecraft:overworld",
                null
        ));
    }

    private static CrossLevelTransferSubLevelLocation loaded(final int localPlotX, final int localPlotZ) {
        return new CrossLevelTransferSubLevelLocation.Loaded(
                "minecraft:overworld",
                localPlotX,
                localPlotZ
        );
    }

    private static CrossLevelTransferSubLevelLocation stored(
            final int chunkX,
            final int chunkZ,
            final short storageIndex,
            final short subLevelIndex
    ) {
        return new CrossLevelTransferSubLevelLocation.Stored(
                "minecraft:overworld",
                new GlobalSavedSubLevelPointer(
                        new ChunkPos(chunkX, chunkZ),
                        storageIndex,
                        subLevelIndex
                )
        );
    }
}
