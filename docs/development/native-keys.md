# Native keybinding owner

`ClientKeyBindings.clientKeys(modBus, container, document, mappings)` is the ordinary physical-client registration for existing native Controls. Its supplier returns actual addon-registered `KeyMapping` references. The factory supplies lifecycle, owner context, bounded workers, generated controls and optional native commands; an ordinary addon supplies no executor, codec, writer or editing session. It neither registers mappings nor creates a preferences file.

`KeyBindingOptions` remains the explicit owner adapter for consumers composing their own admitted context. It binds supplied, already registered mappings to the actual running client's `Options`. `KeyBindingEditors.forSession(session)` supplies native capture/reset/conflict controls for that same generated form. Client types stay outside core and dedicated execution.

## Ordinary registration and entry

Register during the owning physical-client mod's construction, before FML client setup. The document namespace must match the actual mod container. Native and [JSON documents](client-files.md) share identity admission: another owner cannot reuse the same document ID. Metadata registration performs no IO, does not invoke the mapping supplier and publishes no invented definition or runtime value.

Lifecycle states are `REGISTERED`, `WAITING_FOR_NATIVE`, `READY`, `UNAVAILABLE` and `CLOSED`. FML setup arms a one-shot `ClientTickEvent.Post` listener because the final native mapping array is registered later in Minecraft bootstrap than the Options constructor. That first admitted tick removes the listener and invokes the supplier once on the native client thread. Invalid or absent mappings produce UNAVAILABLE with their report and unchanged options; the factory does not poll, substitute defaults or retry an unchanged native registration.

`definition()` is absent until the actual owner is bound. READY describes that binding, not successful saved-versus-loaded observation or a persistence guarantee. Editor reads retain the validation below. `initialization()` reports the historical first binding outcome; cancelling a returned future copy does not cancel startup. Shutdown removes pending admission/command listeners, closes the context and publishes CLOSED.

`screen(parent, title)` opens the generated native key form, or a measured waiting/error view with Back and no invented retry. `registerMenu(title)` explicitly adds a native mod configuration entry and refuses replacing an existing factory. It installs no HUD, inventory control or shortcut. A failed screen constructor closes the session, actor and operation resources it opened; the same construction boundary protects JSON forms. Successful construction transfers that editing lifetime to the screen.

`registerCommands("bindings")` explicitly contributes one client command root; `commandBranch(literal, output)` permits native composition. Both use the [shared operations](operations.md), actual current native recipient and ready owner, retaining a command draft separate from GUI drafts. Suggestions create no editor. An explicit command Save uses the actual Options writer; no JSON mirror, server authority or synchronization is introduced. A second root cannot silently replace another native command.

The physical-client runtime shares two workers and a 256-task queue across document formats, with separate 128-document and 128-managed-context budgets. Key documents close through their registered owner, without also consuming a creation-context slot. [Client JSON](client-files.md) owns the common initialization/error and worker conventions.

Construct it on the actual owner executor with its `OwnerContext`, native `Options`, a logical document ID and admitted mappings. Each instance must occur exactly once in native Controls under a unique name. Equal names do not substitute another object. An unavailable/replaced Options or mapping context is refused. Registration defaults remain the native default key and modifier; the library does not change Wayfinder or another consumer's registration.

The editing definition contains a record per admitted mapping, with `Binding(key, modifier)` values. The key is its canonical native name; the modifier is NeoForge's actual `KeyModifier`. These are editing inputs, not a JSON storage schema. The native options version and serializer retain ownership of the file format.

## Observe and prepare

`session(access)` supplies the common `DocumentSession` interface used by `EditorActor` and `DocumentActions`. Sessions retain actual context/access and have bounded lifetimes. The standard owner admits 64 mappings, 64 sessions and sixteen queued operations, with a 256 KiB native-source read bound. Conflict review separately admits at most 4,096 native Controls entries. Consumers may explicitly choose other supported bounds. No file is initialized during construction or browsing.

Reads capture actual native bindings and conflict-context identities on the client executor, then read the real `Options.getFile()` on a worker. The read-only `NativeFile` snapshot is shared with the registered FML adapter; it retains exact bytes, file identity checks, strict UTF-8 and a revision. It writes no file. Source names and revisions grant no authority.

Only admitted `key_<native-name>` entries are interpreted. Missing entries retain native registered defaults; unrelated options stay under Minecraft's parser/writer. Duplicate admitted entries and invalid key input are unavailable. Native modifier decoding/normalization follows the pinned public API. Saved and loaded bindings are separate observations: a mismatch does not authorize overwriting the loaded value or silently calling a global options reload.

Preparation retains the actual issued observation/session and complete candidate. It checks fresh READ, validates the editing definition, then checks canonical native keyboard/scancode/mouse input and modifiers on the client executor. Numeric names are bounded before native cache lookup. A modifier matching its primary key is refused rather than staging a value the native setter would silently change. Preparation changes neither the mapping nor the file and grants no WRITE authority.

Native names `key.keyboard.0` through `.9` denote the ordinary digit keys, not raw key codes zero through nine. They retain that symbolic meaning through validation. Native source aliases may normalize during observation; newly authored input must use the canonical native name.

## Capture and draft review

Pass `KeyBindingEditors.forSession(session)` to the `SettingsScreen` constructor's [auxiliary-editor registry](presentation.md#auxiliary-field-editors). The registry retains the actual owner definition and registered mapping names. It does not infer another file, register preview mappings or replace the form's actor/services. The generated row retains a **Show fields** repair route.

`KeyBindingScreen` holds a local prospective binding until **Apply draft**. Keyboard capture waits for the recorded key and modifier to be released. A modifier alone remains a valid primary key; repeated modifier events do not replace the primary. Native Control naming and Command/Super behavior on macOS use NeoForge's `KeyModifier`. Release-only macOS Fn/scancode 63 and unrelated releases do not finish capture. A mouse button can be the primary, including a modifier held within this capture. While capturing, Escape unbinds; **Cancel capture** preserves the earlier local selection. Losing window focus stops capture while retaining that selection.

**Reset to default** selects the actual registered default locally. **Back** discards only the local selection, retaining the shared draft. **Apply draft** sends one exact-version `SetRecordScalars` action through `DocumentActions`; key and modifier therefore share one Undo step. Invalid input is retained until an explicit replacement, and unrelated/unknown fields are not silently removed. Native Controls and the options file change only through the parent's separate Save. A changed draft/context disables the obsolete popup, and retired widget callbacks cannot apply to its successor.

Conflict review is an explicit bounded operation, not a rendering callback. It validates the complete draft, including other unsaved admitted bindings, and compares against the current native Controls array. If the draft matches current bindings, it calls actual native conflict methods. Unsaved bindings use the pinned default context/modifier rules, including the native equal-key fallback; the screen labels that projection and warns that custom mod dispatch may differ. The preview constructs no temporary `KeyMapping`, whose constructor would alter global registration. Conflict warnings neither grant Save nor become a general input-routing guarantee.

Malformed sibling input, unavailable authority/context, changing native bindings and exhausted scan budgets supply no complete conflict result. They are displayed as unavailable rather than zero conflicts. New selections retire earlier asynchronous results, and refreshes coalesce while one review is pending. Native save validation remains independent of these advisory warnings.

## Commit through Options

Commit consumes the issued proposal, including a refusal. It requires fresh WRITE, the same native bindings/conflict contexts and exact source bytes. A no-op invokes neither the serializer nor map refresh. A real commit sets only changed admitted mappings, refreshes the native lookup and calls the actual `Options.save()` once. Its normal whole-options serialization preserves unrelated current native values; re:form supplies no replacement serializer or direct writer.

Native setters can run consumer code. After each setter, the owner checks its lifetime, current authority and expected partial binding state before continuing. It repeats native/source checks before saving. Reentrant closure stops later refresh/save; a setter that already entered may have changed a native value. The adapter reports that partial effect and does not claim rollback. Minecraft or another owner can subsequently save that remaining native state.

Readback separately reports observed persistence, actual map refresh/application and whether native Save returned. Returning from the native method alone does not prove persistence: its writer can catch errors internally. Failed/unavailable readback retains execution facts and an unknown result. Closing during an effect drains to that truthful receipt; it cannot become an unattempted write. Separate fresh access remains necessary to disclose observed values.

These checks coordinate an admitted native operation, not arbitrary processes racing the final write. They do not promise an atomic filesystem/permission transaction or sandbox consumer callbacks. Conditional failure preserves the draft for review through the existing [owner](owners.md) and [conflict](conflicts.md) services.

## Qualification and updates

The independent addon registers two ordinary native Controls entries; the library receives those existing references through the public factory. `--keys-factory-only` selects native binding timing, invalid-provider availability, repeated failed native/JSON view construction, GUI/command editing and fresh-JVM reopen. `--keys-owner-only` separately selects explicit-owner checks: actual serializer, modifier syntax, symbolic digits, unrelated current options, native mouse lookup, source/live conflicts, authority, one-use/session identity, malformed source and setter closure. `--keys-controls-only` exercises the public generated form, capture/reset/repair/history/conflict controls, raw disclosure, stale input, 320×240 reflow and native Controls reopen. These invoke actual native event methods, not physical input devices. [Verification](verification.md) owns exact artifacts and outcomes.

Recheck native setup versus `ClientHooks.init`/mapping registration, first-tick admission, `Options.getFile`, key field serialization, `Options.save`, `KeyMapping.setKeyModifierAndCode`, `resetMapping`, symbolic/numeric names and default/conflict semantics after dependency updates. Review `KeyBindsScreen`, `KeyBindsList`, `KeyMapping.same` and `IKeyMappingExtension.hasKeyModifierConflict` together; context overlap alone does not describe the pinned fallback. Requalify key-release/modifier handling, macOS Control naming and Fn-only releases. Native Controls remain available, and committed mappings must reopen there normally. Consumer source migration remains separate from library development.
