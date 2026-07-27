package dev.ryanhcode.sable.sublevel.transfer;

import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferUuidLocationIndexCodecTest {
    @Test
    void completeLoadedAndStoredIndexRoundTrips() {
        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        final UUID loadedId = UUID.randomUUID();
        final UUID storedId = UUID.randomUUID();
        index.register(loadedId, loaded("minecraft:overworld", 1, 2));
        index.register(storedId, stored("starlance:space", 3, 4, (short) 5, (short) 6));
        index.complete();

        final CompoundTag encoded = CrossLevelTransferUuidLocationIndexCodec.encode(index);
        final CrossLevelTransferUuidLocationIndex decoded =
                CrossLevelTransferUuidLocationIndexCodec.decode(encoded).orElseThrow();

        assertEquals(CrossLevelTransferUuidLocationIndex.Status.COMPLETE, decoded.status());
        assertEquals(index.snapshot(), decoded.snapshot());
        assertTrue(decoded.provesExactLocation(loadedId, loaded("minecraft:overworld", 1, 2)));
        assertTrue(decoded.provesExactLocation(
                storedId,
                stored("starlance:space", 3, 4, (short) 5, (short) 6)
        ));
    }

    @Test
    void encodingIsDeterministicAcrossRegistrationOrder() {
        final UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        final UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        final CrossLevelTransferUuidLocationIndex first = new CrossLevelTransferUuidLocationIndex();
        first.register(secondId, loaded("minecraft:overworld", 2, 2));
        first.register(firstId, loaded("minecraft:overworld", 1, 1));
        first.complete();

        final CrossLevelTransferUuidLocationIndex second = new CrossLevelTransferUuidLocationIndex();
        second.register(firstId, loaded("minecraft:overworld", 1, 1));
        second.register(secondId, loaded("minecraft:overworld", 2, 2));
        second.complete();

        assertEquals(
                CrossLevelTransferUuidLocationIndexCodec.encode(first),
                CrossLevelTransferUuidLocationIndexCodec.encode(second)
        );
    }

    @Test
    void incompleteOrConflictedIndexCannotBeEncoded() {
        final CrossLevelTransferUuidLocationIndex building = new CrossLevelTransferUuidLocationIndex();
        assertThrows(
                IllegalStateException.class,
                () -> CrossLevelTransferUuidLocationIndexCodec.encode(building)
        );

        final CrossLevelTransferUuidLocationIndex conflicted = new CrossLevelTransferUuidLocationIndex();
        final UUID duplicate = UUID.randomUUID();
        conflicted.register(duplicate, loaded("minecraft:overworld", 1, 1));
        conflicted.register(duplicate, loaded("minecraft:overworld", 2, 2));
        assertThrows(
                IllegalStateException.class,
                () -> CrossLevelTransferUuidLocationIndexCodec.encode(conflicted)
        );
    }

    @Test
    void unsupportedOrMalformedRootFailsClosed() {
        final CompoundTag unsupported = new CompoundTag();
        unsupported.putInt("codec_version", Integer.MAX_VALUE);
        unsupported.put("entries", new ListTag());
        assertTrue(CrossLevelTransferUuidLocationIndexCodec.decode(unsupported).isEmpty());

        final CompoundTag missingEntries = new CompoundTag();
        missingEntries.putInt("codec_version", 1);
        assertTrue(CrossLevelTransferUuidLocationIndexCodec.decode(missingEntries).isEmpty());

        final CrossLevelTransferUuidLocationIndex valid = new CrossLevelTransferUuidLocationIndex();
        valid.register(UUID.randomUUID(), loaded("minecraft:overworld", 1, 1));
        valid.complete();
        final CompoundTag unknownKind = CrossLevelTransferUuidLocationIndexCodec.encode(valid);
        unknownKind.getList("entries", Tag.TAG_COMPOUND).getCompound(0).putString("kind", "unknown");
        assertTrue(CrossLevelTransferUuidLocationIndexCodec.decode(unknownKind).isEmpty());
    }

    @Test
    void anyDuplicateUuidEntryRejectsTheWholeIndex() {
        final CrossLevelTransferUuidLocationIndex valid = new CrossLevelTransferUuidLocationIndex();
        valid.register(UUID.randomUUID(), loaded("minecraft:overworld", 1, 1));
        valid.complete();

        final CompoundTag conflicting = CrossLevelTransferUuidLocationIndexCodec.encode(valid);
        final ListTag conflictingEntries = conflicting.getList("entries", Tag.TAG_COMPOUND);
        final CompoundTag conflict = conflictingEntries.getCompound(0).copy();
        conflict.putInt("local_plot_x", 2);
        conflictingEntries.add(conflict);
        assertTrue(CrossLevelTransferUuidLocationIndexCodec.decode(conflicting).isEmpty());

        final CompoundTag identical = CrossLevelTransferUuidLocationIndexCodec.encode(valid);
        final ListTag identicalEntries = identical.getList("entries", Tag.TAG_COMPOUND);
        identicalEntries.add(identicalEntries.getCompound(0).copy());
        assertTrue(CrossLevelTransferUuidLocationIndexCodec.decode(identical).isEmpty());
    }

    @Test
    void invalidLocationCoordinatesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> loaded("minecraft:overworld", -1, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CrossLevelTransferSubLevelLocation.Stored(
                        "minecraft:overworld",
                        new GlobalSavedSubLevelPointer(new ChunkPos(0, 0), (short) -1, (short) 0)
                )
        );
    }

    @Test
    void nullContractsAreRejected() {
        assertThrows(NullPointerException.class, () -> CrossLevelTransferUuidLocationIndexCodec.encode(null));
        assertThrows(NullPointerException.class, () -> CrossLevelTransferUuidLocationIndexCodec.decode(null));
    }

    private static CrossLevelTransferSubLevelLocation loaded(
            final String dimension,
            final int localPlotX,
            final int localPlotZ
    ) {
        return new CrossLevelTransferSubLevelLocation.Loaded(dimension, localPlotX, localPlotZ);
    }

    private static CrossLevelTransferSubLevelLocation stored(
            final String dimension,
            final int chunkX,
            final int chunkZ,
            final short storageIndex,
            final short subLevelIndex
    ) {
        return new CrossLevelTransferSubLevelLocation.Stored(
                dimension,
                new GlobalSavedSubLevelPointer(
                        new ChunkPos(chunkX, chunkZ),
                        storageIndex,
                        subLevelIndex
                )
        );
    }
}
