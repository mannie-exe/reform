package example.reform.platform;

import com.google.gson.JsonObject;

final class NativeConnectorProbe {
  static JsonObject verify() {
    throw new UnsupportedOperationException("Connector is a NeoForge integration");
  }

  private NativeConnectorProbe() {}
}
