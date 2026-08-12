use crate::event_handler::SableEventHandler;
use crate::hooks::SablePhysicsHooks;
use crate::joints::SableJointSet;
use crate::rope::RopeMap;
use crate::voxel_collider::VoxelColliderMap;
use crate::{get_physics_state, with_handle, ActiveLevelColliderInfo, ReportedCollision};
use dashmap::DashMap;
use jni::objects::{JClass, JIntArray};
use jni::sys::{jboolean, jint, jlong};
use jni::{JNIEnv, JavaVM};
use marten::level::{
    BlockState, ChunkSection, OctreeChunkSection, VoxelPhysicsState, ALL_VOXEL_PHYSICS_STATES,
    CHUNK_SHIFT,
};
use marten::Real;
use rapier3d::dynamics::{
    CCDSolver, ImpulseJointSet, IslandManager, MultibodyJointSet, RigidBodyHandle, RigidBodySet,
};
use rapier3d::geometry::{ColliderSet, DefaultBroadPhase, NarrowPhase};
use rapier3d::glamx::IVec3;
use rapier3d::math::Vec3;
use rapier3d::pipeline::PhysicsPipeline;
use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock, RwLock};

pub type LevelColliderID = usize;

pub trait ChunkAccess {
    #[allow(unused)]
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection>;
    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection>;
}

#[inline(always)]
pub fn pack_section_pos(i: i32, j: i32, k: i32) -> i64 {
    let mut l: i64 = 0;
    l |= (i as i64 & 4194303i64) << 42;
    l |= j as i64 & 1048575i64;
    l | (k as i64 & 4194303i64) << 20
}

pub type ChunkMap = HashMap<i64, ChunkSection>;

#[derive(Clone)]
struct ReconstructionSectionOwnership {
    owner: LevelColliderID,
    expected_blocks: Vec<BlockState>,
    bounds_min: IVec3,
    bounds_max: IVec3,
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

fn valid_block_colliders(blocks: &[BlockState], collider_map: &VoxelColliderMap) -> bool {
    blocks.iter().all(|block| {
        if block.0 == 0 {
            return true;
        }
        collider_map
            .voxel_colliders
            .get(block.0 as usize - 1)
            .and_then(Option::as_ref)
            .is_some()
    })
}

fn section_body_bounds(
    body: &ActiveLevelColliderInfo,
    x: i32,
    y: i32,
    z: i32,
) -> Option<(IVec3, IVec3)> {
    let bounds_min = body.local_bounds_min?;
    let bounds_max = body.local_bounds_max?;
    body.octree.as_ref()?;

    let section_min = IVec3::new(x << CHUNK_SHIFT, y << CHUNK_SHIFT, z << CHUNK_SHIFT);
    let section_max = section_min + IVec3::splat(15);
    if section_min.x < bounds_min.x
        || section_min.y < bounds_min.y
        || section_min.z < bounds_min.z
        || section_max.x > bounds_max.x
        || section_max.y > bounds_max.y
        || section_max.z > bounds_max.z
    {
        return None;
    }

    Some((bounds_min, bounds_max))
}

fn section_matches(chunk: &ChunkSection, expected_blocks: &[BlockState]) -> bool {
    if expected_blocks.len() != 4096 {
        return false;
    }

    let mut index = 0;
    for y in 0..16 {
        for z in 0..16 {
            for x in 0..16 {
                if chunk.get_block(x, y, z) != expected_blocks[index] {
                    return false;
                }
                index += 1;
            }
        }
    }
    true
}

fn ownership_matches(
    scene: &PhysicsScene,
    section_key: i64,
    ownership: &ReconstructionSectionOwnership,
) -> bool {
    let sable_data = scene.sable_data.read().unwrap();
    let Some(body) = sable_data.level_colliders.get(&ownership.owner) else {
        return false;
    };
    let Some((bounds_min, bounds_max)) = section_body_bounds(body, 0, 0, 0).map(|_| {
        (
            body.local_bounds_min.unwrap(),
            body.local_bounds_max.unwrap(),
        )
    }) else {
        return false;
    };
    if bounds_min != ownership.bounds_min || bounds_max != ownership.bounds_max {
        return false;
    }
    let Some(chunk) = sable_data.main_level_chunks.get(&section_key) else {
        return false;
    };
    section_matches(chunk, &ownership.expected_blocks)
}

fn clear_body_section(
    body: &mut ActiveLevelColliderInfo,
    x: i32,
    y: i32,
    z: i32,
    collider_map: &VoxelColliderMap,
) {
    let empty: BlockState = (0, VoxelPhysicsState::Empty);
    for bx in 0..16 {
        for by in 0..16 {
            for bz in 0..16 {
                body.insert_block(
                    bx + (x << CHUNK_SHIFT),
                    by + (y << CHUNK_SHIFT),
                    bz + (z << CHUNK_SHIFT),
                    &empty,
                    true,
                    collider_map,
                );
            }
        }
    }
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
    let Some((chunk, expected_blocks)) = decode_reconstruction_section(&env, &data) else {
        return 0;
    };
    let section_key = pack_section_pos(x, y, z);
    let registry_key = (handle, section_key);
    let owner = object_id as LevelColliderID;
    let mut ownerships = reconstruction_sections().write().unwrap();
    if ownerships.contains_key(&registry_key) {
        return 0;
    }

    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        if !valid_block_colliders(&expected_blocks, collider_map) {
            return 0;
        }

        let mut sable_data = scene.sable_data.write().unwrap();
        if sable_data.main_level_chunks.contains_key(&section_key) {
            return 0;
        }

        let Some((bounds_min, bounds_max)) = sable_data
            .level_colliders
            .get(&owner)
            .and_then(|body| section_body_bounds(body, x, y, z))
        else {
            return 0;
        };

        let body = sable_data.level_colliders.get_mut(&owner).unwrap();
        body.insert_chunk(&chunk, x, y, z, collider_map);
        sable_data.main_level_chunks.insert(section_key, chunk);
        ownerships.insert(
            registry_key,
            ReconstructionSectionOwnership {
                owner,
                expected_blocks,
                bounds_min,
                bounds_max,
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
    let section_key = pack_section_pos(x, y, z);
    let registry_key = (handle, section_key);
    let ownerships = reconstruction_sections().read().unwrap();
    let Some(ownership) = ownerships.get(&registry_key) else {
        return 0;
    };
    if ownership.owner != object_id as LevelColliderID {
        return 0;
    }

    with_handle(handle, |scene| {
        if ownership_matches(scene, section_key, ownership) {
            1
        } else {
            0
        }
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
    let section_key = pack_section_pos(x, y, z);
    let registry_key = (handle, section_key);
    let mut ownerships = reconstruction_sections().write().unwrap();
    let Some(ownership) = ownerships.get(&registry_key) else {
        return 0;
    };
    if ownership.owner != object_id as LevelColliderID {
        return 0;
    }
    let verified = with_handle(handle, |scene| ownership_matches(scene, section_key, ownership));
    if !verified {
        return 0;
    }

    ownerships.remove(&registry_key);
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
    let section_key = pack_section_pos(x, y, z);
    let registry_key = (handle, section_key);
    let owner = object_id as LevelColliderID;
    let mut ownerships = reconstruction_sections().write().unwrap();
    let Some(ownership) = ownerships.get(&registry_key) else {
        return 0;
    };
    if ownership.owner != owner {
        return 0;
    }

    let restored = with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();

        let Some(body) = sable_data.level_colliders.get(&owner) else {
            return false;
        };
        let Some((bounds_min, bounds_max)) = section_body_bounds(body, x, y, z) else {
            return false;
        };
        if bounds_min != ownership.bounds_min || bounds_max != ownership.bounds_max {
            return false;
        }
        let Some(chunk) = sable_data.main_level_chunks.get(&section_key) else {
            return false;
        };
        if !section_matches(chunk, &ownership.expected_blocks) {
            return false;
        }

        let body = sable_data.level_colliders.get_mut(&owner).unwrap();
        clear_body_section(body, x, y, z, collider_map);
        sable_data.main_level_chunks.remove(&section_key);
        true
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
    let section_key = pack_section_pos(x, y, z);
    let registry_key = (handle, section_key);
    let ownerships = reconstruction_sections().read().unwrap();
    if ownerships.contains_key(&registry_key) {
        return 0;
    }

    with_handle(handle, |scene| {
        let physics_state = get_physics_state();
        let collider_map = &physics_state.voxel_collider_map;
        let mut sable_data = scene.sable_data.write().unwrap();
        let owner = object_id as LevelColliderID;
        if !sable_data.main_level_chunks.contains_key(&section_key) {
            return 0;
        }
        let Some(body) = sable_data.level_colliders.get(&owner) else {
            return 0;
        };
        if section_body_bounds(body, x, y, z).is_none() {
            return 0;
        }

        let body = sable_data.level_colliders.get_mut(&owner).unwrap();
        clear_body_section(body, x, y, z, collider_map);
        sable_data.main_level_chunks.remove(&section_key);
        1
    })
}

pub struct ReportedCollisionBuffer(RefCell<Vec<ReportedCollision>>);

unsafe impl Sync for ReportedCollisionBuffer {}

impl ReportedCollisionBuffer {
    pub fn new() -> Self {
        Self(RefCell::new(Vec::with_capacity(16)))
    }

    pub fn borrow_mut(&self) -> std::cell::RefMut<'_, Vec<ReportedCollision>> {
        self.0.borrow_mut()
    }
}

impl Default for ReportedCollisionBuffer {
    fn default() -> Self {
        Self::new()
    }
}

pub struct SimulationSceneData {
    pub pipeline: PhysicsPipeline,
    pub rigid_body_set: RigidBodySet,
    pub collider_set: ColliderSet,
    pub island_manager: IslandManager,
    pub broad_phase: DefaultBroadPhase,
    pub narrow_phase: NarrowPhase,
    pub impulse_joint_set: ImpulseJointSet,
    pub multibody_joint_set: MultibodyJointSet,
    pub ccd_solver: CCDSolver,
    pub physics_hooks: SablePhysicsHooks,
    pub event_handler: SableEventHandler,
}

pub struct SableSceneData {
    /// A 3-dimensional map of chunk sections for collision.
    /// chunk coordinates -> chunk section
    pub main_level_chunks: ChunkMap,
    pub octree_chunks: HashMap<i64, OctreeChunkSection>,

    /// The companion joint set
    pub joint_set: SableJointSet,

    /// Rope map
    pub rope_map: RopeMap,

    pub level_colliders: HashMap<LevelColliderID, ActiveLevelColliderInfo>,
    pub rigid_bodies: HashMap<LevelColliderID, RigidBodyHandle>,
}

/// A physics scene
pub struct PhysicsScene {
    pub sim_data: RwLock<SimulationSceneData>,
    pub sable_data: Arc<RwLock<SableSceneData>>,

    /// All collisions substantial enough to be considered for collision events.
    pub reported_collisions: Arc<ReportedCollisionBuffer>,

    pub manifold_info_map: Arc<SableManifoldInfoMap>,

    pub current_step_vm: Option<Arc<JavaVM>>,

    /// The handle to a static rigidbody
    pub ground_handle: Option<RigidBodyHandle>,

    /// The current gravity vector for all bodies. [m/s^2]
    pub gravity: Vec3,

    /// Universal linear drag applied to all bodies
    pub universal_drag: Real,
}

#[derive(Default)]
pub struct SableManifoldInfoMap {
    pub list: DashMap<usize, SableManifoldInfo>,
    pub counter: AtomicUsize,
}

impl SableManifoldInfoMap {
    pub fn clear(&self) {
        self.list.clear();
        self.counter.store(0, Ordering::Relaxed);
    }
}

pub struct SableManifoldInfo {
    pub pos_a: IVec3,
    pub pos_b: IVec3,
    pub col_a: usize,
    pub col_b: usize,
}

impl ChunkAccess for SableSceneData {
    fn get_chunk_mut(&mut self, x: i32, y: i32, z: i32) -> Option<&mut ChunkSection> {
        self.main_level_chunks.get_mut(&pack_section_pos(x, y, z))
    }

    fn get_chunk(&self, x: i32, y: i32, z: i32) -> Option<&ChunkSection> {
        self.main_level_chunks.get(&pack_section_pos(x, y, z))
    }
}

impl SableSceneData {
    pub fn get_octree_chunk(&self, x: i32, y: i32, z: i32) -> Option<&OctreeChunkSection> {
        self.octree_chunks.get(&pack_section_pos(x, y, z))
    }

    pub fn get_octree_chunk_mut(
        &mut self,
        x: i32,
        y: i32,
        z: i32,
    ) -> Option<&mut OctreeChunkSection> {
        self.octree_chunks.get_mut(&pack_section_pos(x, y, z))
    }
}
