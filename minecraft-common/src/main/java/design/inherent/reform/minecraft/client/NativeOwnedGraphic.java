package design.inherent.reform.minecraft.client;

/** Native resources registered with the one existing renderer-lifetime coordinator. */
interface NativeOwnedGraphic {
  void close();

  default void contextDestroyed() {
    close();
  }
}
