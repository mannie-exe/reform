# Custom workspace views

These APIs belong to the 0.4 development line. They are not available in public 0.3. A custom body uses the existing workspace header, navigation, theme, scrolling panel, native controls, and workflow lifetime. It does not create another settings editor or persistence owner.

## Declare a body

Declare a category in `ClientWorkflowLayout`, then bind a `ClientCustomView<S>` with `ClientWorkspacePresentation.withView(view)`. Its `ViewKey<S>` supplies that category's `DestinationId` and an explicit `ViewStateKey<S>`. A category has one body: a reference task or a custom view. Custom bodies consume the same 512-placement allowance as ordinary field placements. Registration and presentation validation invoke no factory and open no file owner.

The factory receives a `ClientViewContext` and `ClientViewState<S>`. It runs lazily on the native client thread when that body first opens. Register cleanup on the context before allocating owned work. A factory or initial projection failure closes partial work, reports failed navigation, and restores the origin. Successful bodies stay in the bounded workflow cache until workflow closure; hiding a body retires its mounted callbacks rather than its state.

Return a supplier of `ClientViewContent`. Its projection reads cached data only. It runs at admission and explicit state refresh, not every frame or during measurement. Content comprises text, grouped content, effect-named actions, and typed navigation links. The shared panel measures, wraps, stacks, focuses, and narrates these controls. Up to 256 unique row IDs and four nested groups are admitted. Captions are bounded to 4,096 characters. Use stable IDs across refreshes so existing viewport and focus restoration can find surviving rows.

An action must still enter its actual document or operation service with that service's guards. Neither the body nor its view context grants permission to read, write, save, apply, or prepare graphics. Links use `ClientNavigation.inspect/open`; inspecting cached availability cannot authorize later activation. Hidden or remounted controls cannot dispatch stale callbacks. Refresh or action failures retain useful text and the last admitted content. Initial factory failure remains distinct from a later failed refresh.

## Retain view state

`ClientViewState.value()` returns an owned snapshot. `replace(value)` validates a complete replacement before discarding the previous state, then refreshes cached body content. `refresh()` projects current cached domain facts without changing view state. Both require the view's live native context. Neither edits a configuration field or performs a save.

`ViewStateKey` uses the existing `ValueType` codec and snapshot policy. The bookmark retains encoded JSON, not the supplied object. Each read decodes and freezes a fresh value. The default maximum is 16 KiB of UTF-8 JSON, depth 32, 4,096 nodes, 8,192 characters per string, 256 characters per number, and absolute numeric scale 1,024. Custom limits may narrow this envelope. At most 512 custom bodies share the placement budget, bounding current encoded view state to 8 MiB per workflow, with up to another 8 MiB of retained initial declaration snapshots. These are data bounds, not claims about arbitrary addon callback execution time.

State contracts must contain only data. Do not encode levels, resource managers, screens, editors, source capabilities, or native rendering leases as IDs whose decoder secretly restores live objects. Addon codecs and snapshot policies are explicit trusted contracts, not sandboxed code. Store live resources under `ClientViewContext` cleanup and use the existing graphic preparation/use/retirement services.

## Navigate and return

`ClientDestination.View(id)` identifies a registered body. `ClientNavigation.current()` reports the current shared destination. `up()` follows the declared hierarchy, falling back to the workflow's Home for a top-level page; it returns empty at the root. It does not use Back history as a substitute for hierarchy.

`bookmark()` preserves the current destination, relative viewport, and focused stable control without flushing native input. `restore(bookmark)` re-enters the existing navigation service and retained form machinery. Bookmarks belong to the workflow that created them; another workflow refuses them with `navigation.bookmark_context`. A bookmark is presentation data, not an owner capability or a promise that a removed field still exists. Missing destinations retain the ordinary missing-target outcome. Native invalid text remains with its original retained editor, and custom view data stays with its original admitted view session.

Await the identified navigation result before using mounted widgets. `REVEALED` retains its native-mount acknowledgment contract; a factory declaration or ready editor alone does not establish that a body has mounted. Qualification and remaining target coverage belong in the verification record.
