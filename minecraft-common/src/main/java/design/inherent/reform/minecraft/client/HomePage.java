package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.DestinationId;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;

/** A landing body, never a header preset or an instruction to open its featured owners. */
public record HomePage(
    DestinationId id, Component title, Component introduction, List<DestinationId> featured) {
  public HomePage {
    Objects.requireNonNull(id);
    title = Objects.requireNonNull(title).copy();
    introduction = Objects.requireNonNull(introduction).copy();
    featured = List.copyOf(featured);
    if (title.getString().isBlank()
        || title.getString().length() > 1024
        || introduction.getString().length() > 4096
        || featured.size() > 512
        || featured.stream().distinct().count() != featured.size())
      throw new IllegalArgumentException("Home requires bounded captions and unique destinations");
  }

  public static HomePage standard() {
    return new HomePage(
        DestinationId.parse("reform:home"),
        Component.translatableWithFallback("reform.workspace.home", "Home"),
        Component.translatableWithFallback(
            "reform.workspace.home_help",
            "Choose a destination. Your open drafts remain when you return here."),
        List.of());
  }

  @Override
  public Component title() {
    return title.copy();
  }

  @Override
  public Component introduction() {
    return introduction.copy();
  }
}
