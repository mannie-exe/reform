# Effective publication

`RuntimePolicy<T>` and its read-only `SettingsHandle<T>` extract the useful context/revision behavior from the existing runtime settings policy. They are low-level publication primitives, used by the [asynchronous JSON owner](owners.md). They perform no file IO, command registration, actor lookup or native configuration mutation. [Coverage](coverage.md) records the supported native and operation boundaries.

## Bind values to a context

A policy has a new opaque context identity and owner-thread verifier. It begins uninitialized with no effective value; factory defaults are not presented as loaded data. An actual admitted lifecycle/owner adapter prepares a qualified candidate and explicitly publishes it. Another policy's revision cannot be reused even when document identity, sequence and values match.

The handle exposes immutable views with availability, context, effective revision, optional value and an observation report. Reading it performs no IO. Supported values are defensively copied through the declared snapshot policy; arbitrary mutable custom values do not become safely shareable merely by entering an immutable container. Getters can allocate. Consumers should compile/capture appropriate immutable runtime inputs rather than repeatedly traversing editor or configuration models in terrain/gameplay hot paths.

An observation failure before initialization remains unavailable without substituted defaults. After a qualified publication, the previous value can remain active in the same context while its reload/observation report describes the failure. That report is not a declaration that the retained active value is invalid. Context closure publishes closed/unavailable state, releases current value/type/definition/verifier references and invalidates older prepared work. A caller-retained historical view remains historical data; it does not authorize use in a replacement context.

## Prepare and publish

Administrative reads and publication admission/finalization invoke the adapter's current authorization callback on the admitted owner thread. The native adapter must bind that callback to the actual server/connection/player or local context. GUI data, UUIDs, labels and a previously successful check cannot supply it. The runtime handle is an internal read model, not an administrative transport endpoint.

`publishAsync` captures the exact definition, candidate and current immutable observation on the admitted owner executor. Complete pure validation, copying and equality run on the explicit worker. The native finalization then rechecks actual authorization, context, effective revision and captured observation identity before swapping the prepared view. No value codec, constructor, validator, equality or snapshot callback runs in that final swap. Newer failed observations cannot be erased by an older worker result merely because the effective value/revision remained unchanged. Updating an observation report reuses already owned immutable state without invoking a copy callback.

The explicit synchronous `prepare`/single-use `Prepared.publish` primitive remains available and uses the same computation/finalization logic on its caller's admitted owner thread. It repeats complete validation at publication and preserves its native-thread/reentrant checks; it is not the responsive default for owned JSON. Standard `FileOwner` uses `publishAsync`. Worker execution requires pure, bounded, thread-safe definition callbacks; arbitrary native spec predicates/serializers are not moved there. Those retain the [native executor contract](native-settings.md). These checks are not a sandbox for arbitrary consumer code.

A semantically equal candidate keeps the existing effective value and revision. A changed value receives a new effective revision. Saved-byte hashes and document schemas are separate identities. `PolicyRefusal` retains structured issue codes/paths and unavailable status; it is a local error, not a Java-object serialization protocol.

## Keep persistence independent

Publishing cannot prove that a file was saved, and a saved file cannot prove that publication was allowed. When publication is refused after persistence, retain the saved observation and current effective value independently. An application-only retry needs a fresh admitted saved read and effective revision; it does not repeat persistence or apply a later unsaved draft.

The [independent public consumer](../../examples/headless/src/main/java/example/ReviewProfile.java) exercises actual separate owner/worker executors and a real JSON file: save a submitted value, retain a later edit, refuse a subsequent application-only request after changed authorization, then admit it without another write. The connected-owner filesystem controls additionally revoke authority after replacement but before publication. Both use controlled local callbacks, not real connected-player authorization.

These primitives suit an owner-declared immutable runtime policy. Native registered configuration has different mutation/callback timing; its setters are not this publication operation. The explicit JSON service connects bounded admission, actor-bound sessions and disk/application receipts. [Native registered settings](native-settings.md), [client factories](client-files.md), [server settings](server-settings.md) and [world recipes](world-recipes.md) supply their own actual execution, persistence and admission bindings.
