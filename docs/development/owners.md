# Asynchronous owned JSON

`JsonOwner<T>` connects the owned JSON backend, context lifetime, actor-bound editing sessions and runtime publication. It is an experimental explicit-infrastructure API. Native actor gateways, convenience factories, native backend adapters, complete operation/cancellation views and real consumer migrations remain required by [coverage](coverage.md).

## Construct one document owner

Create `OwnerContext` with an explicitly admitted native executor, worker executor, native-thread verifier and resource limits. Construct `JsonOwner` on that native thread with its actual `JsonStore` and a maximum number of outstanding document operations. Executors are borrowed; the platform/factory retains their queue bounds and shutdown ownership. There is no common-pool fallback or hidden per-document thread.

The native gateway creates `OwnerContext.Access` using read/write callbacks bound to the actual actor and opens an owner session with it. A GUI or payload must not supply those callbacks. Registration requires the same context; observations and one-shot prepared proposals additionally belong to the particular document session. An identifier, filename, old successful read or complete candidate does not grant permission.

An access can serve several document sessions. Closing one session invalidates that session without closing another session's access or the document's runtime handle. Closing the access invalidates its sessions. Closing the context invalidates all access and native publication, releases queued work and clears executor/actor references. Native lifecycle code must close the context before its executor stops accepting work.

## Run the shared workflow

`Session.read()` checks actual READ permission, obtains saved data on the worker and rechecks permission before returning the observation. Browsing does not initialize missing files or publish saved values. Observations contain distinct saved state and effective context/revision; an uninitialized handle contains no substituted defaults.

`Session.initialize()` is an explicit WRITE-authorized lifecycle operation. The backend initializes a missing file from validated defaults using its complete-file create-if-absent contract. Existing invalid or unsupported data is retained. A failed observation can retain only an already qualified effective value in the same runtime context.

`Session.prepare(observation, draft)` inspects a bounded immutable draft on the worker and rechecks native authority, session and effective revision before returning a one-shot proposal. The original draft remains owned by the editing workflow. `Session.commit(proposal)` checks WRITE permission again at serialized commit admission, then performs guarded worker-side persistence and native-thread publication. A consumed or foreign proposal cannot be replayed. Queue refusal requires a fresh preparation; it never erases the editor's draft.

All actor sessions of one document share a bounded logical lane through persistence **and** publication. A physical file lock alone would permit a second session to act between those stages. A competing session prepared against the prior effective revision is refused before its own persistence. External file changes remain subject to the backend's exact observed-byte conflict checks; this does not create a cross-process transaction.

`Session.applySaved(observation)` reads saved bytes again, refuses a changed saved revision and attempts publication from that actual saved candidate. It performs no file write. It supports application-only retry after a successful save and refused application; it never substitutes a new unsaved candidate for the saved observation. A new observation allows an explicit review of externally changed values.

## Preserve truthful outcomes

Receipts distinguish `NOT_ATTEMPTED`, `UNCHANGED`, `SAVED`, `REFUSED`, `FAILED` and `UNKNOWN` persistence from application status. They contain separate diagnostics, an observed saved revision where available and an optional newly authorized observation. Losing READ permission suppresses newly observed file/effective contents without erasing the persistence fact. A successful disk replacement followed by permission loss or context closure remains `SAVED`; application can independently be refused or unavailable.

Actual WRITE authority is checked on the native executor at operation admission and again for runtime publication. Worker-side file guards check the already admitted operation's session/context lifetime before directory creation and immediately before replacement. Guards run under the file lock and must be synchronous/nonblocking: they never dispatch to or await native code. This is not an atomic transaction between native permission changes and filesystem replacement. A write already admitted before closure can finish; its actual receipt is drained. No callback claims that revocation or cancellation rolled it back.

Unstarted native/worker calls are detached before context-close completions run, even if the borrowed executor will never drain. An active write is not prematurely completed with an unattempted result. Failure without a reliable persistence receipt is conservatively `UNKNOWN`; reconcile saved state rather than blindly repeating the write. Closing queued document operations refuses them without executing their effects. Cancelling a copy returned by `CompletionStage.toCompletableFuture()` does not cancel the underlying operation or imply rollback. A public explicit cancellation/drain operation model remains implementation work.

## Boundaries and updates

Owned JSON IO and raw candidate preparation use the worker. Current publication still uses `RuntimePolicy`'s native-thread validation/copy/equality contract; expensive callback costs and worker-prepared publication require further work before release qualification. Domain compilation, application requirements, native TOML serialization and new-world admission are not generic JSON publication.

[Storage](storage.md) owns byte/path/atomicity guarantees. [Publication](publication.md) owns effective revision and immutable handle rules. Recheck those contracts, native actor resolution, close ordering and shared-lane/failure controls when changing an executor, owner backend or permission provider. The headless checks do not qualify server/connection gateways, native GUI responsiveness, pack behavior or complete Phase 1 readiness.
