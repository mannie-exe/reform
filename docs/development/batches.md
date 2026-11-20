# Document batches

`DocumentBatch` supplies complete-candidate preflight and explicitly ordered submission across borrowed `DocumentActions` sessions. It changes neither persistence owners nor their sources, codecs, serializers, callbacks, authority or application timing. All bindings share an actual owner thread; distinct owner contexts and lifetimes stay distinct. The service works without Minecraft classes. The native [client workflow](client-workflows.md) uses the same public service.

## Construction and validation

Construct a batch with its own actual `OwnerContext`, `Access`, logical `DocumentId` and ordered `Member<?>` bindings. `member(actions, COMMIT)` selects that editor for submission. `CHECK` observes a clean owner baseline for validation without requesting WRITE or persistence. An explicitly declared missing-source default remains attributed as unsaved source data. A check-only editor with uncommitted draft edits refuses instead of letting a domain rule approve unsaved guard values. Duplicate documents and empty or excessive member lists refuse before registration.

`review(entry)` calls each actual editor's identified backend preflight, then runs the optional pure `Validator` on a worker. It receives immutable `Values`; `candidate(member)` uses the exact typed member identity, while `candidate(definition)` accepts the exact registered `DocumentDefinition<T>`. Equal names or a newly constructed key confer no type or source authority. Returned values use their declared defensive snapshot semantics. Invalid member candidates prevent cross-document checks, preserving their actual reports rather than passing partial data to a validator.

`Values.baseline(member)` and `baseline(definition)` expose a complete typed saved baseline where available. For coupled documents, the validator can check the final candidate and each intermediate saved state, then return its check with `Check.withPlan(new DocumentBatch.Plan(writes, guardedSources))`. The write list must include all COMMIT members exactly once. Guard sources must be batch members. Every CHECK member is guarded. The owner defines the invariant and safe order. A missing or invalid baseline stays unavailable to that validator.

`Check` attributes field/cross-reference reports to actual member document IDs and separately records completeness. Unknown diagnostic targets refuse the entire projection. `Review` retains every member's identified source/value capture, backend diagnostics, candidate availability, intent and cross-document reports. Failed or incomplete preflight cannot authorize a write. READ disclosure must still be checked against the actual current owners; possession of a retained review is no authorization.

`submissionReadiness(review)` checks cached current target identities, pending/conflict state, actual authority and the retained unconsumed review. It performs no backend IO or domain validation and is only a frontend hint. `requireRead()` separately checks actual disclosure authority, permitting inspection of an unknown prior save rather than requiring another saveable state.

## Ordered effects and receipts

`commit(entry, review)` accepts only this batch's retained review. The provider consumes that review once, repeats complete backend/cross-document preflight, compares every reviewed target and checks all required rights before any effect. Each COMMIT uses the existing conditional owner operation with its exact reviewed revision. Current remaining targets and rights are checked again between owner operations. Later edits to an already captured submission survive its acknowledgement; a changed future member stops the order.

The reviewed plan must still match after fresh preflight. Before each write and at completion, guarded members read their actual source through their owner and compare its exact revision. This leaves editor drafts and baselines intact. A guarded writer advances the expected source only from the verified readback in its receipt, and the readback must match the reviewed typed value. A changed or unobservable guard stops later effects and identifies its document. A known saved prefix remains visible.

The result retains every member's actual operation snapshot and owner receipt. Unsaved-context admission, file persistence and runtime publication keep their native distinctions. Saved but staged is different from applied. Failure, cancellation, partial application or an unknown effect stops further submissions; unattempted members receive explicit `batch.not_attempted` diagnostics. The actual fresh preflight, when obtained, remains available even when it refused the order. A successful operation wrapper alone proves no persistence.

An entered source effect drains to its actual result. Cancelling or closing the batch cannot undo a completed write, close borrowed document owners or safely repeat an uncertain write. Unknown outcomes require the relevant owner's reconciliation; application-only retry remains a separate owner capability. An expired, foreign or consumed review cannot initiate another run. Queued replay refusal is retained as an operation result.

These ordered independent operations check sources at the admitted read points. An external writer can change another file after a check. Domain owners retain responsibility for any stronger atomicity or source coordination.

## Bounds and lifetime

Standard admission is 32 documents, two retained reviews, 16,777,216 aggregate description characters and 16,384 diagnostics, in addition to each editor's existing draft, source, operation and projection bounds. The batch scope admits two definitions, two active operations and eight retained observations. Only one ordered submission runs at a time. Exhaustion refuses complete review material without partial validation credit; these limits are logical admission policy, not heap measurements or a callback time bound.

The validator must be bounded pure work; it cannot query native registries, perform source IO or mutate gameplay. Domain operations remain separate explicit services. Batch closure releases its own scope/reviews and context hook, preserving borrowed sessions. Native readback, callback, loader or lifecycle changes require requalifying backend preflight, current authority, source revisions, late edits, separate effects and cancellation/drain through actual packaged consumers. [Verification](verification.md) owns measured evidence and remaining qualification.
