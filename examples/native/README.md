# Native addon example

This separate NeoForge mod consumes only the staged `design.inherent:reform` Maven artifact. It does not use the library's source sets, Compat, Skyweave or the modpack. The ordinary example JAR excludes the opt-in native probe.

`NativeExample` declares a small typed preference record. Its physically client-only `ClientExample` registers the native configuration-menu extension, initializes a new owned JSON document during admitted client setup, binds the client executor and bounded workers, opens a generated `SettingsScreen` and closes its context on native game shutdown. The explicit wiring is provisional; the complete Phase 1 convenience factories will remove this boilerplate.

Run `./gradlew --no-daemon check build` from the repository root to compile this example and its probe against the exact candidate artifact. This compiles native APIs without launching a game. It also runs the independent headless consumer and core/tooling checks.

For a disposable client check, first build/stage the library, obtain sustained desktop focus, then run from the repository root:

```sh
python3 tools/native_form_probe.py --java-home /path/to/jdk21
```

The runner refuses an existing `examples/native/runs/client`, creates its own marked fixture and runs two bounded 2 GiB Minecraft clients sequentially. The probe opens the actual registered menu entry and exercises native fields/invalid drafts/save/reflow/close/reopen; the second JVM checks persisted preferences. Reports/screenshots go to ignored `build/reports/native-form/`. Only the matching task-owned fixture and logs are removed. Personal instances, servers and worlds are not used.

The launches use ModDev source containers for this ordinary example and its separate probe. The library itself loads from the exact packaged Maven JAR, checked both before launch and inside the game. This is not full Phase 1, a native TOML adapter, an integrated/remote-server test or a release.
