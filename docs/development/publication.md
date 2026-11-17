# Effective publication

`RuntimePolicy<T>` and its read-only `SettingsHandle<T>` extract the useful context/revision behavior from the existing runtime settings policy. They are low-level publication primitives, not the complete asynchronous owner/operation facade. They perform no file IO, command registration, actor lookup or native configuration mutation. [Coverage](coverage.md) owns those remaining requirements.

## Bind values to a context

A policy has a new opaque context identity and owner-thread verifier. It begins uninitialized with no effective value; factory defaults are not presented as loaded data. An actual admitted lifecycle/owner adapter prepares a qualified candidate and explicitly publishes it. Another policy's revision cannot be reused even when document identity, sequence and values match.

The handle exposes immutable views with availability, context, effective revision, optional value and an observation report. Reading it performs no IO. Supported values are defensively copied through the declared snapshot policy; arbitrary mutable custom values do not become safely shareable merely by entering an immutable container. Getters can allocate. Consumers should compile/capture appropriate immutable runtime inputs rather than repeatedly traversing editor or configuration models in terrain/gameplay hot paths.

An observation failure before initialization remains unavailable without substituted defaults. After a qualified publication, the previous value can remain active in the same context while its reload/observation report describes the failure. That report is not a declaration that the retained active value is invalid. Context closure publishes closed/unavailable state, releases current value/type/definition/verifier references and invalidates older prepared work. A caller-retained historical view remains historical data; it does not authorize use in a replacement context.

## Prepare and publish

Administrative reads, preparation and publication invoke the adapter's current authorization callback on the admitted owner thread. The native adapter must bind that callback to the actual server/connection/player or local context. GUI data, UUIDs, labels and a previously successful check cannot supply it. The runtime handle is an internal read model, not an administrative transport endpoint.

Preparation requires the exact definition and current effective revision. A prepared publication is single-use, including refused attempts. Publication repeats complete pure validation, copy/equality work and then fresh context/authorization/revision checks immediately before changing the effective view. Reentrant callbacks cannot overwrite a newer publication or continue through a closed context. Consumer callbacks remain required to be pure/bounded; these checks are not a sandbox for arbitrary code.

A semantically equal candidate keeps the existing effective value and revision. A changed value receives a new effective revision. Saved-byte hashes and document schemas are separate identities. `PolicyRefusal` retains structured issue codes/paths and unavailable status; it is a local error, not a Java-object serialization protocol.

## Keep persistence independent

Publishing cannot prove that a file was saved, and a saved file cannot prove that publication was allowed. When publication is refused after persistence, retain the saved observation and current effective value independently. An application-only retry needs a fresh admitted saved read and effective revision; it does not repeat persistence or apply a later unsaved draft.

The [independent public consumer](../../examples/headless/src/main/java/example/ReviewProfile.java) exercises this distinction with a real JSON file: save a submitted value, retain a later edit, refuse effective publication after a changed authorization result, then re-read the saved file and publish it without another write. This uses a controlled local callback, not a real connected-player authorization test.

These primitives suit an owner-declared immutable runtime policy. Native registered configuration has different mutation/callback timing; use the [native contract](native-settings.md), rather than pretending its setters are this publication operation. The full owner service still needs bounded executors/queues, actual actor/context binding, persistence/native backends, truthful combined receipts and operations, original new-world context and convenience factories before real migration.
