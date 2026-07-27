package dev.ryanhcode.sable.sublevel.transfer;

/**
 * Stable failure codes produced by cross-level transfer preflight validation.
 */
public enum CrossLevelTransferFailure {
    SAME_LEVEL,
    SOURCE_REMOVED,
    SOURCE_CONTAINER_UNAVAILABLE,
    TARGET_CONTAINER_UNAVAILABLE,
    TARGET_PHYSICS_UNAVAILABLE,
    DUPLICATE_TARGET_UUID,
    DEPENDENCIES_PRESENT,
    ACTIVE_KINEMATIC_CONTRAPTION,
    INCOMPATIBLE_SECTION_LAYOUT,
    TARGET_SLOT_OCCUPIED,
    ENTITIES_PRESENT,
    TRANSACTION_CONFLICT,
    SNAPSHOT_UNAVAILABLE
}
