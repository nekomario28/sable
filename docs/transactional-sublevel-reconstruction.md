# Transactional SubLevel Reconstruction Contract

## Status

Required before any addon may reconstruct a Sable `SubLevel` in another dimension and then remove its source.

This contract is tracked by `nekomario28/nekomario28-sable-transit#28`. Transit already provides:

- an immutable, durably verified source snapshot;
- a complete loaded-and-stored UUID location proof through Sable's authoritative snapshot API;
- a synchronously persisted exact target reservation represented by `TARGET_RESERVED`;
- no source removal or target world mutation before this contract is satisfied.

## Problem

`SubLevelSerializer.fullyLoad` is not an atomic reconstruction boundary.

Its current sequence is broadly:

1. parse the target plot and pose;
2. call `ServerSubLevelContainer.allocateSubLevel`;
3. publish the new SubLevel into the slot array, occupancy bitset, loaded list, UUID map and observers;
4. mark occupancy SavedData dirty;
5. load plot chunks, block entities, ticks, light and platform attachments;
6. activate chunks and publish platform/player events;
7. register or update physics body, section state and tickets;
8. apply pose, velocity, metadata and bounds.

Only the invalid-bounds branch performs explicit cleanup. Exceptions from other stages can leave an observer-visible or durable partial target.

A broad `catch` followed by ordinary `removeSubLevel` is not a sufficient inverse because:

- allocation observers may already have observed the target;
- occupancy SavedData may have changed from clean to dirty;
- normal `REMOVED` cleanup may queue a durable storage deletion;
- chunks, block entities, ticking, light, platform events or player packets may already have been activated;
- the physics body and section uploads may be partially registered;
- physics section-ticket creation has no immediate symmetric public rollback operation;
- rollback must not remove shared physics state that existed before reconstruction.

## Required invariant

A reconstruction call must produce exactly one of these outcomes:

### Committed

The requested UUID exists exactly once at the requested target dimension and local plot slot. Plot data, metadata, bounds and physics state match the supplied `SubLevelData`, and every public observer sees one committed addition.

### Rolled back

The target dimension is observationally equivalent to its pre-call state:

- target slot and occupancy are unchanged;
- target UUID is absent from loaded and stored authoritative indexes;
- no target plot, chunk, block entity, entity, tick container or light state remains;
- no target physics body, section upload, ticket, constraint or object registration remains;
- no holding-storage deletion or movement is queued;
- occupancy SavedData has its exact previous dirty state;
- no public allocation observer was notified;
- no target player synchronization or committed platform event was emitted.

Partial success must never be returned.

## API boundary

Provide one server-thread-only operation with a typed result. The exact names may differ, but the semantic shape must be equivalent to:

```java
ReconstructionResult reconstructTransactional(
        ServerLevel targetLevel,
        SubLevelData data,
        ReconstructionOptions options
);
```

The result must distinguish at least:

- `COMMITTED`, including the verified target;
- precondition rejection before mutation;
- reconstruction failure with successful rollback;
- rollback failure, which must be treated as a fatal consistency failure and include diagnostic evidence.

The operation must reject calls outside the owning Minecraft server thread.

## Pre-mutation validation

Before creating any target state, validate all deterministic conditions:

- target container and physics system exist;
- target plot coordinates are in range;
- target slot is vacant;
- UUID is absent from the target container;
- pose and rotation point contain finite values;
- plot `log_size` matches the target container;
- plot bounds and serialized version are valid;
- all chunk keys, local coordinates and section indices are within the target plot and level bounds;
- NBT structures required for chunks, ticks, block entities and metadata are well formed;
- dependencies are supported by the selected reconstruction mode;
- no entity payload is accepted unless the transaction explicitly supports and rolls it back.

Validation reduces failure points but does not replace rollback because registry, platform, block-entity and physics implementations may still throw.

## Transaction stages

### 1. Capture pre-state

Capture enough exact state to prove rollback:

- slot entry and occupancy bit;
- loaded-list and UUID-map membership;
- occupancy SavedData dirty state;
- observer publication count or generation;
- storage queue state relevant to the target UUID and slot;
- physics body existence and relevant section-ticket baseline;
- plot/chunk registrations for the target slot.

Do not rely only on object counts when identity can be checked.

### 2. Provisional allocation

Create a transaction-owned provisional SubLevel without publishing a committed addition.

A provisional allocation must:

- be inaccessible through ordinary public UUID and loaded-SubLevel queries, or be explicitly marked provisional and rejected by them;
- not notify public observers;
- not persist occupancy;
- not queue storage work;
- be owned by a single transaction token;
- support single-use commit and rollback.

The target slot still must not be usable by another transaction while provisional work is active.

### 3. Provisional plot materialization

Stage plot chunks and data while recording every introduced resource.

Externally visible activation should be deferred until commit where possible, including:

- chunk/platform loaded events;
- player chunk synchronization;
- block-entity load notifications and ticking activation;
- public plot/container observer callbacks.

Where a subsystem cannot defer activation, the transaction must record and execute its exact inverse before reporting rollback success.

### 4. Provisional physics materialization

Track exactly which physics resources the transaction introduces:

- SubLevel rigid body;
- uploaded chunk sections;
- physics section tickets;
- pose/teleport state;
- velocity changes;
- mass, bounds and statistics updates;
- constraints, kinematic children or arbitrary objects, when supported.

Add symmetric transaction-aware removal for section tickets and uploads. Cleanup must not remove pre-existing shared tickets or section data.

### 5. Internal verification

Before public commit, verify:

- UUID and target coordinates match the request;
- all serialized chunks expected by the snapshot are present;
- reconstructed immutable data re-serializes to equivalent evidence under the documented canonical comparison;
- bounds are non-empty and finite;
- physics body exists and reports a finite pose;
- no unsupported dependency, entity or child contraption was introduced;
- transaction-owned resource inventory is internally complete.

### 6. Commit

Commit must be one-way and idempotence-safe.

Only after internal verification succeeds:

- publish the slot, loaded list and UUID map as committed;
- publish the occupancy bit and mark SavedData dirty;
- activate deferred block entities, ticking, events, players and observers in a defined order;
- transfer provisional physics/resource ownership to the committed SubLevel;
- return the committed target.

Define a deterministic policy for exceptions from external callbacks after the commit point. Such exceptions must not silently pretend the target was rolled back after observers have seen it.

### 7. Rollback

Before the commit point, any exception or typed failure must roll back in reverse dependency order.

Rollback must be:

- best-effort across all cleanup steps, collecting suppressed failures rather than stopping at the first exception;
- followed by a complete invariant verification;
- reported as successful only if the exact pre-state is restored;
- reported as rollback failure otherwise, with the server prevented from continuing target mutation blindly.

Ordinary `SubLevelRemovalReason.REMOVED` must not be used when it would queue deletion for a target that was never committed.

## Failure injection

Provide deterministic test-only injection points after at least:

1. provisional slot ownership;
2. provisional SubLevel creation;
3. physics body creation;
4. each plot-chunk materialization boundary;
5. block-entity materialization;
6. tick/light/attachment loading;
7. physics section upload and ticket creation;
8. metadata application;
9. bounds application;
10. physics teleport;
11. velocity application;
12. internal verification;
13. immediately before public commit.

Injection must be unavailable or inert in production builds unless explicitly enabled for tests.

## Required tests

### Pure/unit tests

- precondition validation rejects malformed data without mutation;
- transaction token cannot commit or roll back twice;
- rollback cleanup continues after one cleanup action throws;
- shared/pre-existing physics resources are not removed;
- occupancy dirty state is restored exactly;
- observer publication is delayed until commit.

### GameTests

For every injection point, capture the authoritative loaded-and-stored UUID snapshot before and after the failed operation and assert:

- same UUID map;
- same target slot and occupancy;
- same plot/chunk registration;
- no target entities or block entities;
- no transaction-created physics body, section upload or ticket;
- no storage queue operation for the provisional target;
- no public added observer callback;
- exact prior occupancy SavedData dirty state.

Also test:

- successful reconstruction followed by authoritative UUID re-read;
- save/restart after successful reconstruction;
- restart after a durably reserved Transit transaction but before reconstruction;
- failure followed by a second successful reconstruction into the same slot;
- duplicate UUID and occupied-slot races rejected before mutation.

## Transit integration order

After this Sable API and its GameTests pass, Transit may proceed only in this order:

1. re-read immutable snapshot evidence;
2. re-run authoritative preflight;
3. synchronously persist `TARGET_RESERVED`;
4. call Sable transactional reconstruction;
5. re-read authoritative UUID location and re-serialize target evidence;
6. synchronously persist `TARGET_LOADED`;
7. only then begin a separately guarded source-removal phase.

Transit must never call legacy `fullyLoad` as a fallback when the transactional API is absent.

## Non-goals

- source removal;
- cross-dimension orchestration inside Sable;
- Transit journal implementation;
- Create/Simulated-specific reconstruction logic;
- entity transfer unless separately specified and tested;
- compatibility fallback to non-transactional loading.
