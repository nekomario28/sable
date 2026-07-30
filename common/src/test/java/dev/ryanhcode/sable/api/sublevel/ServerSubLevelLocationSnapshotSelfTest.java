package dev.ryanhcode.sable.api.sublevel;

import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Assertion-based executable for the authoritative UUID location resolver. */
public final class ServerSubLevelLocationSnapshotSelfTest {
    private ServerSubLevelLocationSnapshotSelfTest() {
    }

    public static void main(final String[] args) {
        matchingRecoveryPointerCollapsesToLoaded();
        reverseMatchingRecoveryPointerCollapsesToLoaded();
        differentStoredPointerConflicts();
        crossDimensionDuplicateConflicts();
        identicalStoredObservationIsIdempotent();
        System.out.println("SERVER_SUB_LEVEL_LOCATION_SNAPSHOT_SELF_TEST: PASS");
    }

    private static void matchingRecoveryPointerCollapsesToLoaded() {
        final UUID uuid = UUID.randomUUID();
        final GlobalSavedSubLevelPointer pointer = pointer(2, 3, 4, 5);
        final ServerSubLevelLocationSnapshot.Loaded loaded =
                new ServerSubLevelLocationSnapshot.Loaded(Level.OVERWORLD, 7, 8, pointer);
        final ServerSubLevelLocationSnapshot.Accumulator accumulator =
                new ServerSubLevelLocationSnapshot.Accumulator();

        accumulator.register(uuid, loaded);
        accumulator.register(uuid, new ServerSubLevelLocationSnapshot.Stored(Level.OVERWORLD, pointer));

        final ServerSubLevelLocationSnapshot.Snapshot snapshot = accumulator.finish();
        assert snapshot.status() == ServerSubLevelLocationSnapshot.Status.COMPLETE;
        assert snapshot.locations().size() == 1;
        assert snapshot.locations().get(uuid).equals(loaded);
    }

    private static void reverseMatchingRecoveryPointerCollapsesToLoaded() {
        final UUID uuid = UUID.randomUUID();
        final GlobalSavedSubLevelPointer pointer = pointer(-2, 6, 1, 9);
        final ServerSubLevelLocationSnapshot.Loaded loaded =
                new ServerSubLevelLocationSnapshot.Loaded(Level.OVERWORLD, 1, 2, pointer);
        final ServerSubLevelLocationSnapshot.Accumulator accumulator =
                new ServerSubLevelLocationSnapshot.Accumulator();

        accumulator.register(uuid, new ServerSubLevelLocationSnapshot.Stored(Level.OVERWORLD, pointer));
        accumulator.register(uuid, loaded);

        final ServerSubLevelLocationSnapshot.Snapshot snapshot = accumulator.finish();
        assert snapshot.status() == ServerSubLevelLocationSnapshot.Status.COMPLETE;
        assert snapshot.locations().get(uuid).equals(loaded);
    }

    private static void differentStoredPointerConflicts() {
        final UUID uuid = UUID.randomUUID();
        final GlobalSavedSubLevelPointer recoveryPointer = pointer(0, 0, 0, 1);
        final ServerSubLevelLocationSnapshot.Accumulator accumulator =
                new ServerSubLevelLocationSnapshot.Accumulator();

        accumulator.register(
                uuid,
                new ServerSubLevelLocationSnapshot.Loaded(Level.OVERWORLD, 3, 4, recoveryPointer)
        );
        accumulator.register(
                uuid,
                new ServerSubLevelLocationSnapshot.Stored(Level.OVERWORLD, pointer(0, 0, 0, 2))
        );

        final ServerSubLevelLocationSnapshot.Snapshot snapshot = accumulator.finish();
        assert snapshot.status() == ServerSubLevelLocationSnapshot.Status.CONFLICTED;
        assert snapshot.conflicts().size() == 1;
    }

    private static void crossDimensionDuplicateConflicts() {
        final UUID uuid = UUID.randomUUID();
        final GlobalSavedSubLevelPointer pointer = pointer(1, 1, 2, 3);
        final ServerSubLevelLocationSnapshot.Accumulator accumulator =
                new ServerSubLevelLocationSnapshot.Accumulator();

        accumulator.register(
                uuid,
                new ServerSubLevelLocationSnapshot.Loaded(Level.OVERWORLD, 5, 6, pointer)
        );
        accumulator.register(
                uuid,
                new ServerSubLevelLocationSnapshot.Stored(Level.NETHER, pointer)
        );

        final ServerSubLevelLocationSnapshot.Snapshot snapshot = accumulator.finish();
        assert snapshot.status() == ServerSubLevelLocationSnapshot.Status.CONFLICTED;
        assert snapshot.conflicts().size() == 1;
    }

    private static void identicalStoredObservationIsIdempotent() {
        final UUID uuid = UUID.randomUUID();
        final ServerSubLevelLocationSnapshot.Stored stored =
                new ServerSubLevelLocationSnapshot.Stored(Level.END, pointer(9, -4, 7, 11));
        final ServerSubLevelLocationSnapshot.Accumulator accumulator =
                new ServerSubLevelLocationSnapshot.Accumulator();

        accumulator.register(uuid, stored);
        accumulator.register(uuid, stored);

        final ServerSubLevelLocationSnapshot.Snapshot snapshot = accumulator.finish();
        assert snapshot.status() == ServerSubLevelLocationSnapshot.Status.COMPLETE;
        assert snapshot.locations().size() == 1;
        assert snapshot.locations().get(uuid).equals(stored);
    }

    private static GlobalSavedSubLevelPointer pointer(
            final int chunkX,
            final int chunkZ,
            final int storageIndex,
            final int subLevelIndex
    ) {
        return new GlobalSavedSubLevelPointer(
                new ChunkPos(chunkX, chunkZ),
                (short) storageIndex,
                (short) subLevelIndex
        );
    }
}
