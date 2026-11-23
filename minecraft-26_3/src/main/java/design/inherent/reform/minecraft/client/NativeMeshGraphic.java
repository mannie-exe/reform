package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.mixin.GraphicCanvasAccessor;
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
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * Actual RenderPearl private textures, pipelines, and commands; no OpenGL or asset-manager swap.
 */
final class NativeMeshGraphic implements AutoCloseable {
  private static final String VERTEX =
      """
      #version 330
      #extension GL_ARB_separate_shader_objects : require
      layout(location=0) in vec3 Position;
      layout(location=1) in vec2 UV0;
      layout(location=2) in vec4 Color;
      layout(location=0) out vec2 uv;
      layout(location=1) out vec4 color;
      void main() { gl_Position=vec4(Position,1.0); uv=UV0; color=Color; }
      """;
  private static final String FRAGMENT =
      """
      #version 330
      #extension GL_ARB_separate_shader_objects : require
      uniform sampler2D Sampler0;
      layout(std140) uniform Material { vec4 policy; };
      layout(location=0) in vec2 uv;
      layout(location=1) in vec4 color;
      layout(location=0) out vec4 outputColor;
      void main() {
        vec4 sampled=texture(Sampler0,uv)*color;
        if(sampled.a<policy.x)discard;
        if(policy.y>0.5)sampled.a=1.0;
        outputColor=sampled;
      }
      """;
  private static final ShaderSource SHADERS =
      new ShaderSource() {
        @Override
        public String getShader(Identifier id, ShaderType type) {
          return type == ShaderType.VERTEX ? VERTEX : type == ShaderType.FRAGMENT ? FRAGMENT : null;
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
          return null;
        }

        @Override
        public void close() {}
      };

  private record Policy(GraphicMaterial.Alpha alpha, GraphicMaterial.Cull cull) {}

  private record Binding(
      GpuTextureView texture,
      GpuSampler sampler,
      GpuBuffer parameters,
      CompiledRenderPipeline pipeline) {}

  private static final class Owned {
    final Runnable close;
    final GraphicLedger.Allocation allocation;
    boolean attempted, released;
    Throwable failure;

    Owned(Runnable close, GraphicLedger.Allocation allocation) {
      this.close = close;
      this.allocation = allocation;
    }

    void close() {
      if (released) return;
      if (!attempted) {
        attempted = true;
        try {
          close.run();
          allocation.close();
          released = true;
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
  private final int maximumUses;
  private final RenderLifetime lifetime;
  private final List<Owned> resources = new ArrayList<>(),
      frameResources = new ArrayList<>(),
      uploadResources = new ArrayList<>();
  private final Map<DocumentId, Binding> bindings = new LinkedHashMap<>();
  private GpuBuffer vertices;
  private GpuTexture color, depth;
  private GpuTextureView colorView, depthView;
  private GpuSampler outputSampler;
  private int frameWidth, frameHeight;
  private GraphicCamera renderedCamera;

  NativeMeshGraphic(GraphicScene scene, GraphicLedger ledger, int maximumUses) {
    RenderSystem.assertOnRenderThread();
    this.scene = scene;
    this.ledger = ledger;
    this.maximumUses = maximumUses;
    lifetime = new RenderLifetime(maximumUses, RenderSystem::assertOnRenderThread, this::release);
    if (RenderSystem.tryGetDevice() == null)
      throw new PolicyRefusal(
          "preview.provider_unavailable", "The native graphics device is unavailable");
    NativeMeshGraphicRuntime.register(this);
    try {
      if (scene.mesh().isEmpty()) return;
      var ticket = NativeMeshGraphicRuntime.acquire(lifetime);
      try {
        initialize();
      } finally {
        NativeMeshGraphicRuntime.submitted(
            ticket,
            () -> {
              close(uploadResources);
              resources.removeIf(resource -> resource.released);
              uploadResources.clear();
            });
      }
    } catch (RuntimeException | Error failed) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  private <T> T owned(long bytes, Supplier<T> create, Consumer<T> destroy) {
    var allocation = ledger.reserve(bytes, 1);
    T value;
    try {
      value = create.get();
    } catch (RuntimeException | Error failed) {
      allocation.close();
      throw failed;
    }
    resources.add(new Owned(() -> destroy.accept(value), allocation));
    return value;
  }

  private void initialize() {
    var device = RenderSystem.getDevice();
    long bytes = Math.max(1, GraphicProjection.vertexBytes(scene.mesh()));
    if (bytes > Integer.MAX_VALUE)
      throw new PolicyRefusal(
          "preview.budget", "The native vertex buffer exceeds its bounded extent");
    vertices =
        owned(
            bytes,
            () ->
                device.createBuffer(
                    () -> "re:form graphic vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    bytes),
            GpuBuffer::close);
    var textures = new LinkedHashMap<DocumentId, GpuTextureView>();
    var encoder = device.createCommandEncoder();
    for (var entry : scene.textures().entrySet()) {
      var pixels = entry.getValue();
      var texture =
          owned(
              pixels.bytes(),
              () ->
                  device.createTexture(
                      "re:form private graphic texture",
                      GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                      GpuFormat.RGBA8_UNORM,
                      pixels.width(),
                      pixels.height(),
                      1,
                      1),
              GpuTexture::close);
      var view = owned(0, () -> device.createTextureView(texture), GpuTextureView::close);
      var image =
          owned(
              pixels.bytes(),
              () -> new NativeImage(pixels.width(), pixels.height(), false),
              NativeImage::close);
      uploadResources.add(resources.getLast());
      for (int y = 0; y < pixels.height(); y++)
        for (int x = 0; x < pixels.width(); x++) image.setPixel(x, y, pixels.pixel(x, y));
      encoder.writeToTexture(texture, image);
      textures.put(entry.getKey(), view);
    }
    encoder.submit();
    var pipelines = new LinkedHashMap<Policy, CompiledRenderPipeline>();
    for (var entry : scene.materials().entrySet()) {
      var material = entry.getValue();
      var policy = new Policy(material.alpha(), material.cull());
      var pipeline = pipelines.get(policy);
      if (pipeline == null) {
        pipeline = compile(policy);
        pipelines.put(policy, pipeline);
      }
      var filter =
          material.filter() == GraphicMaterial.Filter.NEAREST
              ? FilterMode.NEAREST
              : FilterMode.LINEAR;
      var wrap =
          material.wrap() == GraphicMaterial.Wrap.CLAMP
              ? AddressMode.CLAMP_TO_EDGE
              : AddressMode.REPEAT;
      var sampler =
          owned(
              0,
              () -> device.createSampler(wrap, wrap, filter, filter, 1, OptionalDouble.empty()),
              GpuSampler::close);
      var parameters = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
      parameters
          .putFloat(material.alpha() == GraphicMaterial.Alpha.CUTOUT ? material.cutoff() : 0)
          .putFloat(material.alpha() != GraphicMaterial.Alpha.TRANSLUCENT ? 1 : 0)
          .putLong(0)
          .flip();
      var buffer =
          owned(
              16,
              () -> nativeBuffer(parameters, GpuBuffer.USAGE_UNIFORM, "re:form graphic material"),
              GpuBuffer::close);
      bindings.put(
          entry.getKey(), new Binding(textures.get(material.texture()), sampler, buffer, pipeline));
    }
    outputSampler =
        owned(
            0,
            () ->
                device.createSampler(
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    1,
                    OptionalDouble.empty()),
            GpuSampler::close);
  }

  private CompiledRenderPipeline compile(Policy policy) {
    var id =
        Identifier.parse(
            "reform:graphic/"
                + policy.alpha().name().toLowerCase(java.util.Locale.ROOT)
                + "_"
                + policy.cull().name().toLowerCase(java.util.Locale.ROOT));
    var pipeline =
        RenderPipeline.builder()
            .withLocation(id)
            .withVertexShader("reform:graphic_mesh")
            .withFragmentShader("reform:graphic_mesh")
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("Material", UniformType.UNIFORM_BUFFER)
                    .build())
            .withCull(policy.cull() == GraphicMaterial.Cull.BACK)
            .withDepthStencilState(
                new DepthStencilState(
                    CompareOp.LESS_THAN_OR_EQUAL,
                    policy.alpha() != GraphicMaterial.Alpha.TRANSLUCENT))
            .withColorTargetState(
                new ColorTargetState(
                    policy.alpha() == GraphicMaterial.Alpha.TRANSLUCENT
                        ? Optional.of(BlendFunction.TRANSLUCENT)
                        : Optional.empty(),
                    GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_ALL))
            .build();
    // This is an explicit native preparation stage. The selected compiler executes its CPU work
    // through the supplied direct executor; no worker result is awaited by ordinary rendering.
    return owned(
        0,
        () -> {
          var pending =
              RenderSystem.getDevice().compilePipeline(pipeline, SHADERS, Runnable::run).join();
          if (pending == CompiledRenderPipeline.Pending.NULL)
            throw new PolicyRefusal(
                "preview.prepare_failed", "The native graphic pipeline could not be compiled");
          return pending.finishCompile();
        },
        CompiledRenderPipeline::close);
  }

  private GpuBuffer nativeBuffer(ByteBuffer source, int usage, String label) {
    var allocation = ledger.reserve(source.remaining(), 1);
    ByteBuffer direct = null;
    try {
      direct = MemoryUtil.memAlloc(source.remaining());
      direct.put(source).flip();
      return RenderSystem.getDevice().createBuffer(() -> label, usage, direct);
    } finally {
      if (direct != null) MemoryUtil.memFree(direct);
      allocation.close();
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
      throw new PolicyRefusal("preview.closed", "This graphic is closed to native submission");
    if (scene.mesh().isEmpty()) return ClientMeshGraphic.DrawOutcome.EMPTY;
    if (width < 1 || height < 1) return ClientMeshGraphic.DrawOutcome.WAITING;
    var failure = NativeMeshGraphicRuntime.failure(lifetime);
    if (failure.isPresent()) {
      var refusal =
          new PolicyRefusal(
              "preview.prepare_failed", "Native graphic retirement could not be confirmed");
      refusal.initCause(failure.orElseThrow());
      throw refusal;
    }
    double scale = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
    long w = Math.round(width * scale), h = Math.round(height * scale);
    if (w < 1
        || h < 1
        || w > Integer.MAX_VALUE
        || h > Integer.MAX_VALUE
        || w > ledger.limits().nativeBytes() / 8 / h)
      throw new PolicyRefusal("preview.budget", "The native viewport exceeds its image allowance");
    boolean changed = frameWidth != w || frameHeight != h || !camera.equals(renderedCamera);
    if (changed && lifetime.state().outstandingUses() > 0)
      return ClientMeshGraphic.DrawOutcome.WAITING;
    try {
      if (changed) {
        var ticket = NativeMeshGraphicRuntime.acquire(lifetime);
        try {
          if (frameWidth != w || frameHeight != h) frame((int) w, (int) h);
          render(camera);
          renderedCamera = camera;
        } finally {
          NativeMeshGraphicRuntime.submitted(ticket, () -> {});
        }
      }
      if (lifetime.state().outstandingUses() >= maximumUses)
        return ClientMeshGraphic.DrawOutcome.WAITING;
      var ticket = NativeMeshGraphicRuntime.acquire(lifetime);
      var graphics = (GraphicCanvasAccessor) canvas.graphics();
      NativeMeshGraphicRuntime.extracted(graphics.reform$graphicState(), ticket);
      canvas.enableScissor(x, y, x + width, y + height);
      try {
        graphics.reform$blitGraphic(
            RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
            colorView,
            outputSampler,
            x,
            y,
            x + width,
            y + height,
            0,
            1,
            1,
            0,
            -1);
      } finally {
        canvas.disableScissor();
      }
      return ClientMeshGraphic.DrawOutcome.RENDERED;
    } catch (RuntimeException | Error failed) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  private void frame(int width, int height) {
    close(frameResources);
    frameResources.clear();
    resources.removeIf(resource -> resource.released);
    var device = RenderSystem.getDevice();
    long bytes = (long) width * height * 4;
    color =
        owned(
            bytes,
            () ->
                device.createTexture(
                    "re:form private graphic frame",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA8_UNORM,
                    width,
                    height,
                    1,
                    1),
            GpuTexture::close);
    frameResources.add(resources.getLast());
    colorView = owned(0, () -> device.createTextureView(color), GpuTextureView::close);
    frameResources.add(resources.getLast());
    depth =
        owned(
            bytes,
            () ->
                device.createTexture(
                    "re:form private graphic depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT,
                    GpuFormat.D32_FLOAT,
                    width,
                    height,
                    1,
                    1),
            GpuTexture::close);
    frameResources.add(resources.getLast());
    depthView = owned(0, () -> device.createTextureView(depth), GpuTextureView::close);
    frameResources.add(resources.getLast());
    frameWidth = width;
    frameHeight = height;
  }

  private void render(GraphicCamera camera) {
    var data =
        GraphicProjection.project(
            scene, camera, (double) frameWidth / frameHeight, ledger.limits());
    var encoder = RenderSystem.getDevice().createCommandEncoder();
    var source = data.vertices();
    var allocation = ledger.reserve(source.remaining(), 1);
    ByteBuffer direct = null;
    try {
      direct = MemoryUtil.memAlloc(source.remaining());
      direct.put(source).flip();
      encoder.writeToBuffer(vertices.slice(0, direct.remaining()), direct);
    } finally {
      if (direct != null) MemoryUtil.memFree(direct);
      allocation.close();
    }
    try (var pass =
        encoder.createRenderPass(
            () -> "re:form private graphic",
            colorView,
            Optional.of(GuiRenderer.CLEAR_COLOR),
            depthView,
            OptionalDouble.of(1))) {
      pass.setVertexBuffer(0, vertices.slice());
      for (var draw : data.draws()) {
        var binding = bindings.get(draw.material());
        pass.setPipeline(binding.pipeline());
        pass.setUniform("Sampler0", binding.texture(), binding.sampler());
        pass.setUniform("Material", binding.parameters());
        pass.draw(draw.vertexCount(), 1, draw.firstVertex(), 0);
      }
    }
    encoder.submit();
  }

  private static void close(List<Owned> selected) {
    Throwable failure = null;
    for (var resource : selected.reversed())
      try {
        resource.close();
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
    uploadResources.clear();
    bindings.clear();
    NativeMeshGraphicRuntime.released(lifetime);
    NativeMeshGraphicRuntime.released(this);
  }

  @Override
  public void close() {
    lifetime.close();
  }
}
