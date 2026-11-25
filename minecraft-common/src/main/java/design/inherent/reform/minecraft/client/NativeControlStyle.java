package design.inherent.reform.minecraft.client;

/** Internal native adapter capability; only explicitly bound controls have a theme. */
public interface NativeControlStyle {
  /**
   * Owned per-widget metadata; unlike a global weak-map value, it cannot retain an abandoned key.
   */
  record ControlGeometry(java.util.List<net.minecraft.client.gui.components.AbstractWidget> tree) {
    public ControlGeometry {
      tree = java.util.List.copyOf(tree);
    }
  }

  ClientTheme reform$theme();

  void reform$theme(ClientTheme theme);

  boolean reform$inputArgb();

  void reform$inputArgb(boolean explicit);

  ControlGeometry reform$controlGeometry();

  void reform$controlGeometry(ControlGeometry geometry);

  RuntimeException reform$controlGeometryFailure();

  void reform$controlGeometryFailure(RuntimeException failure);

  Object reform$controlOwner();

  void reform$controlOwner(Object owner);
}
