use super::{ChunkMap, LevelColliderID, PhysicsScene, pack_section_pos};
use crate::collider::{LevelCollider, update_collider_aabb};
use crate::groups::LEVEL_GROUP;
use crate::{ActiveLevelColliderInfo, with_handle};
use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray, JIntArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use rapier3d::glamx::{DVec3, IVec3, Quat};
use rapier3d::prelude::*;
use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

#[derive(Clone)]
struct ReconstructionBodyOwnership {
    owner: LevelColliderID,
    body_handle: RigidBodyHandle,
    collider_handle: ColliderHandle,
    expected_pose: [Real; 7],
    expected_mass_properties: MassProperties,
    expected_center_of_mass: DVec3,
    expected_bounds_min: IVec3,
    expected_bounds_max: IVec3,
    plot_section_min: IVec3,
    plot_section_max: IVec3,
}

static RECONSTRUCTION_BODIES: OnceLock<
    RwLock<HashMap<(jlong, LevelColliderID), ReconstructionBodyOwnership>>,
> = OnceLock::new();

fn reconstruction_bodies(
) -> &'static RwLock<HashMap<(jlong, LevelColliderID), ReconstructionBodyOwnership>> {
    RECONSTRUCTION_BODIES.get_or_init(|| RwLock::new(HashMap::new()))
}

fn read_double_array<const N: usize>(
    env: &JNIEnv<'_>,
    data: &JDoubleArray<'_>,
) -> Option<[jdouble; N]> {
    if env.get_array_length(data).ok()? != N as i32 {
        return None;
    }
    let mut values = [0.0; N];
    env.get_double_array_region(data, 0, &mut values).ok()?;
    values.iter().all(|value| value.is_finite()).then_some(values)
}

fn read_box(env: &JNIEnv<'_>, data: &JIntArray<'_>) -> Option<(IVec3, IVec3)> {
    if env.get_array_length(data).ok()? != 6 {
        return None;
    }
    let mut values = [0; 6];
    env.get_int_array_region(data, 0, &mut values).ok()?;
    let min = IVec3::new(values[0], values[1], values[2]);
    let max = IVec3::new(values[3], values[4], values[5]);
    (min.x <= max.x && min.y <= max.y && min.z <= max.z).then_some((min, max))
}

fn plot_section_state_empty(
    scene: &PhysicsScene,
    section_min: IVec3,
    section_max: IVec3,
) -> bool {
    let sable_data = scene.sable_data.read().unwrap();
    for x in section_min.x..=section_max.x {
        for y in section_min.y..=section_max.y {
            for z in section_min.z..=section_max.z {
                if sable_data.main_level_chunks.contains_key(&pack_section_pos(x, y, z)) {
                    return false;
                }
            }
        }
    }
    true
}

fn pose_matches(body: &RigidBody, expected: &[Real; 7]) -> bool {
    let translation = body.translation();
    let rotation = body.rotation();
    translation.x == expected[0]
        && translation.y == expected[1]
        && translation.z == expected[2]
        && rotation.x == expected[3]
        && rotation.y == expected[4]
        && rotation.z == expected[5]
        && rotation.w == expected[6]
}

fn body_matches(scene: &PhysicsScene, ownership: &ReconstructionBodyOwnership) -> bool {
    let sable_data = scene.sable_data.read().unwrap();
    let Some(mapped_body) = sable_data.rigid_bodies.get(&ownership.owner) else {
        return false;
    };
    if *mapped_body != ownership.body_handle {
        return false;
    }
    let Some(info) = sable_data.level_colliders.get(&ownership.owner) else {
        return false;
    };
    if info.collider != ownership.collider_handle
        || info.static_mount.is_some()
        || info.fake_velocities.is_some()
        || info.chunk_map.is_some()
        || info.local_bounds_min != Some(ownership.expected_bounds_min)
        || info.local_bounds_max != Some(ownership.expected_bounds_max)
        || info.center_of_mass != Some(ownership.expected_center_of_mass)
        || info.octree.is_none()
    {
        return false;
    }

    let sim_data = scene.sim_data.read().unwrap();
    let Some(body) = sim_data.rigid_body_set.get(ownership.body_handle) else {
        return false;
    };
    if body.is_enabled()
        || !body.is_dynamic()
        || !body.is_ccd_enabled()
        || !pose_matches(body, &ownership.expected_pose)
        || body.mass_properties().local_mprops != ownership.expected_mass_properties
        || body.colliders() != [ownership.collider_handle]
    {
        return false;
    }
    let Some(collider) = sim_data.collider_set.get(ownership.collider_handle) else {
        return false;
    };
    collider.parent() == Some(ownership.body_handle)
}

fn remove_owned_body(scene: &PhysicsScene, ownership: &ReconstructionBodyOwnership) -> bool {
    {
        let sable_data = scene.sable_data.read().unwrap();
        if sable_data.rigid_bodies.get(&ownership.owner) != Some(&ownership.body_handle)
            || sable_data
                .level_colliders
                .get(&ownership.owner)
                .map(|info| info.collider)
                != Some(ownership.collider_handle)
        {
            return false;
        }
        let sim_data = scene.sim_data.read().unwrap();
        if sim_data.rigid_body_set.get(ownership.body_handle).is_none()
            || sim_data.collider_set.get(ownership.collider_handle).is_none()
        {
            return false;
        }
    }

    {
        let mut sim_data = scene.sim_data.write().unwrap();
        let sim_data = &mut *sim_data;
        if sim_data
            .rigid_body_set
            .remove(
                ownership.body_handle,
                &mut sim_data.island_manager,
                &mut sim_data.collider_set,
                &mut sim_data.impulse_joint_set,
                &mut sim_data.multibody_joint_set,
                true,
            )
            .is_none()
        {
            return false;
        }
        if sim_data.rigid_body_set.get(ownership.body_handle).is_some()
            || sim_data.collider_set.get(ownership.collider_handle).is_some()
        {
            return false;
        }
    }

    {
        let mut sable_data = scene.sable_data.write().unwrap();
        if sable_data.rigid_bodies.remove(&ownership.owner) != Some(ownership.body_handle) {
            return false;
        }
        let Some(info) = sable_data.level_colliders.remove(&ownership.owner) else {
            return false;
        };
        if info.collider != ownership.collider_handle {
            return false;
        }
    }

    let sable_data = scene.sable_data.read().unwrap();
    !sable_data.rigid_bodies.contains_key(&ownership.owner)
        && !sable_data.level_colliders.contains_key(&ownership.owner)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_acquireReconstructionSubLevelBody<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    object_id: jint,
    pose: JDoubleArray<'local>,
    mass: jdouble,
    center_of_mass: JDoubleArray<'local>,
    inertia: JDoubleArray<'local>,
    bounds: JIntArray<'local>,
    plot_sections: JIntArray<'local>,
) -> jboolean {
    if handle == 0 || object_id < 0 || !mass.is_finite() || mass <= 0.0 {
        return 0;
    }

    let Some(pose_arr) = read_double_array::<7>(&env, &pose) else {
        return 0;
    };
    let Some(center_arr) = read_double_array::<3>(&env, &center_of_mass) else {
        return 0;
    };
    let Some(inertia_arr) = read_double_array::<9>(&env, &inertia) else {
        return 0;
    };
    let Some((bounds_min, bounds_max)) = read_box(&env, &bounds) else {
        return 0;
    };
    let Some((plot_section_min, plot_section_max)) = read_box(&env, &plot_sections) else {
        return 0;
    };

    let quat_len_sq = pose_arr[3] * pose_arr[3]
        + pose_arr[4] * pose_arr[4]
        + pose_arr[5] * pose_arr[5]
        + pose_arr[6] * pose_arr[6];
    if !quat_len_sq.is_finite() || (quat_len_sq - 1.0).abs() > 1.0e-6 {
        return 0;
    }

    let expected_pose = [
        pose_arr[0] as Real,
        pose_arr[1] as Real,
        pose_arr[2] as Real,
        pose_arr[3] as Real,
        pose_arr[4] as Real,
        pose_arr[5] as Real,
        pose_arr[6] as Real,
    ];
    let expected_center_of_mass = DVec3::new(center_arr[0], center_arr[1], center_arr[2]);
    let inertia_tensor = Mat3::from_cols(
        Vec3::new(
            inertia_arr[0] as Real,
            inertia_arr[1] as Real,
            inertia_arr[2] as Real,
        ),
        Vec3::new(
            inertia_arr[3] as Real,
            inertia_arr[4] as Real,
            inertia_arr[5] as Real,
        ),
        Vec3::new(
            inertia_arr[6] as Real,
            inertia_arr[7] as Real,
            inertia_arr[8] as Real,
        ),
    );
    let expected_mass_properties = MassProperties::with_inertia_matrix(
        Vec3::ZERO,
        mass as Real,
        inertia_tensor.into(),
    );
    let owner = object_id as LevelColliderID;
    let registry_key = (handle, owner);

    let mut ownerships = reconstruction_bodies().write().unwrap();
    if ownerships.contains_key(&registry_key) || ownerships.try_reserve(1).is_err() {
        return 0;
    }

    let acquired = with_handle(handle, |scene| {
        if !plot_section_state_empty(scene, plot_section_min, plot_section_max) {
            return None;
        }

        let physics_state = crate::get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;

        {
            let mut sable_data = scene.sable_data.write().unwrap();
            if sable_data.rigid_bodies.contains_key(&owner)
                || sable_data.level_colliders.contains_key(&owner)
                || sable_data.rigid_bodies.try_reserve(1).is_err()
                || sable_data.level_colliders.try_reserve(1).is_err()
            {
                return None;
            }
        }

        let quat = Quat::from_xyzw(
            expected_pose[3],
            expected_pose[4],
            expected_pose[5],
            expected_pose[6],
        );
        let mut rigid_body = RigidBodyBuilder::dynamic()
            .ccd_enabled(true)
            .translation(Vec3::new(expected_pose[0], expected_pose[1], expected_pose[2]))
            .build();
        rigid_body.set_rotation(quat, false);
        rigid_body.set_enabled(false);
        let activation_params = rigid_body.activation_mut();
        activation_params.angular_threshold = 0.15;
        activation_params.normalized_linear_threshold = 0.15;
        rigid_body.set_linear_damping(scene.universal_drag);
        rigid_body.set_angular_damping(scene.universal_drag);
        rigid_body.enable_gyroscopic_forces(true);

        let (body_handle, collider_handle) = {
            let mut sim_data = scene.sim_data.write().unwrap();
            let body_handle = sim_data.rigid_body_set.insert(rigid_body);
            let collider = ColliderBuilder::new(SharedShape::new(LevelCollider::new(Some(owner), false)))
                .friction(0.525)
                .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
                .active_hooks(ActiveHooks::MODIFY_SOLVER_CONTACTS)
                .density(0.0)
                .collision_groups(LEVEL_GROUP)
                .build();
            let collider_handle = sim_data.collider_set.insert_with_parent(
                collider,
                body_handle,
                &mut sim_data.rigid_body_set,
            );

            {
                let sim_data = &mut *sim_data;
                let body = sim_data.rigid_body_set.get_mut(body_handle).unwrap();
                body.set_additional_mass_properties(expected_mass_properties, false);
                body.recompute_mass_properties_from_colliders(&sim_data.collider_set);
            }
            (body_handle, collider_handle)
        };

        let mut info = ActiveLevelColliderInfo::new(collider_handle);
        info.center_of_mass = Some(expected_center_of_mass);
        let empty_chunks = ChunkMap::new();
        info.set_local_bounds(bounds_min, bounds_max, &empty_chunks, collider_map);
        {
            let mut sim_data = scene.sim_data.write().unwrap();
            update_collider_aabb(&mut sim_data, &mut info);
        }
        {
            let mut sable_data = scene.sable_data.write().unwrap();
            sable_data.rigid_bodies.insert(owner, body_handle);
            sable_data.level_colliders.insert(owner, info);
        }

        Some(ReconstructionBodyOwnership {
            owner,
            body_handle,
            collider_handle,
            expected_pose,
            expected_mass_properties,
            expected_center_of_mass,
            expected_bounds_min: bounds_min,
            expected_bounds_max: bounds_max,
            plot_section_min,
            plot_section_max,
        })
    });

    let Some(ownership) = acquired else {
        return 0;
    };
    if !with_handle(handle, |scene| body_matches(scene, &ownership)) {
        with_handle(handle, |scene| {
            let _ = remove_owned_body(scene, &ownership);
        });
        return 0;
    }

    ownerships.insert(registry_key, ownership);
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_verifyReconstructionSubLevelBody<'local>(
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
    with_handle(handle, |scene| body_matches(scene, ownership) as jboolean)
}

/// Commit remains deliberately unavailable until Java live-registry publication can be coupled
/// atomically with native enablement. Rollback proof is the current safety milestone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_commitReconstructionSubLevelBody<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    _handle: jlong,
    _object_id: jint,
) -> jboolean {
    0
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_rollbackReconstructionSubLevelBody<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    object_id: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 {
        return 0;
    }
    let owner = object_id as LevelColliderID;

    let mut ownerships = reconstruction_bodies().write().unwrap();
    let Some(ownership) = ownerships.get(&(handle, owner)) else {
        return 0;
    };
    if !with_handle(handle, |scene| body_matches(scene, ownership)) {
        return 0;
    }
    if !with_handle(handle, |scene| {
        plot_section_state_empty(
            scene,
            ownership.plot_section_min,
            ownership.plot_section_max,
        )
    }) {
        return 0;
    }
    if !with_handle(handle, |scene| remove_owned_body(scene, ownership)) {
        return 0;
    }

    ownerships.remove(&(handle, owner));
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_clearReconstructionBodyOwnershipForScene<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    if handle == 0 {
        return 0;
    }
    let ownerships = reconstruction_bodies().read().unwrap();
    (!ownerships.keys().any(|(scene_handle, _)| *scene_handle == handle)) as jboolean
}
