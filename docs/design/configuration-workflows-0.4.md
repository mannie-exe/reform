# Configuration workflows for 0.4

**Design supersession:** [Semantic interaction architecture](semantic-ui/README.md) owns the revised unpublished0.4 interaction contracts and the connected full reference design. This document retains the inherited baseline wherever not explicitly replaced; all38 acceptance obligations remain attributed. New semantic APIs are proposed, not available in dev.96.

This is the owner-selected shared configuration-workflow contract for [presentation 0.4](presentation-0.4.md). The intent, source/Mobbin review, static A/B proposals, and owner review are complete. The owner selected both arrangements responsively: A is preferred when adjacent content fits; B serves narrower/taller contexts. This document records that final design decision, not an implemented or natively qualified interface.

Static UI mockups and existing native evidence are permitted for this review. They do not authorize product code, candidates, consumer migration, native client launches, publication, or personal rollout. Illustrated feature content, a Wayfinder demonstration, RespackOpts, and Phase 2 remain outside the selected work.

## Shared scope and ownership

re:form owns selector/manual-ID containment, chooser layout, form and metadata navigation, draft/inspection/review views, document actions, source/conflict/import/close decisions, and operation/result presentation. Consumers still own field purpose, profile titles and source metadata, grouping, actual effects, persistence/application policy, and authority. A visual redesign cannot create a second editor, resolver, comparison backend, or writer.

The Lootr handoff selects four shared concerns for design: separate searching/choosing/inspecting, contain manual-ID expansion, name immediate actions by their actual effect while keeping result navigation separate, and project navigation from actual visual groups/rows. Native 0.3 evidence confirms its navigation/body order mismatch. Do not fix presentation by changing order-sensitive schema/codec declarations.

The chooser hierarchy, contained repair, truthful actions, and visual navigation order are selected requirements. Redundant IDs and source revision/UUID details leave the ordinary selection path; exact identities and diagnostics remain available in inspection.

## Review families

| Family                       | Existing views to inspect                                          | Job                                                                                                                          |
| ---------------------------- | ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| Choose or repair a value     | Titled reference, manual ID, reference/collection picker           | Find a value, understand the selection, stage or repair it without saving                                                    |
| Understand a draft or source | Form information, source/effective inspection, document actions    | Identify the current value, draft, saved/effective state, origin, and actual action scope                                    |
| Review and resolve changes   | Single/batch review, conflict, import review, dirty-close decision | Compare captured values, repair/resolve/stage explicitly, and save only through the actual owner                             |
| Run and inspect an operation | Workflow actions, progress, retained result/status and retry       | Start the named effect, understand pending/partial/failed/unknown outcomes, and inspect or recover without replaying a write |

Use a small number of shared layout directions across these families, rather than inventing unrelated styles for every screen. Existing source-only behavior is evidence; a screenshot proves only its captured state. Inventory unsupported or uncaptured states honestly. Review wide, narrow, and high-scale behavior as design constraints; static proposals do not qualify native behavior.

## Preserve

Keep default A and its stable ordinary-navigation header, separate splash-like Home, native Minecraft styling/typography, useful multi-column groups, information access, and visual/navigation reading order. Body and auxiliary layout may change while retaining the same editor and bookmark. Do not replace the selected shell with another unrelated redesign.

Preserve invalid/unaccepted input, caret/focus/viewport, omission versus explicit values, and requested versus effective fallback. Keep draft, saved, loaded, effective, and captured-review values distinct. IDs, paths, revisions, and technical causes remain inspectable; their placement may be less prominent in ordinary flow.

Keep Defaults, Revert, Reload saved, Import as draft, Compare, Resolve in draft, Save, Apply, Export, Start, Inspect result, Retry application, and Cancel distinct. Whole-document/review-scope effects include unshown values. Inspection and navigation do not perform writes. A source conflict or stale review cannot become an apparently current confirmation after relayout.

Progress and terminal results come from existing typed operation facts. A confirmed save survives application failure. Cancel is a request, Back does not mean cancellation, unknown outcomes retain reconciliation, and no restyled retry repeats an entered write. An operation destination reveals the operation; a named immediate-action button starts it explicitly.

## Review boundary

Mobbin supplies structural precedents only. Retain this product's colors, native font/control semantics, source data, and real ownership. Each A/B direction must explain where displaced information goes, its narrow fallback, and any judgment unsupported by a reference. Mockup labels must describe real effects or be marked proposed; do not fabricate successful runtime results.

The owner selected the same responsive policy for chooser, inspection, and operations. Preserve the historical A/B proposals as design evidence. Native visual approval, measurement, and behavior qualification belong to later implementation; the static boards do not establish them.

## Selected responsive arrangement

The following A/B letters concern task layout, independently of the already selected default header A. They are two arrangements of the same task and state, not separate modes, editors, or user settings.

| Task       | Preferred A when adjacent regions fit                                                      | B when available space favors a focused/vertical arrangement                                                                       |
| ---------- | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| Chooser    | Search and candidate list beside selected-profile facts and contained manual repair        | A wider list with contained selection/repair below it; bounded scrolling or an explicitly opened detail destination when necessary |
| Inspection | Selected field/task context beside captured comparison, application, and source details    | A focused comparison body with vertically arranged facts when its columns cannot fit                                               |
| Operations | Named immediate effects beside the selected operation inspector and retained-result access | Named effects and local descriptions in a vertical flow, with explicit retained-result destinations                                |

Choose A whenever the actual content offer can comfortably contain its regions. Choose B when narrow/tall windows, GUI scale, translated text, native controls, or required status/actions leave insufficient room. Aspect ratio alone is not a breakpoint: a tall window that still fits A may retain it. Measure usable body width and height after shell/chrome allocation, using resolved semantic metrics and the actual language/font/control minima. Do not hardcode a screenshot width, shrink native targets, hide errors, or clip details to keep A.

Both arrangements are mandatory shared implementations over one semantic composition. Consumers receive the automatic A-preferred policy by default. Custom presentation can change slot offers, theme, or the preferred arrangement within the same measured containment rules; it cannot force an unusable layout or omit the fallback. No second consumer renderer or settings file is needed.

Reflow preserves the selected candidate/field/operation by stable identity, search/filter, disclosure, exact manual input, diagnostics, draft/source/capture/operation identity, and focused control/caret. Retain region-relative scroll anchors and restore the focused content in the newly mounted tree; do not reuse a stale absolute offset or old widget. Reflow changes neither the task's logical destination nor visit history. An explicitly opened result/detail destination still has its ordinary Back behavior. Returning to A restores the same task context rather than opening another owner.

Resizing, scale/font changes, and showing a disclosure do not stage, validate, resolve, save, refresh a source, start/retry/cancel an operation, or prepare a preview. They only rearrange cached state. Required errors and unavailable reasons remain visible near their controls in either arrangement; long content scrolls within measured containment. Keep arrangement selection stable between actual measurement invalidations rather than letting scrollbar or hover changes oscillate it. The stable-header contract remains independent of task-body reflow.

Related metadata/review views use the same shared composition and detail primitives where applicable. Full comparisons, conflict/import decisions, and batch owner outcomes retain their task-specific information and effects. A bounded dirty-close decision remains a decision, not a mandatory multi-pane dashboard or an extra review gate.

## Reference directions

The reviewed static proposals cover a profile chooser, captured source/value inspection, and operations. Their shared header, native palette, title-first choices, contained manual repair, and effect-named buttons are common constraints. Their arrangements now serve the responsive policy above. The remaining views below have behavioral/layout contracts, not new native captures or implementation.

**A: Details beside the current task.** A chooser keeps search and candidate rows in one region and selected-profile facts/manual repair in a separate inspector. Field inspection retains the selected field and shows baseline, captured draft, effective observation, and application requirements beside it. Operations use named immediate buttons with a separately selected inspector and result destination. This costs body width; the inspector becomes an explicit focused destination when measured content cannot fit. Do not squeeze three columns into every window or overlay the focused editor.

The structural precedents are [Productboard's selected setting pane](https://mobbin.com/screens/83b29d19-ddcf-46c6-a28e-eae4d23eb180), [Plain's selected-item details](https://mobbin.com/screens/9ff39f43-bdf9-4ae4-8c9f-b736cfacdcde), and [Databricks' operation/history and details separation](https://mobbin.com/screens/1bd9f80c-3c2d-46ba-bd06-b6177a78ebdb). They support adjacent context, not Minecraft-specific input, permission, or persistence behavior.

**B: Focused task views.** A chooser gives the list the available body width, with a contained selection/repair area below it. Inspection and review use the body for comparison and application/source facts, returning to the same field/editor bookmark. Operations keep a short effect description beside each immediate button; retained results open a dedicated destination. This costs vertical space and more transitions between editing and inspecting. It does not insert a mandatory review step into a permitted direct save.

The structural precedents are [Klaviyo's search/list/confirmation picker](https://mobbin.com/screens/dd3938ef-dd50-42f9-8766-e0d1d2b7ea5d), [Square's focused selection task](https://mobbin.com/screens/9ab71ea9-dd45-4801-a0a4-249028e9838e), [Figma's captured comparison view](https://mobbin.com/screens/ebb90bf9-735a-4f5f-968a-8152ff58dde8), [Semrush's focused original/new review](https://mobbin.com/screens/ed82aafc-c4dc-442d-87fc-359f7347f940), and [Motion's focused task details](https://mobbin.com/screens/2bd6cc77-0af2-4557-a4f6-42fe1309e1ee). They support task separation and comparison structure, not the exact labels, native controls, or owner effects.

Exact dimensions, narrow fallback, disclosure behavior, and the proposed wording are design judgments constrained by existing native contracts. No reference demonstrates a suitable Minecraft breakpoint or certifies the safety of saving, resolving, or retrying.

## Common workflow requirements

These requirements constrain both responsive arrangements without adding a new backend.

| View or transition                             | Proposed presentation                                                                                                                         | Existing effect and retained state                                                                                                                                                                           |
| ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Reference selection                            | Search toolbar, titled candidate list, selected-value facts, separate technical details, and explicit confirmation                            | Search/inspection do not save. Preserve the requested ID, source context, unresolved/missing/unavailable distinction, bounded result accounting, and actual selection admission.                             |
| Manual ID repair                               | A contained disclosure belonging to the original field; a collapsed error remains visible beside its title                                    | Disclosure alone has no effect. Preserve rejected native input, error text, caret, focus, and requested versus resolved identity; confirmation stages through the same actor.                                |
| Field information and source inspection        | Ordinary purpose and current/draft/saved/effective facts first; exact path, ID, revisions, and complete diagnostics under inspectable details | Preserve capture identity and freshness. Never label a captured observation as the current source or run an implicit refresh on navigation.                                                                  |
| Document actions                               | Separate edit history, source/default actions, and inspection/result navigation; explain whole-document scope once                            | Keep Undo, Redo, Defaults, Reload saved, Import, and Discard draft distinct. Use owner-supplied defaults labels. Unshown fields remain in the actual document scope.                                         |
| Single-document review                         | A changed-field list and captured comparison with validation/application facts and explicit owner submission                                  | Save is conditional on current native-input, validation, permission, source, and review guards. Viewing or refreshing a review is not a write.                                                               |
| Batch review                                   | A document index, per-owner comparison/requirements, and ordered outcomes                                                                     | No invented cross-document transaction. Retain earlier confirmed saves after a later refusal; expose unknown writes for reconciliation, not blanket retry.                                                   |
| Conflict review                                | Field index plus earlier/observed/draft comparison and explicit per-field choices                                                             | Choose every required field. Resolve changes the draft and resets its Undo history; it does not save or apply. Collections remain whole choices. Stale context requires a new review.                        |
| Import review                                  | Choose an admitted source/format, inspect differences and diagnostics, then explicitly stage                                                  | Preserve source identity/schema facts, repairable invalid input, and stale-target refusal. Staging changes the draft only; neither selecting nor closing a chooser writes.                                   |
| Unsaved close                                  | A bounded decision naming the affected document(s), with Keep editing, Discard draft, and the owner's guarded save choice                     | Escape keeps editing. Do not conflate draft abandonment, cancellation, navigation, and saved application. A proposed Review link would open review rather than silently save.                                |
| Operation entry and result                     | Name each immediate effect; separate selecting an operation, starting it, and inspecting its retained result                                  | Keep the existing action and operation scope. Show unavailable reasons locally. Result links require an actual retained operation; do not draw empty success histories or fake completion.                   |
| Pending/failed/unknown operation               | State, reported progress, affected source/runtime, useful diagnostics, and valid recovery choices                                             | Back leaves work running. Cancellation is a request, not rollback. Confirmed saves survive application failures; application-only recovery cannot repeat persistence. Reconcile unknown writes before retry. |
| Context loss, stale data, or missing authority | An outcome beside the affected task, with safe return/refresh when actually available                                                         | Preserve edits and refuse unauthorized/stale effects. Missing provider, unavailable catalogue, resolved-empty, invalid draft, and unknown operation outcome remain distinct.                                 |

Both directions must retain information access from every supported control, local persistent errors, useful narration, keyboard reading order, and the selected visual navigation hierarchy. Richer grouping must not require schema/codec reordering. Displaced technical facts remain fully inspectable and copyable within existing privacy/capability boundaries.

## Construction and later implementation

The owner selected A-preferred automatic adaptation for all three task families, rather than one permanently fixed arrangement or the earlier proposed split by task. Operations retain one consistent distinction between immediate effect and result inspection in either arrangement.

[Construction and lifecycle](presentation-0.4-api.md#responsive-task-presentation) defines the proposed declaration and retained-state boundary. [Migration and acceptance](presentation-0.4-migration.md#shared-configuration-workflow-acceptance) defines later observable checks. Consumer authoring supplies semantic groups, title/help, and effects, not renderer coordinates or private replacement screens. The API names here describe the design boundary; implemented APIs and version-bound qualification belong in the development references. This design record does not itself authorize implementation or adoption; the separately selected execution owns that work.
