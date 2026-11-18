# Native addon example

This separate NeoForge mod consumes only the staged `design.inherent:reform` Maven artifact. It does not use the library's source sets, Compat, Skyweave or the modpack. The ordinary example JAR excludes the opt-in native probe.

`NativeExample` declares a small typed preference record through public record derivation. Its physically client-only `ClientExample` uses the [client JSON factory](../../docs/development/client-json.md), explicit menu registration and the `reformprefs` native client command. The library supplies native setup, bounded workers, owned storage, shared GUI/command operations, the generated form and shutdown. The example supplies no executor, serializer or editing-session infrastructure.

`CreationExample.editor(parent, original)` separately demonstrates the public world-recipe factory for a bounded three-layer flat-world soil-depth setting. It preserves unedited dimensions through the native replacement API and registers no replacement editor for vanilla presets. The native form runner executes it in a real creation screen, without creating a playable world.

Run `./gradlew --no-daemon check build` from the repository root to compile this example and its probe against the exact candidate artifact. This compiles native APIs without launching a game. It also runs the independent headless consumer and core/tooling checks.

For a disposable client check, first build/stage the library, obtain sustained desktop focus, then run from the repository root:

```sh
python3 tools/native_form_probe.py --java-home /path/to/jdk21
```

The runner refuses an existing `examples/native/runs/client`, creates its own marked fixture and runs five bounded 2 GiB Minecraft clients sequentially. The probe exercises registration refusals, actual menu entry, native fields/invalid drafts/save/reflow/close/reopen, persisted preferences in a second JVM, malformed-file retention/recovery, then a pending retry started by an initialization observer and settled at native shutdown. `--lifecycle-only` selects just that last control; `--recipe-only` selects the native original-context recipe controls. The first client also posts the actual client command-registration event and runs native Brigadier operations on the same GUI-saved document, covering invalid input, staged edits, validation, comparison, export/copy components, actual save/publication and retained status/cancel. Its feedback sink is captured at the main menu; this does not qualify a player chat packet or physical clipboard. Reports/screenshots go to ignored `build/reports/native-form/`. Only the matching task-owned fixture and logs are removed. Personal instances, servers and worlds are not used.

The headless `python3 tools/native_addon_server_probe.py --java-home /path/to/jdk21` separately checks dedicated-side loading and the common derived record codec, with no client document initialized. Its marked loopback-only server uses an ephemeral port, flat seed 42 and a bounded 2 GiB JVM; it stops itself after the controls and removes the fixture/log.

Add `--registered` to the server runner for the separate two-boot [registered native owner](../../docs/development/native-settings.md) controls. This opt-in probe registers its own COMMON spec and exercises the public native owner through the shared editor. It preserves the real native TOML serializer and reload listeners, tests failure/readback/restart behavior and reopens saved state in another JVM. Ordinary example launches do not register that fixture spec.

Both registered boots also exercise the public [reference-source APIs](../../docs/development/references.md): actual block/tag capture and worker index compilation, identifier syntax/default namespace, unknown-versus-default-air behavior, an actual isolated registry's empty tag, whole-producer exhaustion, missing optional supplier and explicitly admitted server resource names. These are common-side lookup controls, without a native picker GUI or full-pack admission claim.

The launches use ModDev source containers for this ordinary example and its separate probe. The library itself loads from the exact packaged Maven JAR, checked both before launch and inside the game. This is not full Phase 1, integrated/remote-server or pack qualification, or a release.
