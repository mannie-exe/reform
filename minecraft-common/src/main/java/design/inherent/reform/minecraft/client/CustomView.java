package design.inherent.reform.minecraft.client;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Retained custom contribution using the shared panel, semantic theme, and native callbacks. */
final class CustomView {
  private final ClientViewContext context;
  private final Supplier<ClientViewContent> source;
  private ClientViewContent content;
  private RuntimeException failure;
  private Runnable rebuild;
  private Object generation;

  CustomView(ClientViewContext context, Supplier<ClientViewContent> source) {
    this.context = context;
    this.source = source;
    content = Objects.requireNonNull(source.get()); // Reject failed admission before caching.
  }

  void refresh() {
    context.requireCurrent();
    try {
      content = Objects.requireNonNull(source.get());
      failure = null;
    } catch (RuntimeException failed) {
      failure = failed;
    }
  }

  void hide() {
    generation = null;
  }

  void append(FormPanel panel, ClientTheme theme, ClientNavigation navigation, Runnable rebuild) {
    context.requireCurrent();
    this.rebuild = Objects.requireNonNull(rebuild);
    var token = new Object();
    generation = token;
    if (failure != null)
      panel.row(
          "custom:projection-failed",
          Component.translatableWithFallback(
              "reform.view.refresh_failed", "This view could not refresh"),
          Component.literal(
              Objects.toString(failure.getMessage(), failure.getClass().getSimpleName())));
    append(panel, theme, navigation, content.entries(), token);
  }

  private Runnable guarded(Object token, Runnable effect) {
    return () -> {
      if (!context.isOpen() || generation != token) return;
      context.requireCurrent();
      try {
        effect.run();
      } catch (RuntimeException failed) {
        failure = failed;
        if (context.isOpen() && generation == token) rebuild.run();
      }
    };
  }

  private void append(
      FormPanel panel,
      ClientTheme theme,
      ClientNavigation navigation,
      List<ClientViewContent.Entry> entries,
      Object token) {
    for (var entry : entries) {
      String id = "custom:" + entry.id();
      switch (entry) {
        case ClientViewContent.Text text -> panel.anchorRow(id, text.title(), text.body());
        case ClientViewContent.Group group -> {
          panel.beginGroup(id, group.title());
          if (!group.help().getString().isBlank())
            panel.row(id + ":help", Component.empty(), group.help());
          append(panel, theme, navigation, group.entries(), token);
          panel.endGroup();
        }
        case ClientViewContent.Action action ->
            panel.row(
                id,
                Component.empty(),
                action.help(),
                ClientControls.action(
                        Minecraft.getInstance().font,
                        action.label(),
                        guarded(token, action.invoke()),
                        theme)
                    .build());
        case ClientViewContent.Link link -> {
          var button =
              ClientControls.action(
                      Minecraft.getInstance().font,
                      link.label(),
                      guarded(token, () -> navigation.open(link.target())),
                      theme)
                  .build();
          var availability = navigation.inspect(link.target());
          button.active =
              availability.kind() == DestinationAvailability.Kind.READY
                  || availability.kind() == DestinationAvailability.Kind.UNOPENED
                  || availability.kind() == DestinationAvailability.Kind.PENDING;
          panel.row(id, Component.empty(), link.help(), button);
        }
      }
    }
  }
}
