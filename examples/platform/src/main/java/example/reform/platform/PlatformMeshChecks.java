package example.reform.platform;

import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ClientMeshGraphic;
import design.inherent.reform.minecraft.client.FormCanvas;
import design.inherent.reform.minecraft.client.NativeFormScreen;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Actual pixels, depth/alpha/UVs, close-after-submission, and native ownership; no world needed.
 */
final class PlatformMeshChecks implements AutoCloseable {
  private static final DocumentId TEXTURE = DocumentId.parse("fixture:mesh_texture"),
      SOLID = DocumentId.parse("fixture:solid"),
      GLASS = DocumentId.parse("fixture:glass");
  private static final GraphicLimits LIMITS =
      new GraphicLimits(65536, 65536, 1024 * 1024, 64, 16 * 1024 * 1024);
  private static final GraphicLighting UNLIT =
      new GraphicLighting(GraphicLighting.Shading.UNLIT, Optional.empty(), 0, false);
  private final Minecraft client = Minecraft.getInstance();
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final GraphicLedger ledger = new GraphicLedger(LIMITS);
  private final AtomicBoolean current = new AtomicBoolean(true);
  private GraphicLedger failedLedger;
  private ClientMeshGraphic graphic;
  private Screen parent;
  private View view;
  private Throwable renderFailure;
  private int stage, frames;
  private boolean awaiting, closeAfterDraw, closedAfterDraw, revokedObserved;
  private int outstandingAtClose;
  private long bytesAtClose, preparationNanos, renderNanos, maximumRenderNanos;
  private int renderCalls;

  PlatformMeshChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private static GraphicMaterial material(GraphicMaterial.Alpha alpha) {
    return new GraphicMaterial(
        TEXTURE,
        alpha,
        0.1f,
        GraphicMaterial.Cull.BACK,
        GraphicMaterial.Filter.NEAREST,
        GraphicMaterial.Wrap.CLAMP);
  }

  private static void quad(
      List<GraphicMesh.Vertex> vertices,
      List<GraphicMesh.Triangle> triangles,
      float x0,
      float y0,
      float x1,
      float y1,
      float z,
      int color,
      DocumentId material) {
    int start = vertices.size();
    vertices.add(new GraphicMesh.Vertex(x0, y0, z, 0, 1, color, 0, 0, 1));
    vertices.add(new GraphicMesh.Vertex(x1, y0, z, 1, 1, color, 0, 0, 1));
    vertices.add(new GraphicMesh.Vertex(x1, y1, z, 1, 0, color, 0, 0, 1));
    vertices.add(new GraphicMesh.Vertex(x0, y1, z, 0, 0, color, 0, 0, 1));
    triangles.add(new GraphicMesh.Triangle(start, start + 1, start + 2, material, UNLIT));
    triangles.add(new GraphicMesh.Triangle(start, start + 2, start + 3, material, UNLIT));
  }

  static GraphicScene pattern() {
    var vertices = new ArrayList<GraphicMesh.Vertex>();
    var triangles = new ArrayList<GraphicMesh.Triangle>();
    quad(vertices, triangles, -1, -1, 1, 1, 0, -1, SOLID);
    return new GraphicScene(
        new GraphicMesh(LIMITS, vertices, triangles),
        Map.of(SOLID, material(GraphicMaterial.Alpha.OPAQUE)),
        Map.of(
            TEXTURE,
            new GraphicPixels(
                LIMITS,
                TEXTURE,
                2,
                2,
                new int[] {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00})));
  }

  private static GraphicScene layers() {
    var vertices = new ArrayList<GraphicMesh.Vertex>();
    var triangles = new ArrayList<GraphicMesh.Triangle>();
    quad(vertices, triangles, -0.75f, -0.75f, 0.75f, 0.75f, 0.2f, 0x80ff0000, GLASS);
    quad(vertices, triangles, -1, -1, 0, 1, -0.2f, 0xff0000ff, SOLID);
    return new GraphicScene(
        new GraphicMesh(LIMITS, vertices, triangles),
        Map.of(
            SOLID,
            material(GraphicMaterial.Alpha.OPAQUE),
            GLASS,
            material(GraphicMaterial.Alpha.TRANSLUCENT)),
        Map.of(TEXTURE, new GraphicPixels(LIMITS, TEXTURE, 1, 1, new int[] {-1})));
  }

  private final class View extends NativeFormScreen {
    int viewportWidth = 120, viewportHeight = 120;
    GraphicCamera camera = new GraphicCamera(0, 0, 1.6, 0, 0);

    View() {
      super(Component.literal("Private native mesh qualification"));
    }

    @Override
    protected void init() {
      addRenderableWidget(
          Button.builder(
                  Component.literal("Rotate preview"),
                  button -> camera = new GraphicCamera(25, 20, 1.6, 0, 0))
              .bounds(180, 36, 140, 20)
              .build());
    }

    @Override
    public void render(FormCanvas canvas, int x, int y, float delta) {
      canvas.fill(0, 0, width, height, 0xff202020);
      super.render(canvas, x, y, delta);
      if (renderFailure != null) return;
      try {
        long start = System.nanoTime();
        var outcome = graphic.draw(canvas, 24, 36, viewportWidth, viewportHeight, camera);
        long elapsed = System.nanoTime() - start;
        renderCalls++;
        renderNanos += elapsed;
        maximumRenderNanos = Math.max(maximumRenderNanos, elapsed);
        if (outcome == ClientMeshGraphic.DrawOutcome.RENDERED) {
          frames++;
          if (closeAfterDraw) {
            outstandingAtClose = graphic.state().outstandingUses();
            graphic.close();
            bytesAtClose = ledger.state().bytes();
            closedAfterDraw = true;
            closeAfterDraw = false;
          }
        }
      } catch (PolicyRefusal refused) {
        if (!current.get()
            && refused.report().issues().stream()
                .anyMatch(issue -> issue.code().equals("preview.stale"))) revokedObserved = true;
        else if (!closedAfterDraw) renderFailure = refused;
      } catch (RuntimeException failed) {
        renderFailure = failed;
      }
    }
  }

  private void prepare(GraphicScene scene) {
    long start = System.nanoTime();
    graphic = ClientMeshGraphic.prepare(scene, ledger, 8, current::get);
    preparationNanos += System.nanoTime() - start;
    frames = 0;
    closedAfterDraw = false;
  }

  private void sample(String filename, double fx, double fy, int expected) throws Exception {
    var image =
        ImageIO.read(client.gameDirectory.toPath().resolve("platform-" + filename).toFile());
    int x = (int) Math.floor((24 + fx * view.viewportWidth) * image.getWidth() / view.width),
        y = (int) Math.floor((36 + fy * view.viewportHeight) * image.getHeight() / view.height);
    int observed = image.getRGB(x, y);
    for (int shift : new int[] {16, 8, 0})
      if (Math.abs(((observed >>> shift) & 255) - ((expected >>> shift) & 255)) > 4)
        throw new AssertionError(
            "Actual graphic pixel "
                + filename
                + "@"
                + x
                + ","
                + y
                + " expected="
                + Integer.toHexString(expected)
                + " observed="
                + Integer.toHexString(observed));
  }

  private void capture(String name, Runnable checked) {
    awaiting = true;
    capture.accept(
        name,
        () -> {
          checked.run();
          awaiting = false;
          stage++;
        });
  }

  private void checkedSamples(String name, boolean pattern) {
    try {
      if (pattern) {
        sample(name, 0.25, 0.25, 0xffff0000);
        sample(name, 0.75, 0.25, 0xff00ff00);
        sample(name, 0.25, 0.75, 0xff0000ff);
        sample(name, 0.75, 0.75, 0xffffff00);
      } else {
        // Native GUI fills can carry a game-owned color modulator. Compare transparent pixels
        // against an independent, uncovered sample of the same native background, not its input.
        var image =
            ImageIO.read(client.gameDirectory.toPath().resolve("platform-" + name).toFile());
        int background = image.getRGB(image.getWidth() - 20, image.getHeight() - 20);
        int overBackground = 0xff000000;
        for (int shift : new int[] {16, 8, 0}) {
          int source = shift == 16 ? 255 : 0;
          int destination = (background >>> shift) & 255;
          overBackground |= ((source * 128 + destination * 127 + 127) / 255) << shift;
        }
        sample(name, 0.2, 0.5, 0xff0000ff);
        sample(name, 0.4, 0.5, 0xff80007f);
        sample(name, 0.7, 0.5, overBackground);
        sample(name, 0.95, 0.5, background);
      }
      check.accept(
          pattern
              ? "native private texture preserves UV orientation and RGBA channels"
              : "native depth and premultiplied alpha match opaque and empty backgrounds",
          true);
    } catch (Exception failed) {
      throw new AssertionError(failed);
    }
  }

  boolean tick() throws Exception {
    if (renderFailure != null) throw new AssertionError("Native mesh render failed", renderFailure);
    if (awaiting) return false;
    switch (stage) {
      case 0 -> {
        parent = NativeScreens.current(client);
        var emptyLedger = new GraphicLedger(LIMITS);
        var empty =
            ClientMeshGraphic.prepare(
                new GraphicScene(new GraphicMesh(LIMITS, List.of(), List.of()), Map.of(), Map.of()),
                emptyLedger,
                1,
                () -> true);
        check.accept(
            "ready-empty native mesh creates no graphics allocation",
            emptyLedger.state().resources() == 0);
        empty.close();
        check.accept("ready-empty native mesh retires normally", empty.state().released());
        failedLedger =
            new GraphicLedger(new GraphicLimits(65536, 65536, 1024 * 1024, 2, 16 * 1024 * 1024));
        try {
          ClientMeshGraphic.prepare(pattern(), failedLedger, 2, () -> true);
          throw new AssertionError("Native allocation budget did not refuse");
        } catch (PolicyRefusal refused) {
          check.accept(
              "entered native allocation refuses its object budget",
              refused.report().issues().stream()
                  .anyMatch(issue -> issue.code().equals("preview.budget")));
        }
        check.accept(
            "failed native preparation retains entered ownership until retirement",
            failedLedger.state().resources() > 0);
        prepare(pattern());
        view = new View();
        NativeScreens.open(client, view);
        stage++;
      }
      case 1 -> {
        if (frames < 2) return false;
        check.accept(
            "failed native preparation drains all owned allocations",
            failedLedger.state().resources() == 0);
        capture("form-graphic-texture.png", () -> checkedSamples("form-graphic-texture.png", true));
      }
      case 2 -> {
        closeAfterDraw = true;
        stage++;
      }
      case 3 -> {
        if (!closedAfterDraw) return false;
        check.accept(
            "close after native draw/extraction prevents new uses but retains submitted resources",
            outstandingAtClose > 0 && bytesAtClose > 0 && graphic.state().closing());
        stage++;
      }
      case 4 -> {
        if (!graphic.state().released()) return false;
        check.accept(
            "actual native retirement releases the complete prior scene",
            ledger.state().bytes() == 0 && ledger.state().resources() == 0);
        prepare(layers());
        stage++;
      }
      case 5 -> {
        if (frames < 2) return false;
        capture("form-graphic-layers.png", () -> checkedSamples("form-graphic-layers.png", false));
      }
      case 6 -> {
        view.viewportWidth = 160;
        view.viewportHeight = 80;
        PlatformWidgets.click(view, PlatformWidgets.button(view, "Rotate preview"));
        frames = 0;
        stage++;
      }
      case 7 -> {
        if (frames < 2) return false;
        check.accept(
            "native resize and named orbit control retain the same prepared subject",
            view.camera.yaw() == 25 && graphic.problem().isEmpty());
        capture("form-graphic-orbit.png", () -> {});
      }
      case 8 -> {
        current.set(false);
        stage++;
      }
      case 9 -> {
        if (!revokedObserved || !graphic.state().released()) return false;
        check.accept(
            "resource revocation blocks submission and drains owned graphics",
            ledger.state().bytes() == 0 && ledger.state().resources() == 0);
        NativeScreens.open(client, parent);
        stage++;
        return true;
      }
      default -> {
        return true;
      }
    }
    return false;
  }

  JsonObject report() {
    var report = new JsonObject();
    report.addProperty("stage", stage);
    report.addProperty("preparationNanos", preparationNanos);
    report.addProperty("nativeDrawCalls", renderCalls);
    report.addProperty("nativeDrawCallNanos", renderNanos);
    report.addProperty("maximumNativeDrawCallNanos", maximumRenderNanos);
    report.addProperty(
        "timingScope",
        "Client submission call only, including cold frame/camera updates; not GPU/frame latency");
    report.addProperty("outstandingAtImmediateClose", outstandingAtClose);
    report.addProperty("nativePayloadAtImmediateClose", bytesAtClose);
    report.addProperty("remainingNativePayload", ledger.state().bytes());
    report.addProperty("remainingNativeObjects", ledger.state().resources());
    report.addProperty("peakKnownNativePayload", ledger.state().peakBytes());
    report.addProperty("peakNativeObjects", ledger.state().peakResources());
    if (renderFailure != null) report.addProperty("failure", renderFailure.toString());
    return report;
  }

  @Override
  public void close() {
    if (graphic != null) graphic.close();
    if (view != null && NativeScreens.current(client) == view) NativeScreens.open(client, parent);
  }
}
