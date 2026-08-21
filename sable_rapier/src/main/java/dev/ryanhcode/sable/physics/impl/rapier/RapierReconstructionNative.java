package dev.ryanhcode.sable.physics.impl.rapier;

import org.jetbrains.annotations.ApiStatus;

/**
 * Dedicated JNI bridge for transactional reconstruction operations.
 *
 * <p>Body lifecycle symbols are invoked only after the matching Rust sources have been repacked
 * into the bundled Rapier native archive; PR verification also rebuilds the native library before
 * running the reconstruction GameTests.</p>
 */
@ApiStatus.Internal
final class RapierReconstructionNative {
    static {
        // Force the ordinary Rapier loader to initialize before any dedicated JNI symbol is used.
        @SuppressWarnings("unused")
        final String ignored = Rapier3D.NATIVE_NAME;
    }

    private RapierReconstructionNative() {
    }

    static native boolean acquireReconstructionSubLevelBody(
            long sceneHandle,
            int objectId,
            double[] pose,
            double mass,
            double[] centerOfMass,
            double[] inertia,
            int[] bounds,
            int[] plotSections
    );

    static native boolean verifyReconstructionSubLevelBody(long sceneHandle, int objectId);

    static native boolean commitReconstructionSubLevelBody(long sceneHandle, int objectId);

    static native boolean rollbackReconstructionSubLevelBody(long sceneHandle, int objectId);

    static native boolean clearReconstructionBodyOwnershipForScene(long sceneHandle);

    static native boolean acquireReconstructionSubLevelChunk(long sceneHandle, int x, int y, int z, int[] data, int objectId);

    static native boolean verifyReconstructionSubLevelChunk(long sceneHandle, int x, int y, int z, int objectId);

    static native boolean commitReconstructionSubLevelChunk(long sceneHandle, int x, int y, int z, int objectId);

    static native boolean rollbackReconstructionSubLevelChunk(long sceneHandle, int x, int y, int z, int objectId);

    static native boolean removeSubLevelChunk(long sceneHandle, int x, int y, int z, int objectId);

    static native boolean clearReconstructionSectionOwnershipForScene(long sceneHandle);
}