package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.presentation.graphics.GraphicLedger;
import design.inherent.reform.presentation.graphics.RenderLifetime;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Typed content in the same runtime, ledger, and exact-use retirement as meshes. */
final class ClientNativeGraphic implements ClientGraphicLease, NativeOwnedGraphic {
  private final RenderLifetime lifetime;
  private final BooleanSupplier current;
  private final int maximumUses;
  private final List<Resource> resources = new ArrayList<>();
  private final IdentityHashMap<NativeGraphicResource, Resource> retained = new IdentityHashMap<>();
  private GraphicLedger ledger;
  private GraphicLedger.Allocation binding;
  private NativeGraphicAdapter.Prepared content;
  private GraphicCamera submittedCamera;
  private int submittedWidth, submittedHeight;

  private static final class Resource {
    final NativeGraphicResource value;
    final GraphicLedger.Allocation allocation;
    boolean attempted, teardown, released;
    Throwable failure;

    Resource(NativeGraphicResource value, GraphicLedger.Allocation allocation) {
      this.value = value;
      this.allocation = allocation;
    }

    void release(boolean destroyed) {
      if (released) return;
      if (!attempted || destroyed && !teardown) {
        attempted = true;
        teardown |= destroyed;
        try {
          value.release(new NativeGraphicReleaseContext(destroyed));
          allocation.close();
          released = true;
          failure = null;
        } catch (RuntimeException | Error failed) {
          failure = failed;
        }
      }
      if (failure instanceof RuntimeException failed) throw failed;
      if (failure instanceof Error failed) throw failed;
    }
  }

  private void own(NativeGraphicResource value) {
    if (retained.containsKey(value)) return;
    if (lifetime.state().closing())
      throw new PolicyRefusal("preview.closed", "Native preparation is closing");
    var entry = new Resource(value, ledger.reserve(0, 1));
    resources.add(entry);
    retained.put(value, entry);
  }

  static <I> ClientNativeGraphic prepare(
      NativeGraphicContribution<I> data,
      GraphicLedger ledger,
      int maximumUses,
      BooleanSupplier current,
      Consumer<RenderLifetime> observation) {
    ClientSettingsRuntime.requireClientThread();
    var graphic = new ClientNativeGraphic(current, maximumUses);
    try {
      observation.accept(graphic.lifetime);
      if (!current.getAsBoolean())
        throw new PolicyRefusal("preview.stale", "Native graphic input is no longer current");
      if (!data.adapterId().equals(data.adapter().id()))
        throw new PolicyRefusal("preview.stale", "Native adapter identity changed");
      NativeMeshGraphicRuntime.register(graphic);
      graphic.ledger = ledger;
      graphic.binding = ledger.reserve(0, 1);
      NativeAdapterSubmission.initialize(
          graphic.lifetime,
          () -> {
            graphic.content =
                Objects.requireNonNull(
                    data.adapter()
                        .prepare(
                            data.input(),
                            new NativeGraphicPreparationContext(
                                Minecraft.getInstance(),
                                ledger,
                                data.stamp(),
                                graphic.lifetime,
                                graphic.current,
                                graphic::own)));
            // The binding was admitted before preparation. Transfer it while the preparation
            // ticket is still entered, even if a reentrant callback requested closure. No second
            // reservation may strand an already-created returned resource.
            if (!graphic.retained.containsKey(graphic.content)) {
              var entry = new Resource(graphic.content, graphic.binding);
              graphic.resources.add(entry);
              graphic.retained.put(graphic.content, entry);
              graphic.binding = null;
            }
          });
      if (!current.getAsBoolean())
        throw new PolicyRefusal("preview.stale", "Native graphic input changed during preparation");
      return graphic;
    } catch (RuntimeException | Error failed) {
      try {
        graphic.close();
      } catch (RuntimeException | Error cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  private ClientNativeGraphic(BooleanSupplier current, int maximumUses) {
    this.current = Objects.requireNonNull(current);
    this.maximumUses = maximumUses;
    lifetime =
        new RenderLifetime(maximumUses, ClientSettingsRuntime::requireClientThread, this::release);
  }

  @Override
  public RenderLifetime.State state() {
    ClientSettingsRuntime.requireClientThread();
    return lifetime.state();
  }

  @Override
  public CompletionStage<RenderLifetime.State> releaseCompletion() {
    return lifetime.releaseCompletion();
  }

  @Override
  public ClientMeshGraphic.DrawOutcome draw(
      FormCanvas canvas, int x, int y, int width, int height, GraphicCamera camera) {
    ClientSettingsRuntime.requireClientThread();
    NativeMeshGraphicRuntime.poll();
    if (!current.getAsBoolean()) {
      close();
      throw new PolicyRefusal("preview.stale", "Native graphic source changed; prepare it again");
    }
    if (lifetime.state().closing())
      throw new PolicyRefusal("preview.closed", "Native graphic is closed to new uses");
    var failure = NativeMeshGraphicRuntime.failure(lifetime);
    if (failure.isPresent()) {
      var refused =
          new PolicyRefusal(
              "preview.prepare_failed", "Native graphic preparation or retirement failed");
      refused.initCause(failure.orElseThrow());
      throw refused;
    }
    if (width < 1
        || height < 1
        || lifetime.state().outstandingUses() >= maximumUses
        || (!camera.equals(submittedCamera) || width != submittedWidth || height != submittedHeight)
            && lifetime.state().outstandingUses() > 0) return ClientMeshGraphic.DrawOutcome.WAITING;
    double scale = Minecraft.getInstance().getWindow().getGuiScale();
    long w = Math.round(width * scale), h = Math.round(height * scale);
    if (w < 1
        || h < 1
        || w > Integer.MAX_VALUE
        || h > Integer.MAX_VALUE
        || w > ledger.limits().nativeBytes() / 8 / h)
      throw new PolicyRefusal("preview.budget", "The native viewport exceeds its image allowance");
    var outcome =
        NativeAdapterSubmission.draw(
            lifetime, (NativeCanvas) canvas, x, y, width, height, camera, content);
    if (outcome != ClientMeshGraphic.DrawOutcome.WAITING) {
      submittedCamera = camera;
      submittedWidth = width;
      submittedHeight = height;
    }
    return outcome;
  }

  private void release() {
    release(NativeAdapterSubmission.destroyed());
  }

  private void release(boolean destroyed) {
    NativeGraphicCleanup.run(
        resources.reversed().stream()
            .map(entry -> (Runnable) () -> entry.release(destroyed))
            .toArray(Runnable[]::new));
    resources.clear();
    retained.clear();
    content = null;
    if (binding != null) {
      binding.close();
      binding = null;
    }
    NativeAdapterSubmission.released(this, lifetime);
  }

  @Override
  public void close() {
    ClientSettingsRuntime.requireClientThread();
    lifetime.close();
  }

  @Override
  public void contextDestroyed() {
    ClientSettingsRuntime.requireClientThread();
    // Teardown may account for actually destroyed GPU objects, but never rewrites a failed receipt.
    release(true);
  }
}
