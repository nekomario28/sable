package dev.ryanhcode.sable.physics.impl.rapier;

import org.jetbrains.annotations.ApiStatus;

/** Dedicated JNI bridge for transactional reconstruction operations. */
@ApiStatus.Internal
final class RapierReconstructionNative {
    static {
        // Force the ordinary Rapier loader to initialize before any dedicated JNI symbol is used.
        @SuppressWarnings("unused")
        final String ignored = Rapier3D.NATIVE_NAME;
    }

    private RapierReconstructionNative() {
    }

    static native boolean acquireReconstructionSubLevelChunk(long sceneHandle, int x, int y, int z, int[] data, int objectId);

    static native boolean verifyReconstructionSubLevelChunk(long sceneHandle, int x, int y, int z, int objectId);

    static native boolean commitReconstructionSubLevelChunk(long sceneHandle, int x, int y, int z, int objectId);

    static native boolean rollbackReconstructionSubLevelChunk(long sceneHandle, int x, int y, int z, int objectId);

    static native boolean removeSubLevelChunk(long sceneHandle, int x, int y, int z, int objectId);

    static native boolean clearReconstructionSectionOwnershipForScene(long sceneHandle);
}
