# Native client bindings

Portable client APIs use `design.inherent.reform.minecraft.client`. A `ClientRegistration` binds them to the actual owning loader container, configuration root, setup callback, command source and menu entry. It supplies no configuration schema, serializer, gameplay authority or settings override layer.

The 26.3 native matrix APIs complete JOML 1.10.9 classes whose class-file version is 46 while containing Java 8 type annotations. Actual `Matrix3x2fStack` use triggers javac's external `classfile` warning; caller-level suppression does not suppress that dependency-completion warning. Only the 26.3 native projects and their independent platform consumer disable `classfile` lint. All other lint and `-Werror` remain enabled. Recheck this exception against the actual JOML input when changing game targets or native dependencies; it is not permission to suppress unrelated compiler warnings.

NeoForge consumers create `NeoForgeClientRegistration.of(modBus, container)` from `design.inherent.reform.neoforge.client`. Fabric consumers create `FabricClientRegistration.of(container)` from `design.inherent.reform.fabric.client`, using their actual Fabric Loader container. Construct these on the physical client before native setup. The Fabric binding checks that its supplied container is the actual registered instance. Neither binding selects itself from foreign classes; Connector cannot cause a second bootstrap.

Pass the registration to the ordinary factories:

```java
var settings = ClientFileSettings.clientJson(registration, definition);
var keys = ClientKeyBindings.clientKeys(registration, keyDocument, () -> List.of(existingKey));
```

These calls register bounded metadata. They perform no source IO, register no keys and invent no effective values. [Client files](client-files.md) owns file formats, initialization and application semantics; [native keys](native-keys.md) owns actual Controls persistence. Caller-owned backends remain usable through the same form and operation APIs.

## Native lifetime

NeoForge setup uses the supplied mod bus's `FMLClientSetupEvent.enqueueWork`, then delivers the callback through the actual Minecraft client executor. FML 12's deferred queue runs on a worker while the client waits for loading; enqueueing work alone does not establish native client-thread ownership. The handoff does not wait for the client to execute it. Initialization status retains its separate completion. Native registered settings use the same bridge. Fabric uses its actual `CLIENT_STARTED` callback. Fabric installs one process bridge, with at most 128 pending setup callbacks and 128 command roots. It clears pending setup callbacks after startup and refuses registrations made after that boundary. This is a native startup binding, not an FML registry substitute.

The common client runtime has one native tick/shutdown subscription per selected loader. Independently retiring tick callbacks share a 128-entry budget; opening another screen does not install another permanent Fabric event listener. Keybinding registration uses one such callback to wait for the final native mapping array, then removes it. Document shutdown closes owners, retires command/tick registrations and clears context references. Expensive editing and file work keep their existing bounded workers and owner executors.

`onTick` is a runtime operation on the native client thread. From a mod constructor, register it through `onSetup`; the native Minecraft instance need not exist during construction, particularly on NeoForge 26.3. Ordinary factory metadata remains declarative. An early runtime call refuses with a lifecycle explanation instead of dereferencing a missing client.

A bare library does not initialize settings, menus or workers. Its client-only chat hook records a temporary synchronous invocation frame without initializing the settings runtime. Dedicated-server loading must not resolve these client classes. [Verification](verification.md) records actual native qualification separately from compilation.

## Commands and invocation origin

`registerCommands(literal)` contributes the ordinary local settings tree. For composition, `commandBranch(literal, source, output)` takes the selected binding's `commandSource()`. Import-aware file branches also take their admitted import catalog. The native source type remains Brigadier's actual type: NeoForge uses `CommandSourceStack`; Fabric uses `FabricClientCommandSource`.

`SettingsCommands.Binding.requireInvocation` is an initial execution check. It is separate from current actor, connection, context and permission checks used during later delivery. The shared tree calls it before opening an editor or exposing an operation result/cancellation. Registered import metadata and suggestions do not open sessions or read files. Server policy bindings retain their independent native permission checks.

All four targets require the verified native typed-chat entry, scoped through `ChatScreen.handleChatInput` with a current chat screen, native client thread and local-history submission. The frame retains the actual connection/player/screen only during that call and is restored in `finally`. Fabric 26.3 also checks its native `attended()` capability. That flag alone does not establish invocation origin: Fabric command API 3.1.2 passes its full attended source to execution after checking a restricted source for confirmation. The guard therefore runs before resolving an editor or admitting an operation and still requires the typed frame. Component RUN_COMMAND follows another native path and gains no such authorization. Users can type or explicitly submit a suggested local command; a server command link cannot inspect or change client settings.

An admitted asynchronous command may finish after the chat frame closes. Retained delivery therefore rechecks the real recipient and owner context without demanding another typing frame. An old connection, closed owner or changed authority still refuses disclosure. Cancellation remains a request rather than proof of rollback.

## Optional menus and composition

NeoForge uses the actual `IConfigScreenFactory` extension and refuses replacing an existing factory. Fabric's optional `ReformModMenu` entry supplies registered consumer factories through Mod Menu's provided-factory API. Mod Menu is compile-only, with separate game-specific pins, and is not required on a client or server. Without it, consumers can open `screen` or compose `ClientForm` through another native entry point.

A consumer that declares its own Mod Menu entry point must compose re:form through that entry instead of calling `registerMenu`. The Fabric binding refuses that competing ownership by inspecting lazy entry-point providers without instantiating their optional API classes. Duplicate re:form menu declarations also refuse replacement. Menu declarations are bounded, and none is installed for re:form's own library identity.

Custom native owners can compose the same forms through `ClientEditors.open`, `ClientForm.of` or the explicit asynchronous `ClientForm.PendingScreen` contract. A pending screen carries its actual completion and cancellation request; it does not claim that cancellation closes an entered native operation. Status views keep ready/retry/message state separate from an owner's persistence or application receipt. Consumer callbacks retain their ownership and executor obligations.

A retained editor separates native navigation from field access. `ClientEditor.screen()` returns its actual top-level entry, including a composed workflow's shared root; `ClientEditor.form()` returns `SettingsForm<T>`. Use the form interface for field focus, controls and editor state. It grants no extra owner authority and cannot accidentally be opened as another native screen. `NativeScreens.open` is idempotent for an already current non-null screen; a null destination retains Minecraft's native return behavior.

## Native controls

The cycle-control seam takes the choices together with the current draft value. On 1.21.1, configure `withValues` before `withInitialValue`: the latter resolves the native cursor immediately. Supplying the value first can display the correct label while leaving the cursor at index zero. The 26.3 builder instead resolves the initial index at widget creation. Shared variant, scalar, unit, import, conflict, review and workflow-filter controls use this seam for both collections and enum arrays. Preserve this ordering when updating the native adapter; rendering the expected label alone does not qualify a reconstructed selector.

The information glyph selects the supplied `minecraft:uniform` font on its own bold Component. The internal font adapter uses a resource ID with 1.21.1 `Style.withFont`, and a `FontDescription.Resource` with 26.3. It bundles no font and changes no global font option. When updating game text APIs or font resources, recheck both styling and the actual rendered information control; its subject tooltip, narration, and native input remain shared.

Native narration output also differs. The 26.3 output interface carries an explicit mouse, keyboard or system trigger. Product widgets receive that context from Minecraft; diagnostic collectors must implement the actual target interface rather than inventing a common trigger on 1.21.1. Capturing narration structure does not establish spoken-output or assistive-software compatibility.

Preserve both native key identities. The 1.21.1 callback supplies key, scancode and modifiers. In 26.3, `KeyEvent.key` identifies the physical scancode, while its separate keycode supplies `shortcutKey()` for text navigation and shortcuts. Reusing a zero legacy scancode as the latter can still exercise binding capture while failing to send a meaningful arrow event to an EditBox. The native adapter forwards both fields; the disposable fixture obtains the logical keycode through SDL's actual scancode mapping. Neither test path establishes input-method compatibility.

## Source migration and updates

From 0.1.0, move portable client imports from `design.inherent.reform.neoforge.client` to `design.inherent.reform.minecraft.client`. Replace the mod-bus/container pair on file/key factories with the explicit registration. Direct command composition supplies its native source binding. `IntegratedSettings` and server file/command services also use the neutral namespace. Server file factories take `ServerRegistration`; native NeoForge registrations use `NeoForgeServerSettings` over that same lifetime. Native `ClientRegisteredSettings` and registered-config APIs remain NeoForge-specific. Native key modifiers project into `KeyBindingOptions.Modifier`; converting to a NeoForge enum belongs only at an explicit native boundary.

These source changes do not rename files, document IDs, settings keys, translation keys or saved representations. Published 0.1.0 artifacts and examples remain available under their immutable tag and coordinates.

Recheck loader callback ordering, Fabric's lazy entry-point provider behavior, Mod Menu provider ownership, native command origin and late delivery whenever their dependencies change. MixinExtras 0.5.5 is supplied by both admitted loaders; its common API is compile-only. Keep that pin aligned with the actual selected loader distributions. No second copy is bundled or added as an independent runtime dependency. The [target guide](platform-targets.md) owns version and artifact conventions; [publishing](../publishing.md) owns delivery procedures.

NeoForge native config types remain version-specific. Minecraft 1.21.1 uses `COMMON` and `SERVER`; 26.3 uses `LOCAL` and `SYNCED`. Client preferences bind actual `CLIENT` registrations on both. The client adapter refuses the actual server-synced type; server bindings follow the real loaded source described in [server settings](server-settings.md), including FML 12 installation defaults and existing world overrides. A shared Java API does not translate or copy a native configuration into another lifetime.

Fabric client-source availability also binds the source's actual immutable native `ClientSuggestionProvider.connection` to the current listener. Both full and restricted native sources retain that identity; mutable player/world getters alone cannot certify an old source after reconnect. The access declaration widens observation only, keeps the field final and follows the selected target's real mapping namespace. Requalify that identity and actual server-issued click delivery when changing game mappings or Fabric's command dispatch/promotion rules.

## Construction and activation

Declare form imports, previews, review actions, scoped saving and retained-editor ownership before the screen's first native `init()`. Do not infer activation from the inherited Minecraft reference: 26.3 supplies it during construction. Shared form/review code tracks native initialization explicitly. Reflow does not reopen the declaration phase, and active forms still refuse ownership transfer. The parent screen remains a return destination, not retained editing ownership; use a workflow for retained document views.

The scoped native theme uses the real `Screen.initialized` accessor and current native screen to distinguish construction from mounting. A client reference or nonzero viewport fields cannot replace that observation. `withTheme` refuses replacement after mounting; reflow and return navigation retain the original scope. Independent workflows resolve their own declarations, while auxiliary views inherit the explicit parent/handoff theme.

Owned control hooks retain a null, inert binding on ordinary game and foreign widgets. The library binds its actual controls before measurement. Buttons and cycles retain native caption scrolling, keyboard behavior, and narration; input formatting, substring selection, and caret width share the same role style. When updating Minecraft, inspect those actual native calls, their descriptors/counts, and the native glyph color convention, then rerun pixel, editing, and unbound-control comparisons. Keep the client-only hook inventory and packaging refusals aligned. The adapter never replaces a native font manager or changes global GUI scaling.

Loaded graphics also observe `ReloadableResourceManager.createReload` and `close`, only for the actual Minecraft client-resource manager. Preserve the `PackType` and manager-identity guards: a server-data or separately constructed mod resource manager is not that source. Inspect the native replacement order and real `ReloadInstance.done()` completion when updating either game. Requalify initial readiness, failed/superseded reloads, source invalidation before replacement, and actual loaded-image retirement. The distribution gate rejects moving this hook to common-side mixins.

Loaded sprites borrow `SpriteContents.originalImage` through one client-only read accessor on both games. Do not depend on a Forge-only public image getter for Fabric. Minecraft 1.21.1 resolves GUI sprites through `GuiSpriteManager` and blocks through `InventoryMenu.BLOCK_ATLAS`; 26.3 uses the native `AtlasManager` and `AtlasIds.GUI/BLOCKS`. Recheck atlas source definitions, image ownership, RGBA layout, frame dimensions, missing-entry behavior, and the actual reload boundary when updating. Native bulk pixels are ABGR (`getPixelsRGBA` on 1.21.1 and `getPixelsABGR` on 26.3); the worker converts them to the portable ARGB frame. `getUniqueFrames` has different return types and a static-image sentinel, so it is not the physical-frame selector. The API selects its physical frame explicitly and never claims live animation parity. Preserve the native image on success, refusal, cancellation, and reload; requalify actual GUI and block atlases on both rendering pipelines. The strict hook inventory keeps the accessor client-only.

The 1.21.1 EditBox adapter admits two observed draw-call shapes. Vanilla/Fabric supplies the default shadow through five-argument calls; NeoForge's patch forwards its explicit `textShadow` through six-argument calls. Aggregate groups still require the two formatted, one component, and two plain call sites. Preserve that native flag and those grouped requirements when updating; optional alternate signatures must not turn into an unguarded zero-target success.
