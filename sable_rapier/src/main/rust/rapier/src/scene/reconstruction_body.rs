use super::{LevelColliderID, PhysicsScene, pack_section_pos};
use crate::collider::{LevelCollider, update_collider_aabb};
use crate::groups::LEVEL_GROUP;
use crate::{ActiveLevelColliderInfo, get_physics_state, with_handle};
use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use marten::Real;
use marten::level::CHUNK_SHIFT;
use rapier3d::glamx::{DVec3, IVec3, Quat};
use rapier3d::prelude::*;
use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum OwnershipState {
    Open,
    Committed,
}

#[derive(Clone, Copy, Debug)]
struct ReconstructionBodyOwnership {
    owner: LevelColliderID,
    rigid_body: RigidBodyHandle,
    collider: ColliderHandle,
    pose: [Real; 7],
    mass: Real,
    center_of_mass: DVec3,
    bounds_min: IVec3,
    bounds_max: IVec3,
    state: OwnershipState,
}

static RECONSTRUCTION_BODIES: OnceLock<
    RwLock<HashMap<(jlong, LevelColliderID), ReconstructionBodyOwnership>>,
> = OnceLock::new();

fn reconstruction_bodies(
) -> &'static RwLock<HashMap<(jlong, LevelColliderID), ReconstructionBodyOwnership>> {
    RECONSTRUCTION_BODIES.get_or_init(|| RwLock::new(HashMap::new()))
}

fn read_array<const N: usize>(env: &JNIEnv<'_>, array: &JDoubleArray<'_>) -> Option<[jdouble; N]> {
    if env.get_array_length(array).ok()? != N as i32 {
        return None;
    }
    let mut values = [0.0; N];
    env.get_double_array_region(array, 0, &mut values).ok()?;
    Some(values)
}

fn finite(values: &[jdouble]) -> bool {
    values.iter().all(|value| value.is_finite())
}

fn valid_bounds(min: IVec3, max: IVec3) -> bool {
    if min.x > max.x || min.y > max.y || min.z > max.z {
        return false;
    }

    let Some(dx) = max.x.checked_sub(min.x).and_then(|value| value.checked_add(1)) else {
        return false;
    };
    let Some(dy) = max.y.checked_sub(min.y).and_then(|value| value.checked_add(1)) else {
        return false;
    };
    let Some(dz) = max.z.checked_sub(min.z).and_then(|value| value.checked_add(1)) else {
        return false;
    };
    let max_axis = dx.max(dy).max(dz) as u32;
    max_axis > 0 && max_axis.checked_next_power_of_two().is_some()
}

fn no_native_sections_in_bounds(scene: &PhysicsScene, min: IVec3, max: IVec3) -> bool {
    let chunk_min = min >> CHUNK_SHIFT;
    let chunk_max = max >> CHUNK_SHIFT;
    let sable_data = scene.sable_data.read().unwrap();

    for x in chunk_min.x..=chunk_max.x {
        for y in chunk_min.y..=chunk_max.y {
            for z in chunk_min.z..=chunk_max.z {
                if sable_data.main_level_chunks.contains_key(&pack_section_pos(x, y, z)) {
                    return false;
                }
            }
        }
    }
    true
}

fn body_matches(scene: &PhysicsScene, ownership: &ReconstructionBodyOwnership) -> bool {
    let sable_data = scene.sable_data.read().unwrap();
    let Some(rigid_body_handle) = sable_data.rigid_bodies.get(&ownership.owner) else {
        return false;
    };
    if *rigid_body_handle != ownership.rigid_body {
        return false;
    }
    let Some(info) = sable_data.level_colliders.get(&ownership.owner) else {
        return false;
    };
    if info.collider != ownership.collider
        || info.local_bounds_min != Some(ownership.bounds_min)
        || info.local_bounds_max != Some(ownership.bounds_max)
        || info.center_of_mass != Some(ownership.center_of_mass)
        || info.octree.is_none()
    {
        return false;
    }

    let sim_data = scene.sim_data.read().unwrap();
    let Some(rigid_body) = sim_data.rigid_body_set.get(ownership.rigid_body) else {
        return false;
    };
    let Some(collider) = sim_data.collider_set.get(ownership.collider) else {
        return false;
    };
    if collider.parent() != Some(ownership.rigid_body) || rigid_body.mass() != ownership.mass {
        return false;
    }

    let translation = rigid_body.translation();
    let rotation = rigid_body.rotation();
    translation.x == ownership.pose[0]
        && translation.y == ownership.pose[1]
        && translation.z == ownership.pose[2]
        && rotation.x == ownership.pose[3]
        && rotation.y == ownership.pose[4]
        && rotation.z == ownership.pose[5]
        && rotation.w == ownership.pose[6]
}

fn remove_exact_body(scene: &PhysicsScene, ownership: &ReconstructionBodyOwnership) -> bool {
    if !body_matches(scene, ownership)
        || !no_native_sections_in_bounds(scene, ownership.bounds_min, ownership.bounds_max)
    {
        return false;
    }

    let mut sable_data = scene.sable_data.write().unwrap();
    let Some(rigid_body_handle) = sable_data.rigid_bodies.get(&ownership.owner) else {
        return false;
    };
    let Some(info) = sable_data.level_colliders.get(&ownership.owner) else {
        return false;
    };
    if *rigid_body_handle != ownership.rigid_body || info.collider != ownership.collider {
        return false;
    }

    let mut sim_data = scene.sim_data.write().unwrap();
    if sim_data.rigid_body_set.get(ownership.rigid_body).is_none()
        || sim_data.collider_set.get(ownership.collider).is_none()
    {
        return false;
    }

    let removed = sim_data.rigid_body_set.remove(
        ownership.rigid_body,
        &mut sim_data.island_manager,
        &mut sim_data.collider_set,
        &mut sim_data.impulse_joint_set,
        &mut sim_data.multibody_joint_set,
        true,
    );
    if removed.is_none()
        || sim_data.rigid_body_set.get(ownership.rigid_body).is_some()
        || sim_data.collider_set.get(ownership.collider).is_some()
    {
        return false;
    }

    sable_data.rigid_bodies.remove(&ownership.owner);
    sable_data.level_colliders.remove(&ownership.owner);
    !sable_data.rigid_bodies.contains_key(&ownership.owner)
        && !sable_data.level_colliders.contains_key(&ownership.owner)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_acquireReconstructionSubLevelBody<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    object_id: jint,
    pose: JDoubleArray<'local>,
    mass: jdouble,
    center_of_mass: JDoubleArray<'local>,
    inertia: JDoubleArray<'local>,
    min_x: jint,
    min_y: jint,
    min_z: jint,
    max_x: jint,
    max_y: jint,
    max_z: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 || !mass.is_finite() || mass <= 0.0 {
        return 0;
    }

    let Some(pose_values) = read_array::<7>(&env, &pose) else {
        return 0;
    };
    let Some(center_values) = read_array::<3>(&env, &center_of_mass) else {
        return 0;
    };
    let Some(inertia_values) = read_array::<9>(&env, &inertia) else {
        return 0;
    };
    if !finite(&pose_values) || !finite(&center_values) || !finite(&inertia_values) {
        return 0;
    }

    let bounds_min = IVec3::new(min_x, min_y, min_z);
    let bounds_max = IVec3::new(max_x, max_y, max_z);
    if !valid_bounds(bounds_min, bounds_max) {
        return 0;
    }

    let owner = object_id as LevelColliderID;
    let registry_key = (handle, owner);
    let mut ownerships = reconstruction_bodies().write().unwrap();
    if ownerships.contains_key(&registry_key) || ownerships.try_reserve(1).is_err() {
        return 0;
    }

    let pose_native = [
        pose_values[0] as Real,
        pose_values[1] as Real,
        pose_values[2] as Real,
        pose_values[3] as Real,
        pose_values[4] as Real,
        pose_values[5] as Real,
        pose_values[6] as Real,
    ];
    let mass_native = mass as Real;
    let center_native = DVec3::new(center_values[0], center_values[1], center_values[2]);

    let created = with_handle(handle, |scene| {
        if !no_native_sections_in_bounds(scene, bounds_min, bounds_max) {
            return None;
        }

        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();
        if sable_data.rigid_bodies.contains_key(&owner)
            || sable_data.level_colliders.contains_key(&owner)
            || sable_data.rigid_bodies.try_reserve(1).is_err()
            || sable_data.level_colliders.try_reserve(1).is_err()
        {
            return None;
        }

        let quat = Quat::from_xyzw(
            pose_native[3],
            pose_native[4],
            pose_native[5],
            pose_native[6],
        );
        let mut rigid_body = RigidBodyBuilder::dynamic()
            .ccd_enabled(true)
            .translation(Vec3::new(pose_native[0], pose_native[1], pose_native[2]))
            .build();
        rigid_body.set_rotation(quat, false);
        let activation_params = rigid_body.activation_mut();
        activation_params.angular_threshold = 0.15;
        activation_params.normalized_linear_threshold = 0.15;
        rigid_body.set_linear_damping(scene.universal_drag);
        rigid_body.set_angular_damping(scene.universal_drag);
        rigid_body.enable_gyroscopic_forces(true);

        let inertia_tensor = Mat3::from_cols(
            Vec3::new(
                inertia_values[0] as Real,
                inertia_values[1] as Real,
                inertia_values[2] as Real,
            ),
            Vec3::new(
                inertia_values[3] as Real,
                inertia_values[4] as Real,
                inertia_values[5] as Real,
            ),
            Vec3::new(
                inertia_values[6] as Real,
                inertia_values[7] as Real,
                inertia_values[8] as Real,
            ),
        );
        rigid_body.set_additional_mass_properties(
            MassProperties::with_inertia_matrix(Vec3::ZERO, mass_native, inertia_tensor.into()),
            true,
        );

        let mut sim_data = scene.sim_data.write().unwrap();
        let rigid_body_handle = sim_data.rigid_body_set.insert(rigid_body);
        let collider = ColliderBuilder::new(SharedShape::new(LevelCollider::new(Some(owner), false)))
            .friction(0.525)
            .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
            .active_hooks(ActiveHooks::MODIFY_SOLVER_CONTACTS)
            .density(0.0)
            .collision_groups(LEVEL_GROUP)
            .build();
        let collider_handle = sim_data.collider_set.insert_with_parent(
            collider,
            rigid_body_handle,
            &mut sim_data.rigid_body_set,
        );

        let mut info = ActiveLevelColliderInfo::new(collider_handle);
        info.center_of_mass = Some(center_native);
        info.set_local_bounds(bounds_min, bounds_max, &sable_data.main_level_chunks, collider_map);
        update_collider_aabb(&mut sim_data, &info);

        sable_data.rigid_bodies.insert(owner, rigid_body_handle);
        sable_data.level_colliders.insert(owner, info);
        Some((rigid_body_handle, collider_handle))
    });

    let Some((rigid_body_handle, collider_handle)) = created else {
        return 0;
    };
    let ownership = ReconstructionBodyOwnership {
        owner,
        rigid_body: rigid_body_handle,
        collider: collider_handle,
        pose: pose_native,
        mass: mass_native,
        center_of_mass: center_native,
        bounds_min,
        bounds_max,
        state: OwnershipState::Open,
    };

    let verified = with_handle(handle, |scene| body_matches(scene, &ownership));
    if !verified {
        let restored = with_handle(handle, |scene| remove_exact_body(scene, &ownership));
        if !restored {
            return 0;
        }
        return 0;
    }

    ownerships.insert(registry_key, ownership);
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_verifyReconstructionSubLevelBody<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    object_id: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 {
        return 0;
    }
    let owner = object_id as LevelColliderID;
    let ownerships = reconstruction_bodies().read().unwrap();
    let Some(ownership) = ownerships.get(&(handle, owner)) else {
        return 0;
    };
    if ownership.state != OwnershipState::Open {
        return 0;
    }
    with_handle(handle, |scene| body_matches(scene, ownership) as jboolean)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_commitReconstructionSubLevelBody<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    object_id: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 {
        return 0;
    }
    let owner = object_id as LevelColliderID;
    let registry_key = (handle, owner);
    let mut ownerships = reconstruction_bodies().write().unwrap();
    let Some(ownership) = ownerships.get(&registry_key) else {
        return 0;
    };
    if ownership.state != OwnershipState::Open
        || !with_handle(handle, |scene| body_matches(scene, ownership))
    {
        return 0;
    }
    ownerships.get_mut(&registry_key).unwrap().state = OwnershipState::Committed;
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_rollbackReconstructionSubLevelBody<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    object_id: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 {
        return 0;
    }
    let owner = object_id as LevelColliderID;
    let registry_key = (handle, owner);
    let mut ownerships = reconstruction_bodies().write().unwrap();
    let Some(ownership) = ownerships.get(&registry_key).copied() else {
        return 0;
    };
    if ownership.state != OwnershipState::Open {
        return 0;
    }
    if !with_handle(handle, |scene| remove_exact_body(scene, &ownership)) {
        return 0;
    }
    ownerships.remove(&registry_key);
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_RapierReconstructionNative_clearReconstructionBodyOwnershipForScene<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    if handle == 0 {
        return 0;
    }
    let mut ownerships = reconstruction_bodies().write().unwrap();
    if ownerships.iter().any(|((scene_handle, _), ownership)| {
        *scene_handle == handle && ownership.state == OwnershipState::Open
    }) {
        return 0;
    }
    ownerships.retain(|(scene_handle, _), _| *scene_handle != handle);
    1
}
