package example.reform;

import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.JsonOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Explicit adapter wiring, to be replaced by the library's qualified convenience lifecycle. */
public final class ClientExample {
  static final JsonLimits LIMITS = new JsonLimits(16384, 16, 1024, 1024, 128, 1024);
  public static final ClientExample INSTANCE = new ClientExample();
  private OwnerContext context;
  private OwnerContext.Access access;
  private JsonOwner<NativeExample.Preferences> owner;
  private ThreadPoolExecutor workers;
  EditorActor<NativeExample.Preferences> editor;
  volatile boolean initialized;
  volatile Throwable failure;

  public boolean initialized() {
    return initialized;
  }

  public EditorActor<NativeExample.Preferences> editor() {
    return editor;
  }

  static void register(IEventBus bus, ModContainer container) {
    if (container.getCustomExtension(IConfigScreenFactory.class).isPresent())
      throw new IllegalStateException("Example already has a configuration entry");
    IConfigScreenFactory factory = (ignored, parent) -> INSTANCE.screen(parent);
    container.registerExtensionPoint(IConfigScreenFactory.class, factory);
    bus.addListener(INSTANCE::setup);
    NeoForge.EVENT_BUS.addListener(INSTANCE::shutdown);
  }

  private void setup(FMLClientSetupEvent event) {
    event.enqueueWork(
        () -> {
          var minecraft = Minecraft.getInstance();
          AtomicInteger sequence = new AtomicInteger();
          workers =
              new ThreadPoolExecutor(
                  2,
                  2,
                  30,
                  TimeUnit.SECONDS,
                  new ArrayBlockingQueue<>(64),
                  task -> {
                    var thread = new Thread(task, "reform-example-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                  });
          workers.allowCoreThreadTimeOut(true);
          context =
              new OwnerContext(
                  minecraft::execute,
                  workers,
                  () -> {
                    if (!minecraft.isSameThread())
                      throw new IllegalStateException("Expected native client thread");
                  },
                  OwnerContext.Limits.STANDARD);
          owner =
              new JsonOwner<>(
                  context,
                  new JsonStore<>(
                      NativeExample.DEFINITION,
                      new OwnedFile(
                          StorageBinding.newJson(
                              FMLPaths.CONFIGDIR.get().toAbsolutePath(),
                              NativeExample.DEFINITION.id()),
                          LIMITS.maximumBytes()),
                      JsonStore.Format.NEW_ENVELOPE,
                      LIMITS,
                      DraftLimits.CONFIGURATION),
                  16);
          access = context.access(() -> true, () -> true);
          var initialization = owner.open(access);
          initialization
              .initialize()
              .whenComplete(
                  (receipt, failed) -> {
                    failure = failed;
                    initialized =
                        failed == null && receipt.application() == JsonOwner.Application.APPLIED;
                    initialization.close();
                  });
        });
  }

  private Screen screen(Screen parent) {
    if (!initialized || context == null)
      throw new IllegalStateException("Example settings are not ready", failure);
    editor =
        new EditorActor<>(
            context,
            owner.open(access),
            NativeExample.DEFINITION,
            LIMITS,
            DraftLimits.CONFIGURATION,
            64);
    return new SettingsScreen<>(
        parent,
        Component.literal("Native example settings"),
        Component.literal("Client preferences · applies to this installation"),
        NativeExample.DEFINITION,
        editor);
  }

  private void shutdown(GameShuttingDownEvent event) {
    if (context != null) context.close();
    if (workers != null) workers.shutdown();
  }
}
