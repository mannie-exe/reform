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

## Settings for a running singleplayer world

On the physical client, `IntegratedSettings.screen(parent, title, documentId)` opens a generated form for a policy the current integrated server has already registered. The same entry works for a world-root file and an admitted SERVER TOML. The overload accepts `FormPresentation` and an explicit native permission level from zero through four. The default requires level two for opening and saving. A client mod supplies the entry point. Missing worlds, missing registrations and insufficient permission show an explanation with a Back action.

The entry uses the native local memory connection and resolves the actual host player within this server on its own thread. Console and remote player sources cannot stand in for that host. The client's synchronized permission level limits the presentation. The server checks its current player, connection and native permission at admission and through the backend's normal read, preparation, persistence and publication boundaries. Editing a draft works on the client. Validation, writes, native callbacks and application retain their original owners and execution policies.

Inspect and Review show the actual persistence source. A registered SERVER setting can use the installation config when NeoForge has no world override. Its saved value can then be picked up by other worlds that load the same installation source. A world-root `serverPolicy` keeps the root supplied by its addon. The running-world caption describes the authority and available effects in the current session.

A draft revision describes the client editor. A source observation or receipt retains its server source session, and an effective-value observation retains its runtime handle identity. Detaching a screen retires its editing scope. Losing the current world, player, connection or client permission also retires that scope, including while a child screen is open. Reopening resolves a fresh scope and source. Context closure leaves already issued receipts intact. In-flight native effects follow the backend's existing cancellation and reconciliation rules.

`ServerSettings.admittedDocument(server, id)` is the noncreating registration lookup for the exact native server. `Document.localPresentation` and `LocalPresentation.attach` are the lower-level handoff for an already admitted host. Attach transfers one native source session into a separate owned editing context. Abandoning admission or closing the context releases the native session and access slot. Lookups and queued admission use the existing document limit and executor. The ordinary factory needs no packet protocol, custom executor or duplicate owner.

## Native server commands

In `RegisterCommandsEvent`, add `ServerPolicyCommands.branch(literal, definition)` for the actual declared file policy, or `registeredBranch(literal, documentId)` for a server-admitted native config. Register documents separately on server startup. These branches resolve registrations from the command's current server, so a dispatcher built before registration or rebuilt after data reload can use the current source. The library chooses no root. The two-argument factories use operator level two. The overloads let an addon choose one level for both reading and writing.

The native console and each current player have separate drafts held under the document's existing bounds. Commands share the generated form's typed validation, comparison, export and persistence services. A draft opened through commands is independent of a draft opened through the integrated form. Saves meet at the same owner and source revision checks. Lowered command permission and foreign feedback sources cannot inherit an existing editor's authority.

Availability and suggestions perform noncreating lookups. Status and cancellation require an already retained editor and exact operation UUID. Replies go to the original native recipient only while its current source and editor still match. Logout or native player replacement retires that command editor, including on a same-connection respawn. Reopening reads the new baseline. The server context closes its remaining editors when the world stops. Long-lived domain operations choose their own actor and respawn policy separately.

## Lifetime and resources

The runtime admits eight simultaneous actual server scopes and 128 document identities per scope. Each document retains standard core context bounds, a sixteen-operation owner lane and a 64-edit actor lane. Its actions admit 32 definitions, 64 active operations and 128 retained observations. File-backed editors inherit their store's value/draft limits; registered editors use their actual native owner's value bounds. These limits are admission policy, not measured heap or responsiveness claims.

Each server lazily supplies two daemon workers, a 256-task queue and thirty-second idle expiry. Documents borrow that pool. `ServerStoppingEvent` closes all document contexts before the native executor stops; `ServerStoppedEvent` supplies cleanup after a crash path that skipped the earlier event. Cleanup continues across context failures, releases the server reference and shuts down the pool without blocking the native thread for a timer. Actual active effects retain core receipt/drain semantics; this is no process-crash transaction or universal synchronous drain guarantee.

Explicit scope closure invalidates its documents, handles and editors. A deliberate replacement scope for the same still-running server starts with no inherited bindings and cannot revive old access. Normal native stopping prevents new binding. A stopped world never falls back to another server's handle or installation defaults.

The ordinary [server example](../../examples/native/src/main/java/example/reform/ServerPolicyExample.java) selects a world-root JSON policy and an actual registered SERVER TOML config. It supplies no executor, private serializer or cleanup loop. [Verification](verification.md) records the dedicated and local host checks and the limits of their coverage.
