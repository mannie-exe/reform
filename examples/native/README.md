# Native addon example

This separate NeoForge mod consumes only the staged `design.inherent:reform` Maven artifact. It does not use the library's source sets, Compat, Skyweave or the modpack. The ordinary example JAR excludes the opt-in native probe.

`NativeExample` declares a small typed preference record through public record derivation. Its physically client-only `ClientExample` uses the [client JSON factory](../../docs/development/client-json.md) and explicit menu registration. The library supplies native setup, bounded workers, owned storage, the generated form and shutdown. The example supplies no executor, serializer or editing-session infrastructure.

Run `./gradlew --no-daemon check build` from the repository root to compile this example and its probe against the exact candidate artifact. This compiles native APIs without launching a game. It also runs the independent headless consumer and core/tooling checks.

For a disposable client check, first build/stage the library, obtain sustained desktop focus, then run from the repository root:

```sh
python3 tools/native_form_probe.py --java-home /path/to/jdk21
```

The runner refuses an existing `examples/native/runs/client`, creates its own marked fixture and runs four bounded 2 GiB Minecraft clients sequentially. The probe exercises registration refusals, actual menu entry, native fields/invalid drafts/save/reflow/close/reopen, persisted preferences in a second JVM, malformed-file retention/recovery, then a pending retry started by an initialization observer and settled at native shutdown. `--lifecycle-only` selects just that last control. Reports/screenshots go to ignored `build/reports/native-form/`. Only the matching task-owned fixture and logs are removed. Personal instances, servers and worlds are not used.

The headless `python3 tools/native_addon_server_probe.py --java-home /path/to/jdk21` separately checks dedicated-side loading and the common derived record codec, with no client document initialized. Its marked loopback-only server uses an ephemeral port, flat seed 42 and a bounded 2 GiB JVM; it stops itself after the controls and removes the fixture/log.

The launches use ModDev source containers for this ordinary example and its separate probe. The library itself loads from the exact packaged Maven JAR, checked both before launch and inside the game. This is not full Phase 1, a native TOML adapter, an integrated/remote-server test or a release.
