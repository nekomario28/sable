# Agent Engineering Entry Point

Sable is an intrusive upstream-derived Minecraft library with active local reconstruction work. Preserve upstream ownership, repository-native build rules, and the current fail-closed reconstruction program. This file is only a local routing layer; it is not a replacement architecture.

## Read first

1. `README.md` for upstream project scope, build guidance, compatibility warning, and licensing.
2. Live GitHub PR/issue state for the current reconstruction stage. Do not infer the active stage only from old merged PR text.
3. The exact production implementation and GameTests for the subsystem being changed.
4. For changes consumed by `nekomario28-sable-transit`, preserve the audited reconstruction/preflight contracts expected by Transit rather than adding a second transfer policy here.

## Reconstruction authority

The reconstruction path is intentionally staged and rollback-first.

- A reconstruction stage must remain detached from ordinary container/world publication until the stage's explicit commit boundary.
- Provisional runtime IDs, Rapier bodies, sections, chunks, occupancy, observers, platform callbacks, and other resources require exact ownership and exact inverse cleanup while rollback is still possible.
- If acquisition partially succeeds and cleanup cannot be proven complete, retain ownership and fail closed. Never reinterpret a failed API return as proof that no native/provisional state exists.
- Commit only after the existing read-back/verification contract passes. Do not add irreversible publication before the last required validation simply to simplify cleanup.
- Rollback evidence is part of correctness. A broad catch plus ordinary removal is not a substitute for restoring the pre-call observable state.

## Native / Java packaging discipline

Sable's normal GameTest path can load the committed native bundle rather than newly edited Rust source.

- When changing Rust/native reconstruction behavior, preserve the repository's existing native-substrate -> regenerated committed bundle -> Java bridge sequencing when required for truthful runtime validation.
- Do not make Java rely on native behavior that the normal validation path cannot actually load yet.
- A Java-only change must not silently expand the native capability claim.
- Keep exact ownership and fail-closed behavior consistent across JNI/native and Java registries.

## Scope and provenance

- Prefer narrow changes that preserve upstream structure and licensing. Do not perform unrelated formatting, dependency, asset, or history rewrites as part of local reconstruction work.
- Keep local reconstruction changes reviewable against upstream-derived code. Record why a deviation is needed rather than treating the fork as a blank-slate project.
- Do not copy Sable production classes into Transit; Sable owns sub-level/reconstruction semantics and Transit consumes an audited boundary.

## Validation and claims

- Use the repository's real Gradle/GameTest path for runtime claims; compile success alone is not reconstruction evidence.
- Preserve failure-injection coverage around every resource-owning stage that can leave externally observable state.
- Treat an exact-head GREEN result as evidence only for that head and tested capability. Do not promote later reconstruction stages, source removal, or cross-dimension transfer merely because an earlier detached stage passes.
- Refresh live PR/base state before modifying an active reconstruction carrier; avoid rebuilding or superseding it without a concrete blocker.
