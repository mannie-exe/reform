package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.presentation.graphics.GraphicLedger;
import design.inherent.reform.presentation.graphics.GraphicScene;
import design.inherent.reform.presentation.graphics.RenderLifetime;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * Private native mesh resources, ready for a shared viewport; never registered as live game assets.
 */
public final class ClientMeshGraphic implements AutoCloseable {
  public enum DrawOutcome {
    RENDERED,
    EMPTY,
    WAITING
  }

  private final NativeMeshGraphic nativeGraphic;
  private final BooleanSupplier current;
  private design.inherent.reform.validation.ValidationReport problem;
  private Throwable failure;

  public java.util.Optional<design.inherent.reform.validation.ValidationReport> problem() {
    return java.util.Optional.ofNullable(problem);
  }

  public java.util.Optional<Throwable> failure() {
    return java.util.Optional.ofNullable(failure);
  }

  /** Explicit native-thread stage after worker-safe domain preparation and input capture. */
  public static ClientMeshGraphic prepare(
      GraphicScene scene, GraphicLedger ledger, int maximumNativeUses, BooleanSupplier current) {
    ClientSettingsRuntime.requireClientThread();
    Objects.requireNonNull(scene).requireBudget(Objects.requireNonNull(ledger).limits());
    design.inherent.reform.presentation.graphics.GraphicProjection.requireSupported(scene);
    Objects.requireNonNull(current);
    if (!current.getAsBoolean())
      throw new PolicyRefusal("preview.stale", "The graphic input is no longer current");
    var nativeGraphic = new NativeMeshGraphic(scene, ledger, maximumNativeUses);
    try {
      if (!current.getAsBoolean())
        throw new PolicyRefusal(
            "preview.stale", "The graphic input changed during native preparation");
      return new ClientMeshGraphic(nativeGraphic, current);
    } catch (RuntimeException | Error failed) {
      try {
        nativeGraphic.close();
      } catch (RuntimeException | Error cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  private ClientMeshGraphic(NativeMeshGraphic nativeGraphic, BooleanSupplier current) {
    this.nativeGraphic = nativeGraphic;
    this.current = current;
  }

  public RenderLifetime.State state() {
    return nativeGraphic.state();
  }

  public DrawOutcome draw(
      FormCanvas canvas, int x, int y, int width, int height, GraphicCamera camera) {
    ClientSettingsRuntime.requireClientThread();
    if (!current.getAsBoolean()) {
      close();
      throw new PolicyRefusal(
          "preview.stale", "The graphic source changed; prepare the preview again");
    }
    var outcome =
        nativeGraphic.draw(
            (NativeCanvas) canvas, x, y, width, height, Objects.requireNonNull(camera));
    if (outcome != DrawOutcome.WAITING) {
      problem = null;
      failure = null;
    }
    return outcome;
  }

  /**
   * A decorative native viewport. Named orbit/zoom/pose actions remain separate shared controls.
   */
  public AbstractWidget widget(Component description, Supplier<GraphicCamera> camera) {
    ClientSettingsRuntime.requireClientThread();
    Objects.requireNonNull(camera);
    return new NativeGraphicWidget(description) {
      @Override
      protected void drawGraphic(FormCanvas canvas) {
        DrawOutcome outcome = null;
        try {
          outcome =
              ClientMeshGraphic.this.draw(
                  canvas, getX(), getY(), getWidth(), getHeight(), camera.get());
        } catch (RuntimeException failed) {
          failure = failed;
          problem =
              failed instanceof PolicyRefusal refused
                  ? refused.report()
                  : new design.inherent.reform.validation.ValidationReport(
                      java.util.List.of(
                          design.inherent.reform.validation.Issue.error(
                              "preview.prepare_failed",
                              design.inherent.reform.definition.FieldPath.ROOT,
                              "The native preview failed; prepare it again")),
                      true);
        }
        Component feedback = null;
        if (problem != null) feedback = Component.literal(problem.issues().getFirst().fallback());
        else if (outcome == DrawOutcome.WAITING)
          feedback =
              Component.translatableWithFallback(
                  "reform.preview.waiting", "Waiting for the renderer");
        else if (outcome == DrawOutcome.EMPTY)
          feedback =
              Component.translatableWithFallback("reform.preview.empty", "No visible geometry");
        if (feedback != null) {
          canvas.enableScissor(getX(), getY(), getX() + getWidth(), getY() + getHeight());
          try {
            canvas.drawWordWrap(
                net.minecraft.client.Minecraft.getInstance().font,
                feedback,
                getX() + 4,
                getY() + 4,
                Math.max(1, getWidth() - 8),
                problem == null ? 0xffbbbbbb : 0xffff5555);
          } finally {
            canvas.disableScissor();
          }
        }
      }
    };
  }

  @Override
  public void close() {
    ClientSettingsRuntime.requireClientThread();
    nativeGraphic.close();
  }
}
