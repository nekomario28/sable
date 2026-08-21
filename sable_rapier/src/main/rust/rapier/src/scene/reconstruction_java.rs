use super::{reconstruction_body, reconstruction_section};
use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray, JIntArray};
use jni::sys::{jboolean, jdouble, jint, jlong};

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_acquireReconstructionSubLevelBody<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
    object_id: jint,
    pose: JDoubleArray<'local>,
    mass: jdouble,
    center_of_mass: JDoubleArray<'local>,
    inertia: JDoubleArray<'local>,
    bounds: JIntArray<'local>,
    plot_sections: JIntArray<'local>,
) -> jboolean {
    reconstruction_body::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_acquireReconstructionSubLevelBody(
        env,
        class,
        handle,
        object_id,
        pose,
        mass,
        center_of_mass,
        inertia,
        bounds,
        plot_sections,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_verifyReconstructionSubLevelBody<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
    object_id: jint,
) -> jboolean {
    reconstruction_body::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_verifyReconstructionSubLevelBody(
        env, class, handle, object_id,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_commitReconstructionSubLevelBody<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
    object_id: jint,
) -> jboolean {
    reconstruction_body::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_commitReconstructionSubLevelBody(
        env, class, handle, object_id,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_rollbackReconstructionSubLevelBody<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
    object_id: jint,
) -> jboolean {
    reconstruction_body::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_rollbackReconstructionSubLevelBody(
        env, class, handle, object_id,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_clearReconstructionBodyOwnershipForScene<'local>(
    env: JNIEnv<'local>,
    class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    reconstruction_body::Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_clearReconstructionBodyOwnershipForScene(
        env, class, handle,
    )
}

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
