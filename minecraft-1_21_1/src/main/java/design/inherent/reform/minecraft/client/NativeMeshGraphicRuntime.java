package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.RenderLifetime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;

/** Renderer-lived ownership. Polling observes real GL fences; elapsed frames never retire a use. */
public final class NativeMeshGraphicRuntime {
  private static final int MAXIMUM_GRAPHICS = 128, MAXIMUM_USES = 512;
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(NativeMeshGraphicRuntime.class);
  private static final Set<NativeOwnedGraphic> LIVE =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private static final List<Ticket> USES = new ArrayList<>();
  private static ClientRegistration.Subscription subscription;
  private static boolean closing, destroyed;

  static boolean destroyed() {
    return destroyed;
  }

  static void register(NativeOwnedGraphic graphic) {
    RenderSystem.assertOnRenderThread();
    if (closing || LIVE.size() >= MAXIMUM_GRAPHICS)
      throw new PolicyRefusal("preview.budget", "Native graphic admission is closed or full");
    if (subscription == null)
      subscription = ClientSettingsRuntime.onTick(NativeMeshGraphicRuntime::poll);
    LIVE.add(graphic);
  }

  static void released(NativeOwnedGraphic graphic) {
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

  static final class Ticket {
    final RenderLifetime lifetime;
    final RenderLifetime.Use use;
    long fence;
    boolean submitted;
    Throwable failure;

    Ticket(RenderLifetime lifetime) {
      this.lifetime = lifetime;
      this.use = lifetime.acquire();
    }

    void submitted() {
      submitted = true;
      try {
        fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (fence == 0)
          throw new PolicyRefusal(
              "preview.prepare_failed", "The renderer did not create a retirement fence");
        GL11.glFlush();
      } catch (RuntimeException | Error failed) {
        failure = failed;
        throw failed;
      }
    }
  }

  static java.util.Optional<Throwable> failure(RenderLifetime lifetime) {
    return USES.stream()
        .filter(ticket -> ticket.lifetime == lifetime && ticket.failure != null)
        .map(ticket -> ticket.failure)
        .findFirst();
  }

  static void submit(Ticket ticket, Runnable action) {
    Throwable failure = null;
    try {
      action.run();
    } catch (RuntimeException | Error failed) {
      failure = failed;
    }
    try {
      ticket.submitted();
    } catch (RuntimeException | Error failed) {
      if (failure == null) failure = failed;
      else failure.addSuppressed(failed);
    }
    if (failure instanceof RuntimeException failed) throw failed;
    if (failure instanceof Error failed) throw failed;
  }

  public static void poll() {
    if (USES.isEmpty()) {
      unsubscribeIfIdle();
      return;
    }
    RenderSystem.assertOnRenderThread();
    for (var ticket : List.copyOf(USES)) {
      if (!ticket.submitted || ticket.failure != null) continue;
      if (ticket.fence == 0) {
        // A failed marker retains ownership. A later actual marker can still cover prior commands.
        ticket.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (ticket.fence == 0) continue;
        GL11.glFlush();
      }
      int status = GL32.glClientWaitSync(ticket.fence, 0, 0);
      if (status == GL32.GL_WAIT_FAILED) {
        ticket.failure =
            new PolicyRefusal(
                "preview.prepare_failed",
                "The renderer could not confirm native graphic completion");
        LOG.error(
            "Graphic GPU retirement could not be confirmed; ownership is retained", ticket.failure);
        continue;
      }
      if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) continue;
      GL32.glDeleteSync(ticket.fence);
      USES.remove(ticket);
      try {
        ticket.use.retire();
      } catch (RuntimeException | Error failed) {
        LOG.error("Graphic native-use retirement failed; remaining ownership is retained", failed);
      }
    }
    unsubscribeIfIdle();
  }

  /** Actual Minecraft close entry, before its Window destroys the OpenGL context. */
  public static void shutdown() {
    if (LIVE.isEmpty() && USES.isEmpty()) {
      closing = true;
      return;
    }
    RenderSystem.assertOnRenderThread();
    closing = true;
    for (var graphic : List.copyOf(LIVE)) {
      try {
        graphic.close();
      } catch (RuntimeException | Error failed) {
        LOG.error("Graphic close failed during renderer shutdown", failed);
      }
    }
    try {
      if (!USES.isEmpty()) {
        GL11.glFinish(); // Actual completion of all entered GL work, not a timed disposal delay.
        for (var ticket : List.copyOf(USES)) {
          if (ticket.fence != 0) GL32.glDeleteSync(ticket.fence);
          USES.remove(ticket);
          try {
            ticket.use.retire();
          } catch (RuntimeException | Error failed) {
            LOG.error("Graphic release failed after native shutdown drain", failed);
          }
        }
      }
    } catch (RuntimeException | Error failed) {
      LOG.error(
          "Graphic shutdown drain failed; ownership remains until actual context teardown", failed);
    }
    if (subscription != null) {
      subscription.close();
      subscription = null;
    }
  }

  /**
   * Window.close returned after destroying this exact Minecraft context. No GL call is valid now.
   */
  public static void contextDestroyed() {
    closing = true;
    destroyed = true;
    for (var ticket : List.copyOf(USES)) {
      USES.remove(ticket);
      try {
        ticket.use.retire();
      } catch (RuntimeException | Error failed) {
        LOG.error("Graphic teardown accounting failed", failed);
      }
    }
    for (var graphic : List.copyOf(LIVE)) graphic.contextDestroyed();
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
