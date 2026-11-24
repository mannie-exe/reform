# Preview preparation

The 0.4 development API supplies read-only input capture, bounded graphics data, a shared workflow host, and private native mesh adapters. Exact native and consumer qualification belongs in [verification](verification.md); implementation alone does not qualify every provider or the complete system. The [presentation contract](../design/presentation-0.4-api.md) owns the complete selected behavior.

## Capture the actual draft

Call `ClientEditor.capturePreview(entryPoint, expectedDraftRevision)` on the client thread. It uses the same retained form and `DocumentActions` as editing and saving. Unaccepted native input, pending native edits, unavailable sources, and an old revision refuse capture. The underlying actor performs complete validation and checks actual READ authority before and after preparation. Capture neither saves nor applies values, and it does not require WRITE authority or approval of a save review.

`ClientPreviewCapture.sourceOperation()` exposes the actual backend inspection receipt. Its `completion()` yields `DraftPreviewInput` only after client-thread input and cached reference identity checks. A completed backend inspection does not guarantee successful native delivery. Keep these observations distinct. Closing the editor abandons delivery; cancellation requests do not prove that entered backend work stopped.

`DraftPreviewInput` contains the complete snapshot-policy-frozen candidate, provenance, observed reference stamps, and a `PreviewInputStamp`. The latter identifies both the accepted draft revision and native input epoch. Reflow or caret movement alone does not edit that epoch. A captured reference stamp is an observation, not permission to read its source. Domain providers must capture and recheck their actual resource generation separately; a resource name or human revision string cannot establish freshness.

## Frozen graphics data

`GraphicLimits` supplies explicit positive input-byte, output-byte, decoded-pixel, resource-count, and native-allocation allowances. There is no unlimited fallback or guessed standard budget. Aggregate accounting and actual native preparation must enforce the admitted operation's allowances, not merely each individual texture's bound.

`GraphicMesh` freezes positions, normals, UVs, triangle winding, material identities, and lighting declarations. UVs address each material's animation frame rather than a borrowed atlas or the full encoded strip. `geometryBytes()` describes the canonical geometry and identity payload; it does not estimate JVM or GPU allocation. An empty face list is ready invisible geometry. It has no materials and zero bounds. A viewport must retain a finite default camera for that zero extent, without inserting geometry or dividing by the extent.

`GraphicMaterial` and `GraphicLighting` preserve alpha, culling, sampling, direction, emission, and shading choices. Their presence is not evidence that a native adapter supports every combination. Preparation must implement an admitted combination or report its unsupported capability explicitly. Do not invent unresolved world tint or silently substitute one material policy.

`GraphicTexture` freezes PNG and optional metadata bytes. Construction checks the leading header and whole-strip pixel allowance before native allocation. `validatePng()` separately checks container completeness, chunk checksums, one leading IHDR, image data, and a final IEND. This integrity check is not a replacement pixel decoder.

`ClientGraphicImages.decodeStill(texture, limits, metadataLimits)` performs explicit CPU preparation through the game's PNG decoder and animation metadata parser. It validates the container before native allocation, checks native strip and output allowances, and closes the native image before returning copied `GraphicPixels`. No live resource manager, texture manager, or GPU upload participates. The selected still is the first declared animation frame; absent animation metadata preserves the full rectangular image. Frame dimensions, frame indices, ARGB channels, and alpha remain explicit. Animation playback is separate from still preparation.

Keep decoding and compilation outside ordinary rendering and search. The preparation coordinator must dispatch this work appropriately and check cancellation, authority, and source freshness before mounting its result. Successful decoding alone does not admit a stale result.

`ClientEditor.isPreviewCurrent(input)` checks an earlier capture against the same actual editor, native input epoch, cached reference observations, and current READ permission. It performs no IO or new capture. Invalid new text invalidates the earlier capture even before an accepted actor revision changes. Repairing a field does not restore the earlier epoch.

`ClientGraphicImages.decodeStills(textures, ledger, metadataLimits, reservedOutputBytes, checkpoint)` prepares a complete batch on the caller's worker. It checks aggregate encoded input and full-strip pixels before decoding, subtracts reserved geometry from the combined frame-output allowance, and charges temporary native images to the same `GraphicLedger` as graphics awaiting retirement. Duplicate texture identities refuse. Its checkpoint runs during admission and around each decode; cancellation or failure returns no partial map. The caller owns cancellation and job scheduling. The returned immutable map contains detached pixels, with each temporary native image closed. This method neither captures resources nor publishes textures.

## Private mesh adapter

`GraphicScene` binds a frozen mesh to explicit materials and already-decoded `GraphicPixels`. `ClientMeshGraphic.prepare` performs native preparation on the client thread; ordinary drawing receives only a camera and measured viewport. The default adapter supports explicit alpha, culling, sampling, emission, and studio directional lighting. Ambient occlusion requiring world context refuses rather than becoming a silent approximation. Empty geometry needs no native allocation.

Minecraft 1.21.1 uses a private OpenGL program, buffers, textures, and framebuffer while restoring the borrowed native GUI state. Minecraft 26.3 uses its native rendering device, private pipelines, and deferred GUI extraction. Neither adapter registers textures or replaces a live resource manager. Changing the camera or viewport can wait for earlier native uses before rebuilding its private target; waiting is distinct from a missing or invalid subject.

Use a ledger shared across the workflow's entered preparations and retiring graphics. Its counters describe known native image/buffer payload and owned object counts, not total process or graphics-driver memory. Closing the view revokes new submissions immediately. Actual completion controls release, and failures remain observable through the lifetime state. A supplied freshness guard must reflect the actual source, native input, and resource generation; it is checked before preparation and submission. A predicate that always returns true does not establish owner freshness.

## Hosted preparation

### Loaded resources

`host.openLoaded(provider)` opens a read-only loaded-resource view without admitting a settings document. `ClientLoadedGraphicProvider<S>` receives a `LoadedGraphicRequest<S>` under the workflow's actual READ operation and context. It receives no edited settings, fabricated draft revision, or write capability. `ClientGraphicProvider<T, S>` retains its separate complete-draft capture and native-input guards. Both kinds use the same bounded lane, worker/native stages, ledgers, visibility, cancellation, and actual-use retirement.

`GraphicSourceStamp` distinguishes `DRAFT` from `LOADED`. The existing `GraphicRequest.Stamp` retains its exact `PreviewInputStamp`; `LoadedGraphicRequest.Stamp` carries the actual loaded resource identity instead. `GraphicSceneData`, `PreparedGraphic`, and host results expose that common source stamp. A caller inspecting draft-only data must first match the actual draft subtype. A loaded image is never evidence that edited settings compiled or applied.

`ClientLoadedGraphics.textures(limits, metadataLimits)` prepares one exact local PNG resource from the loaded client packs. It reads bounded image bytes and metadata from the same contributing pack, closes its streams, and uses the shared still decoder and native allocation ledger. The first declared frame is fitted at its own aspect ratio, with nearest filtering, alpha, and unlit reference presentation. Missing assets are Unavailable; malformed data and budget exhaustion remain explicit failures or refusals. It neither browses a filesystem nor accepts remote URLs.

The client-only reload adapter observes the actual Minecraft resource manager, before replacement and after its real completion. Start, completion, and closure change the snapshot identity even when the human revision text is unchanged. Late completion cannot make a newer reload ready. Separate server-data or mod-owned managers do not change this source. Reload retires old images; returning to a view does not prepare another image automatically. A failed or unfinished reload keeps resources unavailable.

`ClientViewContent.LoadedGraphic<S>` adds a shared loaded-image slot to an authored custom body. Supply a readable title/help, the typed provider and frozen subject, and the workflow's explicit aggregate limits/view bound. It admits no editor and performs no preparation during projection. **Prepare loaded image** is explicit; camera controls change only the view. **Show loaded image** focuses and reveals the current native viewport after dispatch. Unchanged slots survive ordinary custom-state refresh/reflow; replaced, removed, hidden, and closed slots retire their actual interests. A draft catalogue task continues to use `ClientTaskGraphic`, with its actual document input.

### Draft providers

The development host is obtained through `workflow.graphics(limits, maximumViews)`. The workflow fixes this aggregate budget and view bound once. Open a typed view with `host.open(workflow.editor(definition), provider)`, using the exact owner definition. This does not load another editor, prepare a subject, or grant READ permission.

`ClientGraphicProvider<T, S>` supplies a stable provider ID, a `ValueType<S>` for bounded selection parameters, explicit limits, a cheap actual `ReferenceStamp` for its resources, and a preparation function. Its request contains the complete separately captured `DraftPreviewInput<T>`; do not serialize the full document or texture payload into `S`. Selection parameters use the existing operation input admission. The provider composes `ClientGraphicWork.onWorker` and `onClient` stages rather than waiting for a worker result on the client thread. Native stages prepare detached model data; private GPU allocation stays with the host. Each stage must close its own transient resources before returning detached data.

`GraphicScenePreparation` is the provider's CPU/native-data outcome. Ready contains frozen `GraphicSceneData`: exact request stamps, mesh/texture bindings, default camera, and bounded descriptive facts. It has no GPU lease. The host checks the complete identity and provider allowance again before native preparation. Known UTF-8 fact payload counts with geometry and pixels against output admission.

`view.request(subject)` explicitly requests preparation. One expensive request enters at a time per workflow; each viewport retains only its latest pending request. A replaced pending attempt has no invented operation handle. Once entered, its `Attempt.operation()` is the actual `OperationScope` handle. Cancellation does not release the lane until entered work is terminal. Provider child ledgers narrow their limits while sharing atomic reservations with the workflow parent, including native work still retiring. Retained scene output has a separate aggregate payload counter.

The operation receipt and `Attempt.completion()` retain only bounded result metadata. They do not retain decoded scene payload or a GPU lease. `view.preparation()` separately exposes the current native `GraphicPreparation`; only Ready contains a `PreparedGraphic` lease. Its `close()` revokes new drawing, while `releaseCompletion()` reports actual release success or failure later. Cleanup does not depend on an open editor executor. Inspect the returned state rather than treating future completion alone as proof of successful release.

The host observes each actual release completion even after the view closes or loses its current graphic interest. A failed release retains the uncertain output charge, exposes `preview.release_failed` in the view snapshot, and records its actual cause through `releaseFailure()` and `releaseFailureCount()`. Synchronous and deferred failure paths count the same lease once. A cleanup issue remains available separately from the current preparation outcome: an older lease cannot replace a newer request's result or reopen a closed view. Inspection does not retry native destruction or require restoring editor authority.

Draft, native-input, permission, or resource-identity changes prevent old native submission and cancel obsolete preparation. Hiding or closing a view does the same. Showing it again does not silently prepare, save, or reload anything. The prior result's bounded facts and provenance can remain available as historical information while its status is Stale. No result with another view/request/source stamp can mount.

The host and provider APIs remain under native qualification and are not yet integrated into the complete 0.4 shell. Consumers should adopt the shared composition when available rather than creating a private production screen or coordinator around the development viewport widget.

## Native ownership

`RenderLifetime` separates a view's interest from outstanding native uses. Closing it prevents new uses; resources release only after every acquired use retires. Release entry, successful completion, and failure are separate observations. A failed partial release is retained rather than retried as if nothing happened.

Only an adapter's actual native consumption or GPU completion can retire a use. A timer, a screen close, or a similarly named game frame callback is insufficient. The 26.3 renderer's extraction and deferred submission differ from 1.21.1 drawing. Each binding must also drain or reconcile its uses before its actual renderer or device shuts down. Core lifetime tests do not establish that native binding.
