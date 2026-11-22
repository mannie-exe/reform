package design.inherent.reform.minecraft.internal;

import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Fabric listeners are process-lived; shared runtime subscriptions retain bounded owner lifetimes.
 */
final class NativeClientLifecycle {
  private static boolean installed;

  static synchronized void install(Runnable shutdown, Runnable tick) {
    Objects.requireNonNull(shutdown);
    Objects.requireNonNull(tick);
    if (installed) throw new IllegalStateException("Client lifecycle is already installed");
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown.run());
    ClientTickEvents.END_CLIENT_TICK.register(client -> tick.run());
    installed = true;
  }

  private NativeClientLifecycle() {}
}
