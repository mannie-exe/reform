package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.presentation.graphics.GraphicLedger;
import design.inherent.reform.presentation.graphics.GraphicMaterial;
import design.inherent.reform.presentation.graphics.GraphicProjection;
import design.inherent.reform.presentation.graphics.GraphicScene;
import design.inherent.reform.presentation.graphics.RenderLifetime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** Private OpenGL 3.2 renderer, the actual Minecraft 1.21.1 native graphics baseline. */
@SuppressWarnings("try") // Scoped GL resources restore state on close; no body read is needed.
final class NativeMeshGraphic implements AutoCloseable {
  private static final String VERTEX =
      """
      #version 150
      in vec3 Position;
      in vec2 UV;
      in vec4 Color;
      out vec2 uv;
      out vec4 color;
      void main() { gl_Position=vec4(Position,1.0); uv=UV; color=Color; }
      """;
  private static final String FRAGMENT =
      """
      #version 150
      uniform sampler2D Diffuse;
      uniform float Cutoff;
      uniform int Opaque;
      in vec2 uv;
      in vec4 color;
      out vec4 outputColor;
      void main() {
        vec4 sampled=texture(Diffuse,uv)*color;
        if (sampled.a<Cutoff) discard;
        if (Opaque!=0) sampled.a=1.0;
        outputColor=sampled;
      }
      """;

  private static final class Owned {
    final int id;
    final GraphicLedger.Allocation allocation;
    final IntConsumer release;
    boolean attempted, closed;
    Throwable failure;

    Owned(int id, GraphicLedger.Allocation allocation, IntConsumer release) {
      this.id = id;
      this.allocation = allocation;
      this.release = release;
    }

    void close() {
      if (closed) return;
      if (NativeMeshGraphicRuntime.destroyed()) {
        allocation.close();
        closed = true;
        return;
      }
      if (!attempted) {
        attempted = true;
        try {
          release.accept(id);
          allocation.close();
          closed = true;
        } catch (RuntimeException | Error failed) {
          failure = failed;
        }
      }
      if (failure instanceof RuntimeException failed) throw failed;
      if (failure instanceof Error failed) throw failed;
    }
  }

  private final GraphicScene scene;
  private final GraphicLedger ledger;
  private final RenderLifetime lifetime;
  private final int maximumUses;
  private final List<Owned> resources = new ArrayList<>(), frameResources = new ArrayList<>();
  private final Map<DocumentId, Integer> textures = new LinkedHashMap<>();
  private int program, vao, buffer, cutoff, opaque;
  private int framebuffer, colorTexture, frameWidth, frameHeight;
  private GraphicCamera renderedCamera;

  NativeMeshGraphic(GraphicScene scene, GraphicLedger ledger, int maximumUses) {
    RenderSystem.assertOnRenderThread();
    this.scene = scene;
    this.ledger = ledger;
    this.maximumUses = maximumUses;
    lifetime = new RenderLifetime(maximumUses, RenderSystem::assertOnRenderThread, this::release);
    if (!GL.getCapabilities().OpenGL32)
      throw new PolicyRefusal(
          "preview.provider_unavailable",
          "This adapter requires Minecraft's OpenGL 3.2 graphics baseline");
    if (org.lwjgl.glfw.GLFW.glfwGetCurrentContext()
        != net.minecraft.client.Minecraft.getInstance().getWindow().getWindow())
      throw new PolicyRefusal(
          "preview.provider_unavailable",
          "The current OpenGL context is not Minecraft's native window");
    NativeMeshGraphicRuntime.register(this);
    try {
      if (scene.mesh().isEmpty()) return;
      var use = NativeMeshGraphicRuntime.acquire(lifetime);
      NativeMeshGraphicRuntime.submit(
          use,
          () -> {
            try (var state = new NativeGraphicGlState()) {
              initialize();
            }
          });
    } catch (RuntimeException | Error failed) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  private Owned create(long bytes, IntSupplier create, IntConsumer release) {
    var allocation = ledger.reserve(bytes, 1);
    int id = 0;
    try {
      id = create.getAsInt();
      if (id == 0)
        throw new PolicyRefusal(
            "preview.prepare_failed", "Native graphic allocation returned no handle");
      var owned = new Owned(id, allocation, release);
      resources.add(owned);
      return owned;
    } catch (RuntimeException | Error failed) {
      if (id != 0)
        try {
          release.accept(id);
        } catch (RuntimeException | Error cleanup) {
          failed.addSuppressed(cleanup);
          throw failed;
        }
      allocation.close();
      throw failed;
    }
  }

  private Owned shader(int kind, String source) {
    var shader = create(0, () -> GL20.glCreateShader(kind), GL20::glDeleteShader);
    GL20.glShaderSource(shader.id, source);
    GL20.glCompileShader(shader.id);
    if (GL20.glGetShaderi(shader.id, GL20.GL_COMPILE_STATUS) == 0)
      throw new PolicyRefusal(
          "preview.prepare_failed",
          "Private graphic shader compilation failed: " + GL20.glGetShaderInfoLog(shader.id, 4096));
    return shader;
  }

  private void initialize() {
    NativeGraphicGlState.ordinaryTriangles();
    program = create(0, GL20::glCreateProgram, GL20::glDeleteProgram).id;
    var vertex = shader(GL20.GL_VERTEX_SHADER, VERTEX);
    var fragment = shader(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
    GL20.glAttachShader(program, vertex.id);
    GL20.glAttachShader(program, fragment.id);
    GL20.glBindAttribLocation(program, 0, "Position");
    GL20.glBindAttribLocation(program, 1, "UV");
    GL20.glBindAttribLocation(program, 2, "Color");
    GL30.glBindFragDataLocation(program, 0, "outputColor");
    GL20.glLinkProgram(program);
    if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
      throw new PolicyRefusal(
          "preview.prepare_failed",
          "Private graphic program linking failed: " + GL20.glGetProgramInfoLog(program, 4096));
    GL20.glDetachShader(program, vertex.id);
    GL20.glDetachShader(program, fragment.id);
    vertex.close();
    fragment.close();
    resources.removeIf(owned -> owned.closed);
    cutoff = GL20.glGetUniformLocation(program, "Cutoff");
    opaque = GL20.glGetUniformLocation(program, "Opaque");
    GL20.glUseProgram(program);
    GL20.glUniform1i(GL20.glGetUniformLocation(program, "Diffuse"), 0);
    vao = create(0, GL30::glGenVertexArrays, GL30::glDeleteVertexArrays).id;
    long vertexBytes =
        Math.max(6L * GraphicProjection.VERTEX_BYTES, GraphicProjection.vertexBytes(scene.mesh()));
    if (vertexBytes > Integer.MAX_VALUE)
      throw new PolicyRefusal(
          "preview.budget", "The native vertex buffer exceeds its bounded extent");
    buffer = create(vertexBytes, GL15::glGenBuffers, GL15::glDeleteBuffers).id;
    GL30.glBindVertexArray(vao);
    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBytes, GL15.GL_DYNAMIC_DRAW);
    if (GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE) != vertexBytes)
      throw new PolicyRefusal(
          "preview.prepare_failed", "The renderer did not allocate the requested vertex buffer");
    GL20.glEnableVertexAttribArray(0);
    GL20.glEnableVertexAttribArray(1);
    GL20.glEnableVertexAttribArray(2);
    GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 24, 0L);
    GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 24, 12L);
    GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, 24, 20L);
    GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
    for (var entry : scene.textures().entrySet()) {
      var pixels = entry.getValue();
      int texture = create(pixels.bytes(), GL11::glGenTextures, GL11::glDeleteTextures).id;
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
      var allocation = ledger.reserve(pixels.bytes(), 1);
      ByteBuffer data = null;
      try {
        data = MemoryUtil.memAlloc(Math.toIntExact(pixels.bytes()));
        for (int value : pixels.argb())
          data.put((byte) (value >>> 16))
              .put((byte) (value >>> 8))
              .put((byte) value)
              .put((byte) (value >>> 24));
        data.flip();
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            pixels.width(),
            pixels.height(),
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            data);
        if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
            != pixels.width())
          throw new PolicyRefusal(
              "preview.prepare_failed", "The renderer did not allocate the captured texture");
      } finally {
        if (data != null) MemoryUtil.memFree(data);
        allocation.close();
      }
      textures.put(entry.getKey(), texture);
    }
  }

  RenderLifetime.State state() {
    return lifetime.state();
  }

  ClientMeshGraphic.DrawOutcome draw(
      NativeCanvas canvas, int x, int y, int width, int height, GraphicCamera camera) {
    RenderSystem.assertOnRenderThread();
    NativeMeshGraphicRuntime.poll();
    if (lifetime.state().closing())
      throw new PolicyRefusal("preview.closed", "This graphic is closed to native drawing");
    if (scene.mesh().isEmpty()) return ClientMeshGraphic.DrawOutcome.EMPTY;
    var nativeFailure = NativeMeshGraphicRuntime.failure(lifetime);
    if (nativeFailure.isPresent()) {
      var refusal =
          new PolicyRefusal(
              "preview.prepare_failed", "Native graphic retirement could not be confirmed");
      refusal.initCause(nativeFailure.orElseThrow());
      throw refusal;
    }
    if (width < 1 || height < 1) return ClientMeshGraphic.DrawOutcome.WAITING;
    double scale = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
    long w = Math.round(width * scale), h = Math.round(height * scale);
    if (w < 1
        || h < 1
        || w > Integer.MAX_VALUE
        || h > Integer.MAX_VALUE
        || w > ledger.limits().nativeBytes() / 8 / h)
      throw new PolicyRefusal("preview.budget", "The native viewport exceeds its image allowance");
    boolean changed = frameWidth != w || frameHeight != h || !camera.equals(renderedCamera);
    if ((changed && lifetime.state().outstandingUses() > 0)
        || lifetime.state().outstandingUses() >= maximumUses)
      return ClientMeshGraphic.DrawOutcome.WAITING;
    canvas.graphics().flush();
    var ticket = NativeMeshGraphicRuntime.acquire(lifetime);
    try {
      NativeMeshGraphicRuntime.submit(
          ticket,
          () -> {
            if (changed) {
              try (var state = new NativeGraphicGlState()) {
                if (frameWidth != w || frameHeight != h)
                  frame(Math.toIntExact(w), Math.toIntExact(h));
                render(camera);
              }
              renderedCamera = camera;
            }
            canvas.enableScissor(x, y, x + width, y + height);
            try (var state = new NativeGraphicGlState()) {
              composite(canvas, x, y, width, height);
            } finally {
              canvas.disableScissor();
            }
          });
    } catch (RuntimeException | Error failed) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
    return ClientMeshGraphic.DrawOutcome.RENDERED;
  }

  private void frame(int width, int height) {
    NativeGraphicGlState.ordinaryTriangles();
    int maximum = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
    if (width > maximum || height > maximum)
      throw new PolicyRefusal(
          "preview.budget", "The viewport exceeds this renderer's maximum texture extent");
    close(frameResources);
    frameResources.clear();
    resources.removeIf(owned -> owned.closed);
    var target = create(0, GL30::glGenFramebuffers, GL30::glDeleteFramebuffers);
    frameResources.add(target);
    framebuffer = target.id;
    var color = create((long) width * height * 4, GL11::glGenTextures, GL11::glDeleteTextures);
    frameResources.add(color);
    colorTexture = color.id;
    var depth =
        create((long) width * height * 4, GL30::glGenRenderbuffers, GL30::glDeleteRenderbuffers);
    frameResources.add(depth);
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    GL11.glTexImage2D(
        GL11.GL_TEXTURE_2D,
        0,
        GL11.GL_RGBA8,
        width,
        height,
        0,
        GL11.GL_RGBA,
        GL11.GL_UNSIGNED_BYTE,
        (ByteBuffer) null);
    GL30.glFramebufferTexture2D(
        GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTexture, 0);
    GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth.id);
    GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, width, height);
    GL30.glFramebufferRenderbuffer(
        GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth.id);
    if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
      throw new PolicyRefusal(
          "preview.prepare_failed", "The private preview framebuffer is incomplete");
    frameWidth = width;
    frameHeight = height;
  }

  private void render(GraphicCamera camera) {
    var data =
        GraphicProjection.project(
            scene, camera, (double) frameWidth / frameHeight, ledger.limits());
    NativeGraphicGlState.ordinaryTriangles();
    GL11.glDisable(GL11.GL_SCISSOR_TEST);
    GL11.glEnable(GL11.GL_DEPTH_TEST);
    GL11.glDepthFunc(GL11.GL_LEQUAL);
    GL11.glDepthMask(true);
    GL11.glDepthRange(0, 1);
    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
    GL11.glViewport(0, 0, frameWidth, frameHeight);
    GL11.glClearColor(0, 0, 0, 0);
    GL11.glClearDepth(1);
    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    GL20.glUseProgram(program);
    GL30.glBindVertexArray(vao);
    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
    upload(data.vertices());
    for (var draw : data.draws()) {
      var material = scene.materials().get(draw.material());
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures.get(material.texture()));
      int filter =
          material.filter() == GraphicMaterial.Filter.NEAREST ? GL11.GL_NEAREST : GL11.GL_LINEAR;
      int wrap =
          material.wrap() == GraphicMaterial.Wrap.CLAMP ? GL12.GL_CLAMP_TO_EDGE : GL11.GL_REPEAT;
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
      if (material.cull() == GraphicMaterial.Cull.BACK) GL11.glEnable(GL11.GL_CULL_FACE);
      else GL11.glDisable(GL11.GL_CULL_FACE);
      if (material.alpha() == GraphicMaterial.Alpha.TRANSLUCENT) {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDepthMask(false);
        GL14.glBlendFuncSeparate(
            GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            GL11.GL_ONE_MINUS_SRC_ALPHA);
      } else {
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthMask(true);
      }
      GL20.glUniform1f(
          cutoff, material.alpha() == GraphicMaterial.Alpha.CUTOUT ? material.cutoff() : 0);
      GL20.glUniform1i(opaque, material.alpha() != GraphicMaterial.Alpha.TRANSLUCENT ? 1 : 0);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, draw.firstVertex(), draw.vertexCount());
    }
  }

  private void upload(ByteBuffer source) {
    var allocation = ledger.reserve(source.remaining(), 1);
    ByteBuffer direct = null;
    try {
      direct = MemoryUtil.memAlloc(source.remaining());
      direct.put(source).flip();
      GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, direct);
    } finally {
      if (direct != null) MemoryUtil.memFree(direct);
      allocation.close();
    }
  }

  private void composite(NativeCanvas canvas, int x, int y, int width, int height) {
    NativeGraphicGlState.ordinaryTriangles();
    GL11.glDisable(GL11.GL_CULL_FACE);
    GL11.glDisable(GL11.GL_DEPTH_TEST);
    GL11.glDepthMask(false);
    GL11.glEnable(GL11.GL_BLEND);
    GL14.glBlendFuncSeparate(
        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
    GL20.glUseProgram(program);
    GL20.glUniform1f(cutoff, 0);
    GL20.glUniform1i(opaque, 0);
    GL30.glBindVertexArray(vao);
    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture);
    var data = ByteBuffer.allocate(144).order(ByteOrder.nativeOrder());
    float[] pose = new float[16], modelView = new float[16], projection = new float[16];
    canvas.graphics().pose().last().pose().get(pose);
    RenderSystem.getModelViewMatrix().get(modelView);
    RenderSystem.getProjectionMatrix().get(projection);
    vertex(data, x, y, 0, 1, pose, modelView, projection);
    vertex(data, x, y + height, 0, 0, pose, modelView, projection);
    vertex(data, x + width, y + height, 1, 0, pose, modelView, projection);
    vertex(data, x, y, 0, 1, pose, modelView, projection);
    vertex(data, x + width, y + height, 1, 0, pose, modelView, projection);
    vertex(data, x + width, y, 1, 1, pose, modelView, projection);
    data.flip();
    upload(data);
    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
  }

  private static void vertex(
      ByteBuffer output,
      float x,
      float y,
      float u,
      float v,
      float[] pose,
      float[] modelView,
      float[] projection) {
    double[] point = {x, y, 0, 1},
        transformed = new double[4],
        view = new double[4],
        clip = new double[4];
    for (int row = 0; row < 4; row++)
      for (int column = 0; column < 4; column++)
        transformed[row] += pose[column * 4 + row] * point[column];
    for (int row = 0; row < 4; row++)
      for (int column = 0; column < 4; column++)
        view[row] += modelView[column * 4 + row] * transformed[column];
    for (int row = 0; row < 4; row++)
      for (int column = 0; column < 4; column++)
        clip[row] += projection[column * 4 + row] * view[column];
    if (clip[3] == 0 || !Double.isFinite(clip[3]))
      throw new PolicyRefusal(
          "presentation.invalid_declaration", "The native GUI pose cannot project this viewport");
    output
        .putFloat((float) (clip[0] / clip[3]))
        .putFloat((float) (clip[1] / clip[3]))
        .putFloat((float) (clip[2] / clip[3]))
        .putFloat(u)
        .putFloat(v)
        .putInt(-1);
  }

  private static void close(List<Owned> selected) {
    Throwable failure = null;
    for (var owned : selected.reversed())
      try {
        owned.close();
      } catch (RuntimeException | Error failed) {
        if (failure == null) failure = failed;
        else if (failure != failed) failure.addSuppressed(failed);
      }
    if (failure instanceof RuntimeException failed) throw failed;
    if (failure instanceof Error failed) throw failed;
  }

  private void release() {
    close(resources);
    resources.clear();
    frameResources.clear();
    textures.clear();
    NativeMeshGraphicRuntime.released(this);
  }

  void contextDestroyed() {
    for (var owned : resources) {
      owned.allocation.close();
      owned.closed = true;
    }
    resources.clear();
    frameResources.clear();
    textures.clear();
    NativeMeshGraphicRuntime.released(this);
  }

  @Override
  public void close() {
    lifetime.close();
  }
}
