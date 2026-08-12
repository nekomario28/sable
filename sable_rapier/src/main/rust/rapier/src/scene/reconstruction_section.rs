use super::{LevelColliderID, PhysicsScene, pack_section_pos};
use crate::voxel_collider::VoxelColliderMap;
use crate::{ActiveLevelColliderInfo, get_physics_state, with_handle};
use jni::JNIEnv;
use jni::objects::{JClass, JIntArray};
use jni::sys::{jboolean, jint, jlong};
use marten::level::{
    ALL_VOXEL_PHYSICS_STATES, BlockState, CHUNK_SHIFT, ChunkSection, VoxelPhysicsState,
};
use rapier3d::glamx::IVec3;
use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum OwnershipState {
    Open,
    Committed,
}

#[derive(Clone)]
struct ReconstructionSectionOwnership {
    owner: LevelColliderID,
    expected_blocks: Vec<BlockState>,
    bounds_min: IVec3,
    bounds_max: IVec3,
    state: OwnershipState,
}

static RECONSTRUCTION_SECTIONS: OnceLock<
    RwLock<HashMap<(jlong, i64), ReconstructionSectionOwnership>>,
> = OnceLock::new();

fn reconstruction_sections(
) -> &'static RwLock<HashMap<(jlong, i64), ReconstructionSectionOwnership>> {
    RECONSTRUCTION_SECTIONS.get_or_init(|| RwLock::new(HashMap::new()))
}

fn decode_reconstruction_section<'local>(
    env: &JNIEnv<'local>,
    data: &JIntArray<'local>,
) -> Option<(ChunkSection, Vec<BlockState>)> {
    if env.get_array_length(data).ok()? != 4096 {
        return None;
    }

    let mut ints: [jint; 4096] = [0; 4096];
    env.get_int_array_region(data, 0, &mut ints).ok()?;

    let mut blocks = Vec::with_capacity(ints.len());
    for block in ints {
        let block_collider_id = (block >> 16) as u16;
        let voxel_state_id = (block & 0xFFFF) as usize;
        if voxel_state_id >= ALL_VOXEL_PHYSICS_STATES.len() {
            return None;
        }
        blocks.push((
            block_collider_id as u32,
            ALL_VOXEL_PHYSICS_STATES[voxel_state_id],
        ));
    }

    let expected_blocks = blocks.clone();
    Some((ChunkSection::new(blocks), expected_blocks))
}

fn collect_section_blocks(chunk: &ChunkSection) -> Vec<BlockState> {
    let mut blocks = Vec::with_capacity(4096);
    for by in 0..16 {
        for bz in 0..16 {
            for bx in 0..16 {
                blocks.push(chunk.get_block(bx, by, bz));
            }
        }
    }
    blocks
}

fn valid_block_colliders(blocks: &[BlockState], collider_map: &VoxelColliderMap) -> bool {
    blocks.iter().all(|block| {
        block.0 == 0
            || collider_map
                .voxel_colliders
                .get(block.0 as usize - 1)
                .and_then(Option::as_ref)
                .is_some()
    })
}

fn state_is_solid(state: &BlockState, collider_map: &VoxelColliderMap) -> bool {
    if state.0 == 0
        || state.1 == VoxelPhysicsState::Interior
        || state.1 == VoxelPhysicsState::Empty
    {
        return false;
    }

    collider_map
        .voxel_colliders
        .get(state.0 as usize - 1)
        .and_then(Option::as_ref)
        .is_some_and(|collider| !collider.collision_boxes.is_empty())
}

fn body_bounds(body: &ActiveLevelColliderInfo) -> Option<(IVec3, IVec3)> {
    body.octree.as_ref()?;
    Some((body.local_bounds_min?, body.local_bounds_max?))
}

fn block_global_position(x: i32, y: i32, z: i32, index: usize) -> IVec3 {
    let bx = (index & 15) as i32;
    let bz = ((index >> 4) & 15) as i32;
    let by = ((index >> 8) & 15) as i32;
    IVec3::new(
        (x << CHUNK_SHIFT) + bx,
        (y << CHUNK_SHIFT) + by,
        (z << CHUNK_SHIFT) + bz,
    )
}

fn solid_blocks_fit_bounds(
    blocks: &[BlockState],
    bounds_min: IVec3,
    bounds_max: IVec3,
    x: i32,
    y: i32,
    z: i32,
    collider_map: &VoxelColliderMap,
) -> bool {
    blocks.iter().enumerate().all(|(index, state)| {
        if !state_is_solid(state, collider_map) {
            return true;
        }

        let pos = block_global_position(x, y, z, index);
        pos.x >= bounds_min.x
            && pos.y >= bounds_min.y
            && pos.z >= bounds_min.z
            && pos.x <= bounds_max.x
            && pos.y <= bounds_max.y
            && pos.z <= bounds_max.z
    })
}

fn octree_block(body: &ActiveLevelColliderInfo, global: IVec3) -> Option<i32> {
    let bounds_min = body.local_bounds_min?;
    let octree = body.octree.as_ref()?;
    Some(octree.query(
        global.x - bounds_min.x,
        global.y - bounds_min.y,
        global.z - bounds_min.z,
        0,
    ))
}

fn body_section_is_empty(
    body: &ActiveLevelColliderInfo,
    x: i32,
    y: i32,
    z: i32,
) -> bool {
    (0..4096).all(|index| {
        octree_block(body, block_global_position(x, y, z, index)) == Some(-2)
    })
}

fn body_section_matches(
    body: &ActiveLevelColliderInfo,
    x: i32,
    y: i32,
    z: i32,
    blocks: &[BlockState],
    collider_map: &VoxelColliderMap,
) -> bool {
    if blocks.len() != 4096 {
        return false;
    }

    blocks.iter().enumerate().all(|(index, state)| {
        let expected = if state_is_solid(state, collider_map) {
            state.0 as i32
        } else {
            -2
        };
        octree_block(body, block_global_position(x, y, z, index)) == Some(expected)
    })
}

fn section_matches(chunk: &ChunkSection, expected_blocks: &[BlockState]) -> bool {
    if expected_blocks.len() != 4096 {
        return false;
    }
    collect_section_blocks(chunk) == expected_blocks
}

fn clear_inserted_solids(
    body: &mut ActiveLevelColliderInfo,
    x: i32,
    y: i32,
    z: i32,
    blocks: &[BlockState],
    collider_map: &VoxelColliderMap,
) {
    let empty: BlockState = (0, VoxelPhysicsState::Empty);
    for (index, state) in blocks.iter().enumerate() {
        if !state_is_solid(state, collider_map) {
            continue;
        }
        let pos = block_global_position(x, y, z, index);
        body.insert_block(pos.x, pos.y, pos.z, &empty, true, collider_map);
    }
}

fn ownership_matches(
    scene: &PhysicsScene,
    section_key: i64,
    x: i32,
    y: i32,
    z: i32,
    ownership: &ReconstructionSectionOwnership,
) -> bool {
    let physics_state = get_physics_state();
    let collider_map = &physics_state.voxel_collider_map;
    let sable_data = scene.sable_data.read().unwrap();

    let Some(body) = sable_data.level_colliders.get(&ownership.owner) else {
        return false;
    };
    let Some((bounds_min, bounds_max)) = body_bounds(body) else {
        return false;
    };
    if bounds_min != ownership.bounds_min || bounds_max != ownership.bounds_max {
        return false;
    }
    if !solid_blocks_fit_bounds(
        &ownership.expected_blocks,
        bounds_min,
        bounds_max,
        x,
        y,
        z,
        collider_map,
    ) || !body_section_matches(
        body,
        x,
        y,
        z,
        &ownership.expected_blocks,
        collider_map,
    ) {
        return false;
    }

    let Some(chunk) = sable_data.main_level_chunks.get(&section_key) else {
        return false;
    };
    section_matches(chunk, &ownership.expected_blocks)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_acquireReconstructionSubLevelChunk<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    data: JIntArray<'local>,
    object_id: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 {
        return 0;
    }

    let Some((chunk, expected_blocks)) = decode_reconstruction_section(&env, &data) else {
        return 0;
    };
    let section_key = pack_section_pos(x, y, z);
    let registry_key = (handle, section_key);
    let owner = object_id as LevelColliderID;

    let mut ownerships = reconstruction_sections().write().unwrap();
    if ownerships.contains_key(&registry_key) || ownerships.try_reserve(1).is_err() {
        return 0;
    }

    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        if !valid_block_colliders(&expected_blocks, collider_map) {
            return 0;
        }

        let mut sable_data = scene.sable_data.write().unwrap();
        if sable_data.main_level_chunks.contains_key(&section_key)
            || sable_data.main_level_chunks.try_reserve(1).is_err()
        {
            return 0;
        }

        let Some(body) = sable_data.level_colliders.get(&owner) else {
            return 0;
        };
        let Some((bounds_min, bounds_max)) = body_bounds(body) else {
            return 0;
        };
        if !solid_blocks_fit_bounds(
            &expected_blocks,
            bounds_min,
            bounds_max,
            x,
            y,
            z,
            collider_map,
        ) || !body_section_is_empty(body, x, y, z)
        {
            return 0;
        }

        let body = sable_data.level_colliders.get_mut(&owner).unwrap();
        body.insert_chunk(&chunk, x, y, z, collider_map);
        if !body_section_matches(body, x, y, z, &expected_blocks, collider_map) {
            clear_inserted_solids(body, x, y, z, &expected_blocks, collider_map);
            return 0;
        }

        sable_data.main_level_chunks.insert(section_key, chunk);
        ownerships.insert(
            registry_key,
            ReconstructionSectionOwnership {
                owner,
                expected_blocks,
                bounds_min,
                bounds_max,
                state: OwnershipState::Open,
            },
        );
        1
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_verifyReconstructionSubLevelChunk<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    object_id: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 {
        return 0;
    }

    let section_key = pack_section_pos(x, y, z);
    let ownerships = reconstruction_sections().read().unwrap();
    let Some(ownership) = ownerships.get(&(handle, section_key)) else {
        return 0;
    };
    if ownership.owner != object_id as LevelColliderID || ownership.state != OwnershipState::Open {
        return 0;
    }

    with_handle(handle, |scene| {
        ownership_matches(scene, section_key, x, y, z, ownership) as jboolean
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_commitReconstructionSubLevelChunk<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    object_id: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 {
        return 0;
    }

    let section_key = pack_section_pos(x, y, z);
    let registry_key = (handle, section_key);
    let mut ownerships = reconstruction_sections().write().unwrap();
    let Some(ownership) = ownerships.get(&registry_key) else {
        return 0;
    };
    if ownership.owner != object_id as LevelColliderID || ownership.state != OwnershipState::Open {
        return 0;
    }
    if !with_handle(handle, |scene| {
        ownership_matches(scene, section_key, x, y, z, ownership)
    }) {
        return 0;
    }

    ownerships.get_mut(&registry_key).unwrap().state = OwnershipState::Committed;
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_rollbackReconstructionSubLevelChunk<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    object_id: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 {
        return 0;
    }

    let section_key = pack_section_pos(x, y, z);
    let registry_key = (handle, section_key);
    let owner = object_id as LevelColliderID;
    let mut ownerships = reconstruction_sections().write().unwrap();
    let Some(ownership) = ownerships.get(&registry_key) else {
        return 0;
    };
    if ownership.owner != owner || ownership.state != OwnershipState::Open {
        return 0;
    }

    let restored = with_handle(handle, |scene| {
        if !ownership_matches(scene, section_key, x, y, z, ownership) {
            return false;
        }

        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();
        let body = sable_data.level_colliders.get_mut(&owner).unwrap();
        clear_inserted_solids(
            body,
            x,
            y,
            z,
            &ownership.expected_blocks,
            collider_map,
        );
        sable_data.main_level_chunks.remove(&section_key);

        let body = sable_data.level_colliders.get(&owner).unwrap();
        body_section_is_empty(body, x, y, z)
            && !sable_data.main_level_chunks.contains_key(&section_key)
    });
    if !restored {
        return 0;
    }

    ownerships.remove(&registry_key);
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_removeSubLevelChunk<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    x: jint,
    y: jint,
    z: jint,
    object_id: jint,
) -> jboolean {
    if handle == 0 || object_id < 0 {
        return 0;
    }

    let section_key = pack_section_pos(x, y, z);
    let registry_key = (handle, section_key);
    let owner = object_id as LevelColliderID;
    let mut ownerships = reconstruction_sections().write().unwrap();
    if let Some(ownership) = ownerships.get(&registry_key) {
        if ownership.owner != owner || ownership.state != OwnershipState::Committed {
            return 0;
        }
    }

    let removed = with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();

        let Some(chunk) = sable_data.main_level_chunks.get(&section_key) else {
            return false;
        };
        let current_blocks = collect_section_blocks(chunk);
        if !valid_block_colliders(&current_blocks, collider_map) {
            return false;
        }

        let Some(body) = sable_data.level_colliders.get(&owner) else {
            return false;
        };
        let Some((bounds_min, bounds_max)) = body_bounds(body) else {
            return false;
        };
        if !solid_blocks_fit_bounds(
            &current_blocks,
            bounds_min,
            bounds_max,
            x,
            y,
            z,
            collider_map,
        ) || !body_section_matches(body, x, y, z, &current_blocks, collider_map)
        {
            return false;
        }

        if !current_blocks.iter().any(|state| state_is_solid(state, collider_map))
            && !matches!(
                ownerships.get(&registry_key),
                Some(ReconstructionSectionOwnership {
                    owner: recorded_owner,
                    state: OwnershipState::Committed,
                    ..
                }) if *recorded_owner == owner
            )
        {
            return false;
        }

        let body = sable_data.level_colliders.get_mut(&owner).unwrap();
        clear_inserted_solids(body, x, y, z, &current_blocks, collider_map);
        sable_data.main_level_chunks.remove(&section_key);

        let body = sable_data.level_colliders.get(&owner).unwrap();
        body_section_is_empty(body, x, y, z)
            && !sable_data.main_level_chunks.contains_key(&section_key)
    });
    if !removed {
        return 0;
    }

    ownerships.remove(&registry_key);
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_clearReconstructionSectionOwnershipForScene<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    if handle == 0 {
        return 0;
    }

    let mut ownerships = reconstruction_sections().write().unwrap();
    if ownerships
        .iter()
        .any(|((scene_handle, _), ownership)| {
            *scene_handle == handle && ownership.state == OwnershipState::Open
        })
    {
        return 0;
    }

    ownerships.retain(|(scene_handle, _), _| *scene_handle != handle);
    1
}
