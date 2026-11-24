package design.inherent.reform.minecraft.client;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Save actual GL state and restore it exactly without changing Minecraft's tracked state. */
final class NativeGraphicGlState implements AutoCloseable {
  private static final int[] CAPABILITIES = {
    GL11.GL_BLEND,
    GL11.GL_CULL_FACE,
    GL11.GL_DEPTH_TEST,
    GL11.GL_SCISSOR_TEST,
    GL11.GL_STENCIL_TEST,
    GL11.GL_COLOR_LOGIC_OP,
    GL11.GL_POLYGON_OFFSET_FILL,
    GL30.GL_RASTERIZER_DISCARD
  };
  private final boolean[] enabled = new boolean[CAPABILITIES.length], colorMask = new boolean[4];
  private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
      vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
      buffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
  private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
      read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
      renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
  private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
      texture,
      unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING),
      unpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
  private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
      dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
      srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
      dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
      eqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),
      eqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
  private final int depth = GL11.glGetInteger(GL11.GL_DEPTH_FUNC),
      cull = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE),
      front = GL11.glGetInteger(GL11.GL_FRONT_FACE);
  private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
  private final int[] viewport = new int[4], polygon = new int[2], scissor = new int[4];
  private final float[] clear = new float[4];
  private final double[] range = new double[2], clearDepth = new double[1];
  private final int rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH),
      skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS),
      skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
  private final boolean swapBytes = GL11.glGetBoolean(GL11.GL_UNPACK_SWAP_BYTES);
  private final boolean[] clip = new boolean[GL11.glGetInteger(GL30.GL_MAX_CLIP_DISTANCES)];

  NativeGraphicGlState() {
    for (int i = 0; i < CAPABILITIES.length; i++) enabled[i] = GL11.glIsEnabled(CAPABILITIES[i]);
    for (int i = 0; i < clip.length; i++) clip[i] = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0 + i);
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
    GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
    GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, polygon);
    GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
    GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, range);
    GL11.glGetDoublev(GL11.GL_DEPTH_CLEAR_VALUE, clearDepth);
    try (var stack = MemoryStack.stackPush()) {
      var mask = stack.malloc(4);
      GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
      for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
    }
    GL13.glActiveTexture(GL13.GL_TEXTURE0);
    texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GL13.glActiveTexture(active);
  }

  static void ordinaryTriangles() {
    GL11.glDisable(GL11.GL_STENCIL_TEST);
    GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
    GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
    GL11.glColorMask(true, true, true, true);
    GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
    GL11.glFrontFace(GL11.GL_CCW);
    GL11.glCullFace(GL11.GL_BACK);
    GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
    GL13.glActiveTexture(GL13.GL_TEXTURE0);
    GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
    GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
    GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, 0);
    for (int i = 0; i < GL11.glGetInteger(GL30.GL_MAX_CLIP_DISTANCES); i++)
      GL11.glDisable(GL30.GL_CLIP_DISTANCE0 + i);
  }

  @Override
  public void close() {
    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
    GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
    GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    GL11.glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
    GL11.glClearColor(clear[0], clear[1], clear[2], clear[3]);
    GL11.glClearDepth(clearDepth[0]);
    GL11.glDepthRange(range[0], range[1]);
    GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
    GL11.glDepthMask(depthMask);
    GL11.glDepthFunc(depth);
    GL11.glCullFace(cull);
    GL11.glFrontFace(front);
    if (polygon[0] == polygon[1]) GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, polygon[0]);
    else {
      GL11.glPolygonMode(GL11.GL_FRONT, polygon[0]);
      GL11.glPolygonMode(GL11.GL_BACK, polygon[1]);
    }
    GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    GL20.glBlendEquationSeparate(eqRgb, eqAlpha);
    GL20.glUseProgram(program);
    GL30.glBindVertexArray(vao);
    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
    GL13.glActiveTexture(GL13.GL_TEXTURE0);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    GL13.glActiveTexture(active);
    GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpack);
    GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
    GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, swapBytes ? 1 : 0);
    for (int i = 0; i < clip.length; i++)
      if (clip[i]) GL11.glEnable(GL30.GL_CLIP_DISTANCE0 + i);
      else GL11.glDisable(GL30.GL_CLIP_DISTANCE0 + i);
    for (int i = 0; i < CAPABILITIES.length; i++)
      if (enabled[i]) GL11.glEnable(CAPABILITIES[i]);
      else GL11.glDisable(CAPABILITIES[i]);
  }

  /** Native game renderers update Minecraft's cache; restore it as well as actual GL state. */
  void closeTracked() {
    try {
      if (enabled[0]) com.mojang.blaze3d.systems.RenderSystem.enableBlend();
      else com.mojang.blaze3d.systems.RenderSystem.disableBlend();
      if (enabled[1]) com.mojang.blaze3d.systems.RenderSystem.enableCull();
      else com.mojang.blaze3d.systems.RenderSystem.disableCull();
      if (enabled[2]) com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
      else com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
      if (enabled[3])
        com.mojang.blaze3d.systems.RenderSystem.enableScissor(
            scissor[0], scissor[1], scissor[2], scissor[3]);
      else com.mojang.blaze3d.systems.RenderSystem.disableScissor();
      if (enabled[5]) com.mojang.blaze3d.systems.RenderSystem.enableColorLogicOp();
      else com.mojang.blaze3d.systems.RenderSystem.disableColorLogicOp();
      if (enabled[6]) com.mojang.blaze3d.systems.RenderSystem.enablePolygonOffset();
      else com.mojang.blaze3d.systems.RenderSystem.disablePolygonOffset();
      com.mojang.blaze3d.systems.RenderSystem.depthFunc(depth);
      com.mojang.blaze3d.systems.RenderSystem.depthMask(depthMask);
      com.mojang.blaze3d.systems.RenderSystem.colorMask(
          colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
      com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    } finally {
      close();
    }
  }
}
