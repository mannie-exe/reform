package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import java.util.Objects;
import net.minecraft.network.chat.Component;

/** Fixed workflow identity; ordinary navigation does not replace its title or subtitle. */
public record WorkspaceIdentity(DocumentId id, Component title, Component subtitle) {
  public WorkspaceIdentity(DocumentId id, Component title) {
    this(id, title, Component.empty());
  }

  public WorkspaceIdentity {
    Objects.requireNonNull(id);
    title = Objects.requireNonNull(title).copy();
    subtitle = Objects.requireNonNull(subtitle).copy();
    if (title.getString().isBlank()
        || title.getString().length() > 1024
        || subtitle.getString().length() > 1024)
      throw new IllegalArgumentException("Workspace identity needs bounded readable captions");
  }

  @Override
  public Component title() {
    return title.copy();
  }

  @Override
  public Component subtitle() {
    return subtitle.copy();
  }
}
