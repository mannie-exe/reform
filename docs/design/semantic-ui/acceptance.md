# Design traceability and acceptance

The claim being audited is **two complete connected designs**, not a working new runtime. SOURCE means inspected source or attributed prior observation; DECISION is a proposed normative choice; WALKTHROUGH is a consistency exercise over that contract; DOC-CHECK is an executed document/provenance check. None establishes future implementation/native qualification. The task's final audit retains exact commands, source/document digests and material omissions.

## Objective trace

| ID  | Requirement                                                                  | Owning artifact and concrete evidence to inspect                                                                                                        |
| --- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| O01 | Read full selected objective and current state.                              | Captured objective digest, current clean source revisions, repository guidance and task checkpoint.                                                     |
| O02 | Investigate SwiftUI, Solid 2, Base UI and actual Playground.                 | [Research](research.md#inspected-source-identities); pinned package/tag/source and dated Apple symbol documents, actual local source/contract excerpts. |
| O03 | Distinguish stable, experimental, historical and inferred claims.            | Research identities and adopt/adapt/reject/decision tables; Solid frozen RFC versus runtime distinction and separate signals package.                   |
| O04 | Preserve original rewrite and recent two planning artifacts.                 | [Migration lineage](migration.md#intent-lineage-and-planning-ownership), retained before-text digests and complete clause disposition manifest.         |
| O05 | Complete revised 0.4 scope, all owner UX feedback.                           | [References/previews](references-previews.md), layout/control contracts and U01–U15 below.                                                              |
| O06 | Complete bounded full architecture, remaining Phase 2 and future work.       | [Full system](full-system.md), ten value families, F01–F08 contracts/dependencies and explicit exclusions.                                              |
| O07 | One shared foundation and credible extension path.                           | [Kernel module map](kernel.md#module-and-service-boundaries), input/capture API sum, [migration](migration.md), F package dependencies.                 |
| O08 | Every relevant structure has purpose/owner/identity/I/O/invariants.          | Kernel vocabulary/state tables, component anatomy, picker/basis state and full package contracts.                                                       |
| O09 | Construction/admission/mount/update/suspend/disposal/threading.              | Kernel mounting/lifetime/scheduler and [interaction](layout-interaction.md); real owner and resource services retained.                                 |
| O10 | Actions/preconditions/failure/cancel/recovery.                               | [APIs](api.md#commands-and-field-bindings), picker/preview protocols, component key table, owner outcome table.                                         |
| O11 | Composition/neighbors/limits/cost/cache/platform split.                      | Kernel environment/resource/extension contract and [layout algorithms](layout-interaction.md#geometry-contract-and-algorithms).                         |
| O12 | Address all twelve named architecture areas.                                 | C01–C12 table below with document owners.                                                                                                               |
| O13 | Vocabulary/type/relationship/module/dependency/lifecycle diagrams.           | Kernel vocabulary + README service graph + lifetime state machine + preview and remote/save sequences.                                                  |
| O14 | Proposed APIs with construction and typed results/errors.                    | [API shapes](api.md), single command/field bindings, component parts, basis/provider evolution and full package signatures.                             |
| O15 | Seven substantive end-to-end examples.                                       | [E1–E7](examples.md), each with actual inputs/state/effect path and failure/currentness sequence.                                                       |
| O16 | Kept/evolved/replaced/retired map.                                           | [Existing API/source disposition](migration.md#existing-apisource-disposition); exact classes/services and compatibility obligations.                   |
| O17 | Decision log with alternatives/tradeoffs.                                    | [D01–D18](research.md#decision-log), including explicit differences from each inspiration.                                                              |
| O18 | Dependency-ordered reviewable work breakdown.                                | [A0–A6](migration.md#dependency-ordered-implementation-checkpoints), F01–F08 and their gate dependencies.                                               |
| O19 | Requirement→contract→verification for both designs.                          | This document, inherited38 and full family maps, source-clause lineage manifest with total coverage audit.                                              |
| O20 | Update candidate/analysis and replace Phase 2 bucket without dropping scope. | Revised canonical private intents, original supersession notice plus archived original bytes, new full-reference intent and central body readbacks.     |
| O21 | Relevant independent intents and future constraints without activation.      | Original analysis ledger, migration independent-owner list and full-system exclusions.                                                                  |
| O22 | Validate difficult scenarios against actual source and consumer semantics.   | W01–W15 below; current ReferenceSelection/capture/provider/host and Styled field/subject mapping source evidence.                                       |
| O23 | Tools/guidance/debugging/provenance/update obligations.                      | F07, API inspector and migration tooling section; actual documentation verifier outputs with clear limits.                                              |
| O24 | Preserve unrelated code/artifacts/user preview; no prohibited activation.    | Final Git diff scope/status and unchanged product/version/source manifests; no build/native/pin/publication/desktop actions.                            |
| O25 | One current Moment checkpoint and final exact retrieval.                     | Current incident continuation and source/body digest equality, with old immutable records retained.                                                     |
| O26 | Final decisions, sequence, limitations and remaining material uncertainty.   | README, decision/migration/audit records; native metrics/ABI/provider/transport qualification remain implementation gates.                              |

| Area                                                                                                    | Contract owner                                                    |
| ------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| C01 authoring/builders/modifiers/slots/environment/identity/conditions/collections                      | Kernel + APIs + E1/E5.                                            |
| C02 roles/names/relations/values/errors/availability/selection/disclosure/busy/actions                  | Layout/interaction semantic projection and component anatomy.     |
| C03 owner/raw/local/derived/bindings/controlled state/effects                                           | Kernel state/binding and scheduler; E4/E7.                        |
| C04 flow/ownership/loading/errors/latest/cancel/drain/stale/cleanup                                     | Kernel flow table + references replacement state machine + E6.    |
| C05 measure/constraints/flex/wrap/gaps/scroll/clips/overlays/responsiveness                             | Layout algorithms and component defaults.                         |
| C06 routing/capture/keyboard/text/focus/popups/gameplay                                                 | Event and keyboard/focus contracts.                               |
| C07 hierarchy/history/routes/bookmarks/retained destinations/admission/close                            | Header/navigation and kernel actual mount acknowledgement.        |
| C08 accessible fields/lists/completion/choices/disclosure/menus/dialogs/toolbars/status/errors/previews | Component anatomy, key table, semantic feedback and API parts.    |
| C09 inputs/bases/cameras/policies/native uses/retirement/capabilities                                   | References/previews and existing graphics boundary.               |
| C10 validate/history/review/conflict/import/export/save/apply/unknown/recovery/batch                    | Full-system owner foundation and E7; existing services preserved. |
| C11 diagnostics/inspectors/fixtures/examples/debug/docs/provenance                                      | F07 + migration/tooling + audit.                                  |
| C12 advanced types/expressions/inheritance/merge/remote/sharing/adapters                                | Full family matrix and F01–F08.                                   |

## Live UX feedback trace

| ID  | Feedback                                       | Design resolution                                                                            | Future actual check                                                                             |
| --- | ---------------------------------------------- | -------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| U01 | Profiles purpose unclear.                      | Role/purpose copy and draft versus audition basis, one named task.                           | User can explain inspected/selected/previewed values before editing.                            |
| U02 | Search/choices/preview look unrelated.         | Shared browser container, useful subheadings, adjacent/stacked same semantic task.           | Wide and compact user journey, same owner/bookmark without lost context.                        |
| U03 | Choices should be a real list.                 | Bounded scroll viewport, stable keyed options, selection/focus and paging separate.          | Wheel/keyboard/scrollbar, long/paged/empty/unavailable results and offscreen selection.         |
| U04 | Retain preview rotation.                       | Camera/view state separate from output; provider initial defaults once and explicit Reset.   | Reprepare different subject/profile and resize without orbit/zoom reset or compile from camera. |
| U05 | Separate container/Open/Looted controls.       | SubjectForm and consumer typed lenses preserve existing twelve combinations.                 | Independently exercise all twelve and unsupported CEM pose feedback.                            |
| U06 | Automatically update preview state.            | Opt-in latest-only120ms preparation after relevant committed gestures; existing lane/drain.  | Rapid changes, out-of-order completions, no source/file/application side effects.               |
| U07 | Appearance inline bounded picker with preview. | Same picker/controller, fixed field role, approx four visible rows and optional preview.     | No ordinary Screen transition; independent field edits and complete candidate preview.          |
| U08 | Automatic responsive search.                   | 200ms debounce, Enter flush, latest/source-aware results; no compile.                        | Input/search latency and stale query/source tests on actual sources.                            |
| U09 | Text breadcrumb and current emphasis.          | Unboxed ancestor links, muted chevrons, current highlighted/underlined, accessible overflow. | Native keyboard/narration and long path with no duplicate full-path speech text.                |
| U10 | Stable nearby history arrows/assets.           | Native sprites on both games, shared stable header toolbar.                                  | All destinations, focus/disabled states, actual current route/history and same header bounds.   |
| U11 | ID autocomplete.                               | Free-form source-bound explicit completion, native text keys and retained unknowns.          | Malformed/custom ID, source change, accept/dismiss/blur/caret/selection cases.                  |
| U12 | Inspect/source inline distribution.            | Related bounded-gap action row, measured wrapping.                                           | No giant gap/clipping or lost action at narrow/highscale.                                       |
| U13 | Edit ID spacing.                               | Named disclosure/field boundary gap and collapsed error linkage.                             | Actual pixels/focus/text at both scales/languages and neighboring fields.                       |
| U14 | Show preview confusing.                        | Remove ordinary redundant button; optional accurately named focus jump only where needed.    | Keyboard can reach/reveal viewport; action never implies/starts prepare.                        |
| U15 | Cancel active while idle.                      | Readiness from pending/queued/preparing state; inactive while ready/draining/terminal.       | Native activity plus real cancellation acknowledgement/drain and no renderer-use confusion.     |

## Inherited38 acceptance obligations

Every ID from the original [migration matrix](../presentation-0.4-migration.md#acceptance-matrix-for-later-implementation) remains. “Retained” here is a design disposition, not a new runtime pass or erasure of the original baseline qualification. Changed bindings require new affected evidence; unchanged historical carry keeps its actual version/scope.

| ID        | New owning contract                                      | Verification disposition                                                                           |
| --------- | -------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| SHELL-1   | Layout/header offers and overflow.                       | Retained; actual wide/narrow/highscale native controls/text/focus.                                 |
| SHELL-2   | Kernel retained shell/owner and lazy Home.               | Retained; same composition/source through ordinary routes.                                         |
| HOME-1    | No eager owner/source/preview admission.                 | Retained; unavailable/unopened Home actual read/provider counters.                                 |
| THEME-1   | Kernel environment/modifier precedence.                  | Retained; same resolved measure/draw and explicit inherit/default.                                 |
| THEME-2   | Native font/language/motion/contrast policy.             | Retained; input/state retained, meaning independent of decoration.                                 |
| CONTROL-1 | Intrinsic parts and single action binding.               | Extended; stale/disabled/nested activation and invalid text.                                       |
| NAV-1     | Key/route admission, alias identity and missing rows.    | Retained; duplicate/cycle refusal and row replacement negative.                                    |
| NAV-2     | Hierarchy/history/header/bookmarks.                      | Extended; actual text-link/overflow/history keyboard returns.                                      |
| NAV-3     | Mount generation and actual REVEALED boundary.           | Retained; async latest and permission/source refusal without focus theft.                          |
| LIFE-1    | Scope suspension/retirement and existing owner lifetime. | Retained; return/close/disconnect/shutdown, no resurrection.                                       |
| GRAPHIC-1 | Typed capability and failure projection.                 | Retained; absent/no-world/unsupported versus empty/invalid.                                        |
| GRAPHIC-2 | Existing isolated native providers.                      | Retained; no gameplay/world mutation, clipping and camera controls.                                |
| GRAPHIC-3 | Automatic demand/capture state machine.                  | Extended; rapid relevant changes, reload/late work and no paint compilation.                       |
| GRAPHIC-4 | Version adapter/native use boundary.                     | Retained; immediate/extracted failure restoration and dedicated absence.                           |
| LOOTR-1   | Shared picker, staging/audition and whole candidate.     | Extended; actual Models/Artwork/subjects/manual/export workflows.                                  |
| LINK-1    | Optional existing view-only recipe links.                | Retained; provider absent/notready/dualviewer/origin return, no craft/transfer.                    |
| OWNER-1   | Existing actual document actions/receipts.               | Retained; invalid/default/history/conflict/pending effect cases.                                   |
| COST-1    | Bounded projections/requests/retention.                  | Extended; measured input/search/reflow/repeated preparation/releases, no portable frame guarantee. |
| DECL-1    | Structural/part/budget/capability admission.             | Extended; fail before source/owner/native allocation, partial cleanup.                             |
| SHELL-3   | Stable offered identity/location/history.                | Extended; actual header bounds through seven view families and long path.                          |
| THEME-3   | Authored semantic roles versus component defaults.       | Retained; explicit default does not disappear under variant.                                       |
| PREVIEW-1 | Input epoch and complete base capture.                   | Extended; rejected native text + valid sibling cannot audition/capture older draft.                |
| PREVIEW-2 | Read-only capture independent of save review.            | Retained; READ-only/review-required owner and actual provider.                                     |
| PREVIEW-3 | Draft/source/reference/native-input basis stamp.         | Extended; each change boundary refuses stale output/overlay.                                       |
| GRAPHIC-5 | Native uses and releaseCompletion.                       | Retained; close/reload/submission/permission/executor/shutdown boundaries.                         |
| GRAPHIC-6 | Shared lane/ledger and retained cleanup cause.           | Extended; one latest demand, ignored cancel, failed/uncertain retirement, newer READY preserved.   |
| MIGRATE-1 | Public adapter/default/compatibility migration.          | Retained; exact older-loader refusal and actual same owner/native path.                            |
| NAV-4     | Authored composition/reading order.                      | Retained; schema-independent visual/nav/keyboard order and group anchors.                          |
| TASK-1    | Same browser/status/inspection at A/B offers.            | Extended; measured fit and actual short/narrow/highscale/long text.                                |
| TASK-2    | Retained task state and widget attachment.               | Extended; A→B→A query/caret/raw/disclosure/row/operation/camera.                                   |
| TASK-3    | Typed declaration/part/theme defaults.                   | Extended; customization cannot remove required behavior or force clipped A.                        |
| META-1    | Cached field/source/effective inspection.                | Retained; no invented values/refresh, technical details and real absence states.                   |
| META-2    | Whole-document action scope.                             | Retained; filtered hidden fields Defaults/Undo/Redo/Reload/Import/Discard.                         |
| META-3    | Exact single/batch submissions and receipts.             | Retained; stale/invalid/partial/unknown, no transaction claim.                                     |
| META-4    | Actual manual conflict and F03 typed merge.              | Retained/extended; whole collections absent identity and fresh source rechecks.                    |
| META-5    | Admitted bounded import/source/format.                   | Retained; Preview versus Stage and invalid/stale draft refusal.                                    |
| META-6    | Escape/dirty-close/authority and entered work.           | Retained; same draft, safe close decisions and no owner revival.                                   |
| META-7    | Effect-named actions versus inspection.                  | Retained; actual handle/retry/cancel/unknown and no second persistence.                            |

Full Design B adds the ten value families and F01–F08 gates in [full system](full-system.md); no retained complete-system requirement is declared done merely because this 0.4 matrix is covered. Source-clause lineage supplements the curated maps with source digest/line spans and explicit owning disposition; historical facts are not transformed into current runtime claims.

The [lineage manifest](lineage.json) indexes all 1,138 nonblank source units from twelve selected objective/intent/baseline resources: 960 retained, 50 transferred/deferred, 44 expanded into the 0.4 design and 84 outline/status-analysis units superseded by the detailed contracts. Paragraph/table/list/code units preserve exact source digest and original line span; they are conservative source coverage, not 1,138 automatically proved semantic requirements. Curated O/C/U/inherited38/family maps and the decision/walkthrough audit supply the meaningful contract review. The [source manifest](sources.json) retains 33 external fetch identities and 18 current local source digests without copying upstream code into product modules or exposing private store paths.

## Adversarial design walkthroughs

These are WALKTHROUGH checks against the written contracts and inspected actual service boundaries. They are not tests of a future implementation.

| ID                         | Sequence                                                                                               | Contract result and contradiction avoided                                                                                                                                   |
| -------------------------- | ------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| W01 source replacement     | Inspect P at sourceS1; source changesS2 before accept/capture; old completion lands.                   | Picker stamp/target recheck refuses and preserves raw/query; no staging or old overlay. This uses existing ReferenceSelection source guard plus new capture-basis identity. |
| W02 invalid/rejected input | Actor draftR valid, native epochE gains rejected text; valid sibling edit reachesR+1; request preview. | Native input guard refuses complete capture; it cannot use older typedR or ignore sibling. Overlay is not a repair bypass.                                                  |
| W03 READ revocation        | Captured candidate/provider entered; READ lost before native output/publication.                       | No new disclosure/native use; cancel/drain and owned release continue without restoring WRITE/editor executor.                                                              |
| W04 rapid demand           | A entered; B thenC demanded; A ignores cancel until return.                                            | One latestC unentered; A reservations charged until drain, obsolete result discarded; no parallel compiler/output publication.                                              |
| W05 reorder/delete         | Focused invalid rowA moves, then is removed and reintroduced.                                          | Logical row state moves by key; old attachment callback STALE; no index substitution or unexpected edit.                                                                    |
| W06 reflow/locale          | Query/caret/disclosure/operation/camera active; A→B→A and font/language changes.                       | Same owner/state/handle, new geometry/native attachment as needed; no history visit/save/prepare from layout.                                                               |
| W07 saved/failed apply     | Write succeeds; native application fails; an Errored fallback resets.                                  | Confirmed Saved remains; reset only view construction. Actual application-only command retries saved revision without persistence.                                          |
| W08 unknown write          | Owner reports unknown after entered persistence and client leaves.                                     | Preserve uncertainty; new authorized observation/reconciliation, no implicit retry or rollback.                                                                             |
| W09 native shutdown        | Close/reset after actual extracted use, then renderer shutdown.                                        | Closed interest blocks uses; actual tickets/futures own lifetime until retirement/destruction observation. Timers/frame counts are not release proof.                       |
| W10 factory/parts          | Custom surface omits label/action state or factory throws after partial allocation.                    | Declaration refuses/partial cleanup attempted; actual owner retained; no decorative “working control” or leaked resources.                                                  |
| W11 branch suspension      | Hidden detail has raw invalid ID and held preview; Home shown, then return.                            | Hidden native/semantic descendants gone; input bookmark retained; preview retired/drained and no automatic return prepare.                                                  |
| W12 pure cycle             | A depends onB andB onA, or a projection calls an edit.                                                 | Typed cycle/write-in-pure-phase diagnostic and scoped failure; no per-frame side-effect loop.                                                                               |
| W13 remote replacement     | Partial transfer/reply from server epochX lands after reconnect toY; same document IDs.                | Epoch/peer/request/schema checks reject and release quotas; no replay, ghost success or cross-server edit.                                                                  |
| W14 structural merge       | Collection lacks persistent keys; client has redacted field; saved source changes during review.       | Whole-field conflict/private unavailable maintained; server owner validates full candidate; stale merge proposal cannot save.                                               |
| W15 expression growth      | Small expression would cause huge precision/output or expensive excluded operator.                     | Policy preflight/incremental bounds refuse before expansion; timeout alone is insufficient; no IO/effect execution.                                                         |

## Design validation and implementation gate

Design review must inspect all cross-document types/actions and contradictions: one owner, no hidden save, same key versus attachment, current draft versus audition/loaded input, real operation versus view loading, suspension versus retirement, native closure versus release completion, and no private source access in debug/remote observations. No unresolved issue may silently change those meanings.

Executable design probes are justified only by a concrete uncertainty. No simulated actor or independently invented expected-state test proves actual native behavior. Documentation validators check links, source and required-case coverage, vocabulary/disposition and diff scope. Proposed examples have not been compiled; the native metrics, exact limits/default tuning, public binary compatibility, remote channel details and third-party provider admission remain explicit future implementation/qualification gates.

The final design audit must record actual commands/results, every O/C/U/inherited38/F package disposition and the source-clause coverage count, exact research/source/document identities, archived-before intent hashes, canonical intent readbacks and remaining limitations. Preserve original artifacts/evidence and the user-owned preview. Mark this design goal complete only when those design deliverables and checks are present; keep all product/native/public-delivery gates separate.
