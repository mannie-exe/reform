# Owned storage

The experimental storage core connects real files to definitions and [edit sessions](editing.md). `TextStore<T>` shares observed-file identity, complete candidate admission, explicit initialization, no-op preservation and guarded replacement between format implementations. It is a synchronous worker-side backend, not the asynchronous owner service. It supplies no actor authorization, context lifetime, native callback, effective-value publication or GUI. An owner must admit those boundaries and keep this work off render/tick threads; a widget must not receive the storage backend as its save authority. [Coverage](coverage.md) retains those remaining requirements.

## Bind a declared location

`StorageBinding` combines an explicit absolute root with portable relative segments. Construction performs no IO. The platform supplies its actual loader/world/export root; the helper does not derive one from the process directory, home, environment variables or JVM overrides. Document identity, path, source-byte revision and eventual owner context remain separate.

`StorageBinding.newJson(root, id)` maps a genuinely new document to `<root>/<namespace>/<document-path>.json`. Existing owners use `relative` with their actual path and format. Extraction does not move Compat/Skyweave files, rename keys or collect them under `config/reform/`. The default mapping refuses identities that are valid logical names but unsafe storage segments; it never silently sanitizes them.

Bindings admit at most sixteen segments of 128 ASCII characters each. Absolute/traversing segments, separators, trailing dots/spaces, reserved portable device names and case collisions are refused. Each access rechecks regular directories, existing case spellings and the regular non-symlink target. Final reads also use `NOFOLLOW_LINKS`. Directory scans have an owner-declared entry bound, default 4,096; a write excludes only its exact temporary file from that accounting. Registration-time collisions between two not-yet-created documents remain a responsibility of the forthcoming owner registry.

These are admitted local configuration paths, not a filesystem sandbox against a hostile process replacing ancestor directories. The helper coordinates library writers and preserves the inspected consumer symlink policy; it does not claim secure-directory-handle isolation, cross-process locking or filesystem-wide compare-and-swap.

## Read, initialize and replace

`OwnedFile` reads bounded strict UTF-8. An immutable snapshot distinguishes absent data from an empty file and retains exact text plus a content digest. The snapshot belongs to that file binding; another binding cannot reuse it as a write precondition. A digest is not an authorization/context token. No-op saves still compare current bytes with the baseline before reporting unchanged.

Normal replacement writes a same-directory temporary file, preserves existing POSIX permissions where supported, forces its contents and rechecks the baseline before the native atomic move. The code retains `FileChannel.force(true)` from the stronger owned writer. Directory metadata is not forced; this is not a universal power-loss durability guarantee. Unsupported atomic replacement is reported rather than silently downgraded.

First initialization publishes a complete forced temporary file using a new hard link and then removes the temporary name. This refuses an existing target instead of relying on `ATOMIC_MOVE` as create-if-absent. Java specifies implementation-dependent handling of an existing target for atomic moves; hard-link creation is an optional filesystem operation. Unsupported or denied creation is an explicit failure, never a fallback that replaces another initializer's file. See the [Java 21 Files contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#createLink(java.nio.file.Path,java.nio.file.Path)). Supported filesystem/platform qualification remains separate from this local result.

Per-path locks coordinate all live library bindings to the canonical location. References include waiting callers and are removed after the final lease; completed paths do not remain in a process-lifetime writer map. Locks cover synchronous file work only, with no cross-executor wait or runtime publication callback under them. An external writer can still race the last check and replacement; do not advertise cross-process atomic conflict detection.

`OwnedFile.Result` reports observed persistence separately from failure details. Intended bytes observed after an exception remain `SAVED`; proven baseline bytes after an attempted failure are `FAILED`; unexpected/unreadable post-attempt bytes are `UNKNOWN`. Before-attempt conflicts remain `REFUSED`. Cleanup failures remain visible without erasing established saved evidence. No path rolls back over another writer. `SAVED` proves neither ownership of every filesystem event nor runtime application.

## Typed JSON storage

`JsonStore<T>` attaches a definition, admitted file, explicit format and JSON/draft limits. `NEW_ENVELOPE` uses exactly `schemaVersion` and `values`; the positive owner-declared version is independent of library, wire, world-generation and runtime revisions. `EXISTING_BARE` retains the original unwrapped JSON shape. Native NeoForge files keep their actual registered serializer and callbacks rather than using this backend.

Read returns missing, invalid, unavailable or ready state with its exact saved snapshot, available draft, complete candidate and diagnostics. Envelope selection preserves original scalar spelling in `values`. Malformed JSON, unsupported/fractional/string versions, unknown envelope fields and invalid values do not turn into loaded defaults. Invalid typed drafts remain available when structurally admissible. Unreadable/invalid UTF-8 file access fails explicitly and does not rewrite data.

`initializeDefaultsIfMissing()` is an explicit lifecycle operation. It validates/serializes defaults before creating the required directories or file, and uses the expected-missing snapshot. A competing initializer's observed file is returned for inspection. Existing malformed data remains untouched and invalid; an unchanged persistence result does not claim a successful load. Merely constructing a store, reading it or opening an edit session never initializes it.

Writes admit a complete candidate from the exact definition and saved observation from the exact store. Unchanged drafts preserve original bytes/formatting and still perform fresh conflict checks. Actual edits are pretty-printed in the declared format, preserve admitted number spelling, and pass complete serialization/admission before file effects. A stale baseline is refused. The returned observation can acknowledge an `EditSession` opened with `fromDraft`, preserving later raw edits while saved state advances. Persistence and validation remain visible separately if readback is unavailable or invalid.

Pretty output preserves explicitly declared JSON nulls, including nullable record fields, null-valued map entries and the `values` member of a nullable root envelope. Null is distinct from an omitted/defaulted field; the writer does not remove it as a formatting preference. Existing non-nullable codecs still refuse null input.

The [public headless consumer](../../examples/headless/src/main/java/example/ReviewProfile.java) performs this sequence using only the packaged Maven artifact and removes its own temporary directory. It does not qualify native permissions, execution or application policies. [Verification](verification.md) owns tested artifacts and limits.

## Typed properties storage

`PropertiesStore<T>` uses the JDK's `Properties.load(Reader)` semantics over the admitted UTF-8 source. Registration requires a flat record of scalar fields, optionally wrapped by reference metadata or declared defaults. It rejects structural/optional fields instead of inventing a dotted-key encoding. Missing defaulted fields stay omitted, explicit equal-default values remain explicit and the existing filename/keys acquire no JSON envelope or schema field.

Logical duplicate keys are refused, including differently escaped spellings of the same key. Booleans must be exactly lowercase `true` or `false`; logical trailing whitespace is significant. Numeric values pass strict JSON numeric admission and the declared codec/ranges. Text and choices remain strings. Unknown fields remain invalid until explicitly removed or repaired. Malformed native escapes and unsupported projection input preserve source bytes; no read silently supplies defaults or repairs a file. The shared JSON value projection also refuses unpaired logical surrogates.

The writer preserves comments, blank lines, unchanged assignments, escaped keys, continuations and their line endings. A changed assignment uses the public native property serializer without its timestamp header; new assignments use declared field order and the source's first line-ending convention. An unfinished EOF continuation receives a separating blank line before appended assignments. The complete transformed logical map must equal the intended authored map before file effects. The public native parser is the semantic check for both original chunk boundaries and final output; the library does not copy JDK-private parsing or writing code.

`PropertiesStore.Limits.STANDARD` admits 1,024 properties, 16,384 physical lines, 4,096 characters per logical key and 32,768 per logical value. File-byte, JSON-projection and draft bounds remain independently declared. Exhaustion returns unavailable/incomplete without a partial draft or candidate. These are input admission bounds, not measured heap or latency guarantees.

The [public properties example](../../examples/headless/src/main/java/example/PropertiesEditing.java) connects this format to the same owner, editor and GUI/command services, preserving an immutable active startup snapshot while saving future choices. [Owners](owners.md#immutable-startup-files) owns that application policy. Properties serialization alone does not make a live startup switch safe.

## Additional text formats

A `TextStore<T>` subclass supplies pure bounded `decode`, `encode` and `validateEncoded` hooks. Format diagnostics use `InvalidData` with a complete or unavailable `ValidationReport`. Unexpected format failures expose a fixed diagnostic. Hooks run on the admitted worker; they must not perform separate file effects, native callbacks, authority checks or runtime publication. Reuse the actual shared file identity/write boundary rather than building a second owner or writer. This extension API is not an arbitrary path or parser endpoint.

For backend changes, retain concurrent initialization, exact-byte conflicts, read-only/regular/symlink checks, UTF-8/byte bounds, source spelling, malformed/unsupported input, no-op behavior, post-write failure/unknown outcomes, temporary cleanup and lock lifetime controls. Native TOML serializers/events must keep their own owner executor and callback order during migration; this text-store backend does not replace them.
