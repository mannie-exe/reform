package design.inherent.reform.minecraft.client;

/** Internal native adapter capability; only explicitly bound controls have a theme. */
public interface NativeControlStyle {
  ClientTheme reform$theme();

  void reform$theme(ClientTheme theme);

  boolean reform$inputArgb();

  void reform$inputArgb(boolean explicit);
}
