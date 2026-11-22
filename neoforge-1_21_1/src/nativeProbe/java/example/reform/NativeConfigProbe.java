package example.reform;

import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Native source-mode controls only; this probe is excluded from the product and sources JARs. */
@Mod("reform_native_probe")
public final class NativeConfigProbe {
  private final ModConfigSpec spec;
  private final ModConfigSpec.IntValue live, world, game;
  private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
  private final List<String> checks = new ArrayList<>();
  private final AtomicBoolean failNext = new AtomicBoolean();
  private final AtomicInteger ownReloads = new AtomicInteger();
  private volatile ModConfig config;
  private volatile Thread owner;

  public NativeConfigProbe(IEventBus bus, ModContainer container) {
    var builder = new ModConfigSpec.Builder();
    live = builder.defineInRange("live", 1, 0, 100);
    world = builder.worldRestart().defineInRange("world", 1, 0, 100);
    game = builder.gameRestart().defineInRange("game", 1, 0, 100);
    spec = builder.build();
    container.registerConfig(ModConfig.Type.COMMON, spec, "reform-native-probe.toml");
    bus.addListener(this::loaded);
    bus.addListener(this::reloaded);
    NeoForge.EVENT_BUS.addListener(this::started);
  }

  private void loaded(ModConfigEvent.Loading event) {
    if (event.getConfig().getSpec() != spec) return;
    config = event.getConfig();
    events.add(Map.of("kind", "loading", "thread", Thread.currentThread().getName()));
  }

  private void reloaded(ModConfigEvent.Reloading event) {
    if (event.getConfig().getSpec() != spec) return;
    var row = new LinkedHashMap<String, Object>();
    row.put("kind", "reload");
    row.put("thread", Thread.currentThread().getName());
    row.put("ownerThread", Thread.currentThread() == owner);
    row.put("raw", List.of(live.getRaw(), world.getRaw(), game.getRaw()));
    row.put("effective", List.of(live.get(), world.get(), game.get()));
    try {
      var disk = new TomlParser().parse(Files.readString(event.getConfig().getFullPath()));
      row.put("disk", List.of(disk.getInt("live"), disk.getInt("world"), disk.getInt("game")));
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
    events.add(Map.copyOf(row));
    if (Thread.currentThread() == owner) {
      ownReloads.incrementAndGet();
      if (failNext.getAndSet(false))
        throw new IllegalStateException("deliberate after-write probe failure");
    }
  }

  private void check(String name, boolean valid) {
    if (!valid) throw new IllegalStateException("Native control failed: " + name);
    checks.add(name);
  }

  private void preWriteFailure(Map<String, Object> report) throws IOException {
    Path path = config.getFullPath();
    String original = Files.readString(path);
    var parent = path.getParent();
    var permissions = Files.getPosixFilePermissions(parent);
    int before = ownReloads.get();
    try {
      live.set(3);
      world.set(3);
      game.set(3);
      Files.setPosixFilePermissions(
          parent, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
      check("fixture denies directory writes", !Files.isWritable(parent));
      boolean threw = false;
      try {
        spec.save();
      } catch (RuntimeException expected) {
        threw = true;
        report.put("preWriteFailureClass", expected.getClass().getName());
      }
      check("native pre-write failure propagated", threw);
      check("failed persistence did not dispatch reload", ownReloads.get() == before);
      check("failed persistence leaves disk unchanged", Files.readString(path).equals(original));
      check(
          "native raw mutation precedes persistence",
          List.of(live.getRaw(), world.getRaw(), game.getRaw()).equals(List.of(3, 3, 3)));
      check(
          "live cache changes even before failed save",
          List.of(live.get(), world.get(), game.get()).equals(List.of(3, 1, 1)));
    } finally {
      Files.setPosixFilePermissions(parent, permissions);
      // Restore only this fixture's known native inputs before the next independent control.
      live.set(2);
      world.set(2);
      game.set(2);
    }
  }

  private void started(ServerStartedEvent event) {
    owner = Thread.currentThread();
    var report = new LinkedHashMap<String, Object>();
    Path directory = event.getServer().getServerDirectory().toAbsolutePath();
    String mode = "unread";
    try {
      mode =
          JsonParser.parseString(Files.readString(directory.resolve("native-probe-mode.json")))
              .getAsJsonObject()
              .get("mode")
              .getAsString();
      check("real native loaded configuration", config != null && spec.isLoaded());
      report.put("registeredPath", config.getFullPath().getFileName().toString());
      if (mode.equals("reopen")) {
        check(
            "restart reloads persisted raw values",
            List.of(live.getRaw(), world.getRaw(), game.getRaw()).equals(List.of(4, 4, 4)));
        check(
            "restart activates restart-bound values",
            List.of(live.get(), world.get(), game.get()).equals(List.of(4, 4, 4)));
      } else {
        check("explicit first mode", mode.equals("first"));
        check(
            "initial native values",
            List.of(live.get(), world.get(), game.get()).equals(List.of(1, 1, 1)));
        live.set(2);
        world.set(2);
        game.set(2);
        check(
            "raw edits retained",
            List.of(live.getRaw(), world.getRaw(), game.getRaw()).equals(List.of(2, 2, 2)));
        check(
            "restart caches preserved",
            List.of(live.get(), world.get(), game.get()).equals(List.of(2, 1, 1)));
        int before = ownReloads.get();
        long start = System.nanoTime();
        spec.save();
        report.put("saveNanos", System.nanoTime() - start);
        check("same-caller synchronous reload", ownReloads.get() == before + 1);
        Map<String, Object> last = events.getLast();
        check("disk precedes reload callback", last.get("disk").equals(List.of(2, 2, 2)));
        check(
            "restart caches after native save",
            List.of(live.get(), world.get(), game.get()).equals(List.of(2, 1, 1)));
        before = ownReloads.get();
        spec.save();
        check("native no-op save still dispatches reload", ownReloads.get() == before + 1);
        preWriteFailure(report);
        live.set(4);
        world.set(4);
        game.set(4);
        failNext.set(true);
        boolean threw = false;
        try {
          spec.save();
        } catch (RuntimeException expected) {
          threw = true;
          report.put("failureClass", expected.getClass().getName());
        }
        check("native callback failure propagated", threw && !failNext.get());
        var saved = new TomlParser().parse(Files.readString(config.getFullPath()));
        check(
            "failed callback did not erase persisted values",
            List.of(saved.getInt("live"), saved.getInt("world"), saved.getInt("game"))
                .equals(List.of(4, 4, 4)));
        check(
            "failed callback retains native raw values",
            List.of(live.getRaw(), world.getRaw(), game.getRaw()).equals(List.of(4, 4, 4)));
      }
      report.put("status", "PASS");
    } catch (Exception failure) {
      report.put("status", "FAIL");
      report.put("failure", failure.toString());
    } finally {
      report.put("mode", mode);
      report.put("ownerThread", owner.getName());
      report.put("checks", List.copyOf(checks));
      report.put("events", List.copyOf(events));
      try {
        Files.writeString(
            directory.resolve(
                "native-config-" + (mode.equals("reopen") ? "reopen" : "first") + ".json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(report));
      } catch (IOException failure) {
        throw new IllegalStateException(failure);
      } finally {
        event.getServer().halt(false);
      }
    }
  }
}
