# Cross-Dimension Sub-Level Transfer Contract

Status: Phase 01 design gate only

This document defines the compatibility and safety boundary for moving one server-side Sable sub-level from one `ServerLevel` to another. It does not add runtime transfer behavior.

## Goal

Provide a future public API that can move a sub-level between dimensions without requiring Create Aeronautics or other consumers to replace existing Sable API calls.

The intended operation is transactional:

1. validate the source and target;
2. capture an immutable transfer snapshot;
3. reserve the target plot slot;
4. reconstruct the sub-level in the target `ServerLevel`;
5. transfer eligible entities and tracking state;
6. remove the source only after target validation;
7. commit or roll back exactly once.

## Backward-compatibility requirements

The first implementation must preserve all existing public and binary contracts used by Create Aeronautics 1.3.x and other Sable consumers.

It must not:

- rename or move existing public classes;
- change existing method descriptors;
- change `modId = "sable"`;
- require consumers to recompile merely to keep current behavior;
- change ordinary same-dimension serialization, loading, removal, physics, or tracking semantics;
- expose an incomplete transfer as a normal loaded sub-level;
- use reflection or consumer-owned mixins as the transfer mechanism.

New functionality must be additive and opt-in.

## Phase 01 scope

The first executable spike is limited to:

- one `ServerSubLevel`;
- no loading dependency chain;
- no entities or passengers;
- no active kinematic contraptions;
- equal source and target section layouts;
- the same local plot slot available in the target dimension;
- a caller-supplied target `Pose3d`;
- preservation of UUID, blocks, block entities, scheduled ticks, attachments, name, user data, linear velocity, and angular velocity;
- source removal only after target reconstruction and validation succeeds;
- a durable recovery record before source mutation.

## Explicitly excluded from Phase 01

- automatic planet or atmosphere selection;
- fuel, navigation, portals, or user interface;
- offline players;
- respawn points;
- entity and passenger graphs;
- active Create or Aeronautics contraptions;
- connected or nested sub-level chains;
- relocation to a different local plot slot;
- dimensions with incompatible section counts or build-height layouts;
- changing Sable's storage file format without a migration path;
- destructive changes to existing serialization or removal paths.

## Proposed additive API boundary

Names are provisional until the spike proves the lifecycle.

```java
public interface CrossLevelSubLevelTransferService {
    CrossLevelTransferValidation validate(CrossLevelTransferRequest request);

    CrossLevelTransferResult transfer(CrossLevelTransferRequest request);

    CrossLevelTransferRecoveryResult recover(UUID transactionId);
}
```

```java
public record CrossLevelTransferRequest(
        ServerSubLevel source,
        ServerLevel targetLevel,
        Pose3d targetPose,
        UUID transactionId
) {}
```

```java
public enum CrossLevelTransferPhase {
    PREPARING,
    SNAPSHOT_WRITTEN,
    TARGET_RESERVED,
    TARGET_LOADED,
    SOURCE_REMOVED,
    COMMITTED,
    ROLLED_BACK
}
```

The public result must report a typed outcome rather than throwing for expected validation failures. Unexpected failures must retain enough recovery state to restore one authoritative copy.

## Required validation gates

Before writing or mutating either side, validation must reject the transfer when:

- source and target are the same `ServerLevel`;
- the source is already removed;
- the source UUID is already present in the target container;
- the source has dependencies;
- the source plot has active kinematic contraptions;
- the target does not have a Sable container and physics system;
- source and target section layouts differ;
- the matching target local plot slot is occupied or reserved;
- any entity is contained in, tracking, or riding a vehicle in the source sub-level;
- another transaction owns the source UUID or target reservation;
- the source snapshot cannot be written and verified before mutation.

## Plot-slot rule

Phase 01 must use the same local plot coordinates in the target dimension.

Changing only serialized `plot_x` and `plot_z` is unsafe because block-entity NBT, scheduled ticks, chunk attachments, and mod-specific data may contain plot-space positions. General plot relocation is a separate future feature and must not be hidden inside the transfer spike.

## Transaction invariants

At every durable phase boundary:

- one transaction ID identifies the operation;
- the source UUID has at most one authoritative committed location;
- the source is not removed before a valid target exists;
- target construction failure leaves the source authoritative;
- process restart can determine whether to resume, roll back, or finalize;
- recovery is idempotent;
- duplicate calls with the same transaction ID cannot create a second target;
- cleanup of a failed target cannot delete the source snapshot.

## Snapshot requirements

The immutable transfer snapshot must contain or reference:

- transaction UUID;
- sub-level UUID;
- source and target dimension keys;
- source and target poses;
- source local plot coordinates;
- source and target section-layout descriptors;
- serialized sub-level data;
- dependency UUIDs;
- linear and angular velocity;
- snapshot format version;
- checksum;
- current transaction phase.

The initial implementation should reuse `SubLevelSerializer.toData` and `SubLevelSerializer.fullyLoad` rather than inventing a second block-copy path. The service must wrap partial target allocation and load failures so that partially created target state is removed safely.

## Removal semantics

The spike must not overload existing `UNLOADED` semantics. A future additive removal reason such as `TRANSFERRED` may be introduced only after its behavior is explicitly tested.

Required transfer removal behavior:

- detach the source physics body;
- unload source plot chunks;
- release source occupancy;
- avoid deleting or kicking entities after entity transfer support is added;
- clear obsolete source storage pointers only after commit;
- notify existing observers in a deterministic order.

Until that semantic exists, the Phase 01 spike must have no entities and must prove that `REMOVED` is safe in that constrained case.

## Aeronautics compatibility boundary

Create Aeronautics must remain an unchanged external consumer during Phase 01.

The spike therefore must:

- preserve the existing Sable package and API surface;
- run with the released Aeronautics JAR and the forked Sable JAR;
- reject active Aeronautics/Create contraptions instead of attempting to migrate their runtime-only state;
- add no Aeronautics-specific planet, fuel, bearing, propeller, or UI logic to Sable.

A later Aeronautics lifecycle hook may be justified only after the generic Sable transfer succeeds and a concrete runtime-state gap is demonstrated.

## Phase 01 test skeleton

The executable phase must add deterministic server-side tests for these cases:

1. validation rejects same-level transfer;
2. validation rejects occupied target slot;
3. validation rejects active kinematic contraption;
4. validation rejects any contained or tracking entity;
5. a stone-only sub-level preserves UUID, pose, bounds, and block states;
6. a chest preserves block-entity inventory data;
7. scheduled block and fluid ticks survive reconstruction;
8. linear and angular velocity are restored;
9. successful transfer leaves no source sub-level or source occupancy;
10. target load failure leaves the source intact;
11. restart after `SNAPSHOT_WRITTEN` rolls back safely;
12. restart after `TARGET_LOADED` resolves to exactly one authoritative copy;
13. duplicate invocation with one transaction ID is idempotent;
14. released Create Aeronautics starts with the forked Sable without recompilation.

Tests and fixtures must not be packaged into the production JAR.

## Advancement gate

Phase 02 implementation may begin only after this contract is reviewed and the following remain true:

- no production behavior changed in this phase;
- no public or binary API was broken;
- no dependency, workflow, asset, or mod metadata change was introduced;
- the fork remains buildable from the same upstream baseline;
- the first code PR can be limited to validation, transaction records, and a stone-only transfer fixture.
