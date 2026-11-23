package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuFence;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.RenderLifetime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * Tracks the exact extracted state and then actual submitted GPU work, independently of editors.
 */
public final class NativeMeshGraphicRuntime {
  private static final int MAXIMUM_GRAPHICS = 128, MAXIMUM_USES = 512;
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(NativeMeshGraphicRuntime.class);
  private static final Set<NativeMeshGraphic> LIVE =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private static final Set<Ticket> USES = Collections.newSetFromMap(new IdentityHashMap<>());
  private static final IdentityHashMap<GuiRenderState, Frame> EXTRACTED = new IdentityHashMap<>();
  private static final List<Batch> RETIRING = new ArrayList<>();
  private static final IdentityHashMap<RenderLifetime, Throwable> FAILURES =
      new IdentityHashMap<>();
  private static ClientRegistration.Subscription subscription;
  private static boolean closing;

  private static final class Frame {
    final List<Ticket> tickets = new ArrayList<>();
    boolean consuming;
  }

  private static final class Batch {
    final List<Ticket> tickets;
    GpuFence fence;
    Throwable failure;

    Batch(List<Ticket> tickets) {
      this.tickets = List.copyOf(tickets);
    }
  }

  static final class Ticket {
    final RenderLifetime lifetime;
    final RenderLifetime.Use use;
    Runnable cleanup = () -> {};

    Ticket(RenderLifetime lifetime) {
      this.lifetime = lifetime;
      use = lifetime.acquire();
    }
  }

  static void register(NativeMeshGraphic graphic) {
    RenderSystem.assertOnRenderThread();
    if (closing || LIVE.size() >= MAXIMUM_GRAPHICS)
      throw new PolicyRefusal("preview.budget", "Native graphic admission is closed or full");
    if (subscription == null)
      subscription = ClientSettingsRuntime.onTick(NativeMeshGraphicRuntime::poll);
    LIVE.add(graphic);
  }

  static void released(NativeMeshGraphic graphic) {
    LIVE.remove(graphic);
    unsubscribeIfIdle();
  }

  static Ticket acquire(RenderLifetime lifetime) {
    poll();
    if (closing || USES.size() >= MAXIMUM_USES)
      throw new PolicyRefusal("preview.budget", "Earlier native graphic uses have not retired");
    var ticket = new Ticket(lifetime);
    USES.add(ticket);
    return ticket;
  }

  static Optional<Throwable> failure(RenderLifetime lifetime) {
    return Optional.ofNullable(FAILURES.get(lifetime));
  }

  static void released(RenderLifetime lifetime) {
    FAILURES.remove(lifetime);
  }

  static void submitted(Ticket ticket, Runnable cleanup) {
    ticket.cleanup = cleanup;
    submitted(List.of(ticket));
  }

  private static void submitted(List<Ticket> tickets) {
    if (tickets.isEmpty()) return;
    var batch = new Batch(tickets);
    RETIRING.add(batch);
    try {
      batch.fence = fence();
    } catch (RuntimeException | Error failed) {
      batch.failure = failed;
      for (var ticket : tickets) FAILURES.putIfAbsent(ticket.lifetime, failed);
      LOG.error("Graphic retirement marker failed; ownership is retained", failed);
    }
  }

  private static GpuFence fence() {
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    var fence = encoder.createFence();
    try {
      encoder.submit();
      return fence;
    } catch (RuntimeException | Error failed) {
      try {
        fence.close();
      } catch (RuntimeException | Error cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  static void extracted(GuiRenderState state, Ticket ticket) {
    EXTRACTED.computeIfAbsent(state, ignored -> new Frame()).tickets.add(ticket);
  }

  public static void begin(GuiRenderState state) {
    var frame = EXTRACTED.get(state);
    if (frame != null) frame.consuming = true;
    poll();
  }

  public static void consumed(GuiRenderState state) {
    var frame = EXTRACTED.remove(state);
    if (frame != null) submitted(frame.tickets);
    poll();
  }

  /** Called after actual reset, never while GuiRenderer is still consuming those resources. */
  public static void reset(GuiRenderState state) {
    var frame = EXTRACTED.get(state);
    if (frame == null || frame.consuming) return;
    EXTRACTED.remove(state);
    for (var ticket : frame.tickets) finish(ticket);
    unsubscribeIfIdle();
  }

  public static void poll() {
    if (RETIRING.isEmpty()) {
      unsubscribeIfIdle();
      return;
    }
    RenderSystem.assertOnRenderThread();
    for (var batch : List.copyOf(RETIRING)) {
      if (batch.failure != null) continue;
      try {
        if (!batch.fence.awaitCompletion(0)) continue;
        batch.fence.close();
        RETIRING.remove(batch);
        for (var ticket : batch.tickets) finish(ticket);
      } catch (RuntimeException | Error failed) {
        batch.failure = failed;
        for (var ticket : batch.tickets) FAILURES.putIfAbsent(ticket.lifetime, failed);
        LOG.error("Graphic GPU retirement could not be confirmed; ownership is retained", failed);
      }
    }
    unsubscribeIfIdle();
  }

  private static void finish(Ticket ticket) {
    if (!USES.remove(ticket)) return;
    try {
      ticket.cleanup.run();
    } catch (RuntimeException | Error failed) {
      FAILURES.putIfAbsent(ticket.lifetime, failed);
      LOG.error("Graphic upload cleanup failed", failed);
    }
    try {
      ticket.use.retire();
    } catch (RuntimeException | Error failed) {
      FAILURES.putIfAbsent(ticket.lifetime, failed);
      LOG.error("Graphic release failed after native retirement", failed);
    }
  }

  /** Real renderer shutdown, while the actual device can still confirm and release prior work. */
  public static void shutdown() {
    closing = true;
    if (LIVE.isEmpty() && USES.isEmpty()) return;
    RenderSystem.assertOnRenderThread();
    for (var graphic : List.copyOf(LIVE))
      try {
        graphic.close();
      } catch (RuntimeException | Error failed) {
        LOG.error("Graphic close failed during renderer shutdown", failed);
      }
    if (!USES.isEmpty()) {
      try (var completion = fence()) {
        if (!completion.awaitCompletion(GpuFence.NO_TIMEOUT))
          throw new PolicyRefusal(
              "preview.prepare_failed", "Native shutdown could not confirm graphic completion");
        for (var batch : List.copyOf(RETIRING))
          if (batch.fence != null)
            try {
              batch.fence.close();
            } catch (RuntimeException | Error failed) {
              LOG.error("Graphic fence release failed", failed);
            }
        RETIRING.clear();
        EXTRACTED.clear();
        for (var ticket : List.copyOf(USES)) finish(ticket);
      } catch (RuntimeException | Error failed) {
        LOG.error("Graphic shutdown drain failed; remaining ownership is retained", failed);
      }
    }
    if (subscription != null) {
      subscription.close();
      subscription = null;
    }
  }

  private static void unsubscribeIfIdle() {
    if (LIVE.isEmpty() && USES.isEmpty() && subscription != null) {
      subscription.close();
      subscription = null;
    }
  }

  private NativeMeshGraphicRuntime() {}
}
