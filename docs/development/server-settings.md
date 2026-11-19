# Server settings

`ServerSettings.forServer(server)` binds the actual running `MinecraftServer` on its native thread. It supplies a server lifetime, shared bounded workers and explicit document factories without client classes or a process-global active value. Call it from an admitted server lifecycle callback; mod-constructor metadata and native server initialization are different boundaries. [Native registered settings](native-settings.md) and [owners](owners.md) retain their backend contracts.

## Bind an actual source

`serverPolicy(container, definition, binding)` initializes a genuinely new owned JSON policy at the explicitly supplied `StorageBinding`. The container must own the definition namespace. The caller chooses the real server/world persistence root; the library does not infer a world from a filename, seed or working directory. The conventional JSON envelope, strict parsing, complete defaults, create-if-absent initialization and immutable publication are the shared file backend's operations.

`serverPolicy(container, store)` instead retains an explicit properties, bare JSON or custom `TextStore`. Its value/draft limits govern the shared editor too. An optional immutable `ApplicationEffects` declaration explains domain requirements without claiming those effects happened. The ordinary file policy publishes its own runtime handle; domain compilation and cache activation remain consumer-owned.

Binding a file policy is an explicit lifecycle initialization operation. `Document.initialization()` retains its actual asynchronous receipt, including refused, failed or unknown outcomes; cancelling a returned future copy does not cancel that operation. `Document.runtime()` supplies the actual handle, whose value can be unavailable or closed. A malformed existing file remains intact and does not acquire effective defaults. Opening an editor, looking up metadata and reading the handle perform no initialization or source replacement.

`registeredNeoForge(container, config, id)` binds an actual already loaded server-admitted `ModConfig`. The overload accepting explicit field types and `RegisteredConfig.Limits` preserves ambiguous native fields and chosen admission bounds. It delegates to `RegisteredConfig`, retaining native paths, world overrides, predicates, TOML, restart declarations and save/listener execution. A CLIENT config is refused. Admitting a COMMON config is an explicit consumer policy decision; its filename alone establishes no authority.

Registered documents expose neither a fabricated domain-effective handle nor a library initialization receipt. Native loading belongs to FML, and raw values plus returned callbacks do not certify arbitrary copied gameplay state. The shared native save can be staged, partially observed or unavailable independently of disk success.

Documents share conservative lexical normalized, case-insensitive path collision admission within their actual server scope, including collisions between file and native writers. It performs no filesystem lookup and does not replace backend-specific regular-file, symlink and exact-source checks. Document IDs remain separate from paths and display text. Independent servers can deliberately bind the same installation source, but their runtime handles remain distinct and competing writes retain the backend's source checks.

## Open an authenticated editor

`Document.editor(source)` opens the same `EditorActor` and `DocumentActions` used by native forms and commands, with operator level two required for both reading and writing. Its overload accepts explicit native read/write levels from zero through four. The command stack's captured level is only an additional restriction. Every native access guard checks actual server lifetime and current native permission again.

The built-in binding admits the actual server console or an actual current player source, preserving the player object and connection identity. A replaced player, connection or server refuses access. Elevating a captured command stack cannot elevate the player's current permissions. Replacing the feedback source with another object does not impersonate the console or player. These are deliberately local native source bindings; arbitrary command blocks, RCON, custom permission providers and remote administration need their own explicit reviewed adapters.

An `Editor` owns its access and shared actions. Close it on the native server thread when the workflow ends. This releases sessions, hooks and access capacity without closing the document or another editor. Different editors retain separate drafts and use actual revision/source conflicts rather than sharing a mutable widget model. `Editor.actions()` after closure refuses access.

Advanced adapters can borrow `Document.context()` and open `Document.session(access)` with an actual context-bound access capability. They retain responsibility for their real actor checks and shorter-lived cleanup. A payload identity or a path cannot construct that authority. No command root, packet endpoint, client menu or gameplay shortcut is installed automatically.

## Lifetime and resources

The runtime admits eight simultaneous actual server scopes and 128 document identities per scope. Each document retains standard core context bounds, a sixteen-operation owner lane and a 64-edit actor lane. Its actions admit 32 definitions, 64 active operations and 128 retained observations. File-backed editors inherit their store's value/draft limits; registered editors use their actual native owner's value bounds. These limits are admission policy, not measured heap or responsiveness claims.

Each server lazily supplies two daemon workers, a 256-task queue and thirty-second idle expiry. Documents borrow that pool. `ServerStoppingEvent` closes all document contexts before the native executor stops; `ServerStoppedEvent` supplies cleanup after a crash path that skipped the earlier event. Cleanup continues across context failures, releases the server reference and shuts down the pool without blocking the native thread for a timer. Actual active effects retain core receipt/drain semantics; this is no process-crash transaction or universal synchronous drain guarantee.

Explicit scope closure invalidates its documents, handles and editors. A deliberate replacement scope for the same still-running server starts with no inherited bindings and cannot revive old access. Normal native stopping prevents new binding. A stopped world never falls back to another server's handle or installation defaults.

The ordinary [server example](../../examples/native/src/main/java/example/reform/ServerPolicyExample.java) selects a world-root JSON policy and an actual registered SERVER TOML config. It supplies no executor, private serializer or cleanup loop. [Verification](verification.md) separates tested dedicated-side behavior from remaining integrated/client, real-player, command-routing, full-pack and cost qualification.
