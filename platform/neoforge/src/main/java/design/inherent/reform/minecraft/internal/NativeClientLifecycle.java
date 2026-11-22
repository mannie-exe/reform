package design.inherent.reform.minecraft.internal;

import java.util.Objects;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * One native process subscription; documents and world contexts retire inside the shared runtime.
 */
final class NativeClientLifecycle {
  private static boolean installed;

  static synchronized void install(Runnable shutdown, Runnable tick) {
    Objects.requireNonNull(shutdown);
    Objects.requireNonNull(tick);
    if (installed) throw new IllegalStateException("Client lifecycle is already installed");
    NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, event -> shutdown.run());
    NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick.run());
    installed = true;
  }

  private NativeClientLifecycle() {}
}
