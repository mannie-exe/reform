# Preview preparation

The 0.4 development API supplies read-only input capture and bounded graphics data. Native viewport submission, provider hosting, and GPU retirement bindings remain under implementation. These foundations do not qualify an isolated rendered preview. The [presentation contract](../design/presentation-0.4-api.md) owns the complete selected behavior.

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

## Native ownership

`RenderLifetime` separates a view's interest from outstanding native uses. Closing it prevents new uses; resources release only after every acquired use retires. Release entry, successful completion, and failure are separate observations. A failed partial release is retained rather than retried as if nothing happened.

Only an adapter's actual native consumption or GPU completion can retire a use. A timer, a screen close, or a similarly named game frame callback is insufficient. The 26.3 renderer's extraction and deferred submission differ from 1.21.1 drawing. Each binding must also drain or reconcile its uses before its actual renderer or device shuts down. Core lifetime tests do not establish that native binding.
