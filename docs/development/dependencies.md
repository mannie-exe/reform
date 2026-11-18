# Local dependent settings

`FormDependencies` declares same-document presentation availability. A rule names a stable ID, a target field or subtree, one boolean/choice/text input, accepted literal values and a localized explanation for a known nonmatch. Several rules affecting a field combine with AND. A gate applies to descendants without disabling its independent controller.

Declare these through `FormPresentation` alongside optional numeric ranges. `bind(type)` checks declared shapes and returns an immutable evaluator. It invokes no codec, value constructor, snapshot callback, source lookup or owner operation. Unknown fields, incompatible input kinds, out-of-shape matches and prefix-aware cycles produce `InvalidDefinition` with structured issues and related input paths. Static declarations cannot bind session-row UUIDs. Dynamic row templates and cross-document derivation are outside this local baseline.

There are at most 128 rules, 64 accepted strings per rule and 128 segments per path. Match strings, paths and captions have explicit representation bounds. Evaluation follows a checked dependency order. Reasons identify immediate inputs instead of recursively expanding upstream explanations; the maximum reverse-declared chain is covered by a core control.

## Evaluate cached input evidence

Pass the current immutable draft and its corresponding `EditorActor.View.inputs()` to `Bound.evaluate`. The actor publishes these per-input states with the validation report from one worker inspection. Reading them performs no new validation. Standalone editing consumers can explicitly inspect a draft on their admitted worker and pass that inspection's input map.

The evaluator distinguishes:

- `AVAILABLE`: all applicable rules match known, active, individually valid input.
- `DISABLED`: at least one applicable rule is a known nonmatch.
- `UNKNOWN`: no known nonmatch settles the conjunction, but a required input is absent, inactive, invalid, unobserved or awaiting local admission.

A gated controller is not treated as an invented default or false value. A pending-input set lets a frontend with unadmitted local text invalidate just the relevant input observations. A source remains individually known when an unrelated field or complete-object invariant fails; using aggregate validity to gate every field could prevent repairing that object.

Only declared literals and cached input evidence participate. Numeric comparisons, arbitrary callbacks, expressions, IO and world queries are not predicate facilities. Input codecs retain their actual validation; metadata checks do not execute custom codecs to guess whether every declared literal could occur.

Availability has no authorization or persistence meaning. It never changes a draft, removes an inactive field, alters saved encodings or suppresses complete-candidate owner validation. A headless editor can still explicitly stage such a field under its actual owner policy. Owners enforce domain invariants independently of this presentation metadata.

## Native presentation

The generated form leaves dependent fields visible. Inactive text stays focusable and read-only; buttons, numeric steps/sliders, reference browsing and structural actions use the same local decision. The callbacks also recheck current availability, so invoking an old inactive action cannot bypass its disabled widget. Current controller input awaiting worker admission makes its dependents unknown.

Each affected field has a separate, focusable Availability action with localized reasons and the immediate input label. Its narration and visible summary remain usable when an inactive native button itself cannot receive focus. Container summaries and child controls share the inherited gate. Reenabling a controller exposes the original retained input, including invalid drafts; the ordinary owner validator still decides whether Save can proceed.

The [public native example](../../examples/native/README.md) supplies the independent JSON owner and disposable client fixture. [Verification](verification.md) owns actual runtime observations and omissions. Advanced dependency/inheritance systems remain deferred.

## Update boundary

Keep per-input worker observations, declared-path semantics, dependency admission and native callback checks aligned. Do not replace these observations with aggregate report validity or run `Snapshot.inspect()` during drawing/narration. Changes to optional/default/variant activation require rerunning inactive-source and prefix-cycle controls. Changes to native focus/input require actual read-only typing, keyboard explanation, container-action and save/reopen checks.
