package design.inherent.reform.minecraft.internal;

import net.minecraft.client.Minecraft;

/** Stateless native-thread guard; checking chat origin must not initialize the settings runtime. */
public final class NativeClientThread {
  public static Minecraft require() {
    var client = Minecraft.getInstance();
    if (client == null || !client.isSameThread())
      throw new IllegalStateException(
          "Client settings operations require the native client thread after startup; use onSetup for construction-time registration");
    return client;
  }

  private NativeClientThread() {}
}
