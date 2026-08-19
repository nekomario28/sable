use super::reconstruction_section;
use jni::JNIEnv;
use jni::objects::{JClass, JIntArray};
use jni::sys::{jboolean, jint, jlong};

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_acquireReconstructionSubLevelChunk<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    data: JIntArray<'local>,
    object_id: jint,
) -> jboolean {
    reconstruction_section::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_acquireReconstructionSubLevelChunk(
        env, class, handle, x, y, z, data, object_id,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_verifyReconstructionSubLevelChunk<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    object_id: jint,
) -> jboolean {
    reconstruction_section::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_verifyReconstructionSubLevelChunk(
        env, class, handle, x, y, z, object_id,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_commitReconstructionSubLevelChunk<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    object_id: jint,
) -> jboolean {
    reconstruction_section::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_commitReconstructionSubLevelChunk(
        env, class, handle, x, y, z, object_id,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_rollbackReconstructionSubLevelChunk<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    object_id: jint,
) -> jboolean {
    reconstruction_section::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_rollbackReconstructionSubLevelChunk(
        env, class, handle, x, y, z, object_id,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_removeSubLevelChunk<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    object_id: jint,
) -> jboolean {
    reconstruction_section::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_removeSubLevelChunk(
        env, class, handle, x, y, z, object_id,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_clearReconstructionSectionOwnershipForScene<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    reconstruction_section::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_clearReconstructionSectionOwnershipForScene(
        env, class, handle,
    )
}
