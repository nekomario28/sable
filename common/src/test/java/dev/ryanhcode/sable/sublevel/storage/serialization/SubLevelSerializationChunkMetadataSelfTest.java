package dev.ryanhcode.sable.sublevel.storage.serialization;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/** Assertion-based executable for serialized plot chunk metadata layout. */
public final class SubLevelSerializationChunkMetadataSelfTest {
    private SubLevelSerializationChunkMetadataSelfTest() {
    }

    public static void main(final String[] args) {
        lightStateTargetsExactLocalChunk();
        missingChunkTargetFailsClosed();
        missingChunkContainerFailsClosed();
        legacyRootLightStateIsRemoved();
        System.out.println("SUB_LEVEL_SERIALIZATION_CHUNK_METADATA_SELF_TEST: PASS");
    }

    private static void lightStateTargetsExactLocalChunk() {
        final CompoundTag plot = new CompoundTag();
        final CompoundTag chunks = new CompoundTag();
        final ChunkPos first = new ChunkPos(0, 0);
        final ChunkPos second = new ChunkPos(2, 3);
        chunks.put(String.valueOf(ChunkPos.asLong(first.x, first.z)), new CompoundTag());
        chunks.put(String.valueOf(ChunkPos.asLong(second.x, second.z)), new CompoundTag());
        plot.put("chunks", chunks);

        final CompoundTag firstTag = SubLevelSerializationChunkMetadata.writeLightState(
                SubLevelSerializationChunkMetadata.requireChunks(plot), first, true
        );
        final CompoundTag secondTag = SubLevelSerializationChunkMetadata.writeLightState(
                SubLevelSerializationChunkMetadata.requireChunks(plot), second, false
        );

        assert firstTag.getBoolean("isLightOn");
        assert !secondTag.getBoolean("isLightOn");
        assert chunks.getCompound(String.valueOf(ChunkPos.asLong(first.x, first.z))) == firstTag;
        assert chunks.getCompound(String.valueOf(ChunkPos.asLong(second.x, second.z))) == secondTag;
    }

    private static void missingChunkTargetFailsClosed() {
        final CompoundTag chunks = new CompoundTag();
        assertIllegalState(() -> SubLevelSerializationChunkMetadata.writeLightState(
                chunks,
                new ChunkPos(7, 9),
                true
        ));
    }

    private static void missingChunkContainerFailsClosed() {
        assertIllegalState(() -> SubLevelSerializationChunkMetadata.requireChunks(new CompoundTag()));
    }

    private static void legacyRootLightStateIsRemoved() {
        final CompoundTag plot = new CompoundTag();
        plot.putBoolean("isLightOn", true);

        SubLevelSerializationChunkMetadata.removeLegacyRootLightState(plot);

        assert !plot.contains("isLightOn");
    }

    private static void assertIllegalState(final Runnable operation) {
        boolean threw = false;
        try {
            operation.run();
        } catch (final IllegalStateException expected) {
            threw = true;
        }
        assert threw;
    }
}
