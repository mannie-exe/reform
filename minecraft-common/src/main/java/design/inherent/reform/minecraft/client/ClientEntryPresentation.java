package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** Optional field slots; required labels, controls, information, and feedback remain host-owned. */
public record ClientEntryPresentation(
    Component eyebrow, Optional<Leading> leading, Optional<ClientEntryGraphic.Factory> background) {
  public static final ClientEntryPresentation EMPTY =
      new ClientEntryPresentation(Component.empty(), Optional.empty(), Optional.empty());

  /** One bounded intrinsic graphic, with readable alternative text beside the field purpose. */
  public record Leading(
      FormGeometry.Size preferred, Component description, ClientEntryGraphic.Factory factory) {
    public Leading {
      Objects.requireNonNull(preferred);
      description = Objects.requireNonNull(description).copy();
      Objects.requireNonNull(factory);
      if (preferred.width() < 1
          || preferred.height() < 1
          || preferred.width() > 256
          || preferred.height() > 256
          || description.getString().isBlank()
          || description.getString().length() > 1024)
        throw new IllegalArgumentException(
            "Field artwork needs a bounded offer and alternative text");
    }

    @Override
    public Component description() {
      return description.copy();
    }
  }

  public ClientEntryPresentation {
    eyebrow = Objects.requireNonNull(eyebrow).copy();
    Objects.requireNonNull(leading);
    Objects.requireNonNull(background);
    if (eyebrow.getString().length() > 256)
      throw new IllegalArgumentException("A field eyebrow must be bounded");
  }

  @Override
  public Component eyebrow() {
    return eyebrow.copy();
  }

  public ClientEntryPresentation withEyebrow(Component value) {
    return new ClientEntryPresentation(value, leading, background);
  }

  public ClientEntryPresentation withLeading(Leading value) {
    return new ClientEntryPresentation(eyebrow, Optional.of(value), background);
  }

  public ClientEntryPresentation withBackground(ClientEntryGraphic.Factory factory) {
    return new ClientEntryPresentation(eyebrow, leading, Optional.of(factory));
  }
}
