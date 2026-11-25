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
  private final MountedUiTree ui;
  private ClientViewContent content;
  private RuntimeException failure;
  private Runnable rebuild;
  private Object generation;
  private final java.util.function.BiFunction<
          design.inherent.reform.presentation.graphics.GraphicLimits, Integer, ClientGraphicHost>
      graphics;
  private final java.util.function.Consumer<FormPanel> focus;
  private final java.util.Map<String, LoadedGraphicView<?>> loaded =
      new java.util.LinkedHashMap<>();

  CustomView(
      ClientViewContext context,
      Supplier<ClientViewContent> source,
      java.util.function.BiFunction<
              design.inherent.reform.presentation.graphics.GraphicLimits,
              Integer,
              ClientGraphicHost>
          graphics,
      java.util.function.Consumer<FormPanel> focus) {
    this.context = context;
    this.source = source;
    ui = null;
    this.graphics = graphics;
    this.focus = focus;
    context.onClose(this::closeGraphics);
    var ticking =
        design.inherent.reform.minecraft.internal.ClientSettingsRuntime.onTick(this::tick);
    try {
      context.onClose(ticking::close);
    } catch (RuntimeException | Error failed) {
      ticking.close();
      throw failed;
    }
    content = Objects.requireNonNull(source.get()); // Reject failed admission before caching.
  }

  CustomView(
      ClientViewContext context, MountedUiTree ui, java.util.function.Consumer<FormPanel> focus) {
    this.context = context;
    this.ui = ui;
    source = null;
    graphics = null;
    this.focus = java.util.Objects.requireNonNull(focus);
    var ticking =
        design.inherent.reform.minecraft.internal.ClientSettingsRuntime.onTick(this::tick);
    try {
      context.onClose(ticking::close);
    } catch (RuntimeException | Error failed) {
      ticking.close();
      throw failed;
    }
  }

  void refresh() {
    context.requireCurrent();
    if (ui != null) {
      ui.refresh(true);
      return;
    }
    try {
      var next = Objects.requireNonNull(source.get());
      var declarations = new java.util.LinkedHashMap<String, ClientViewContent.LoadedGraphic<?>>();
      graphicDeclarations(next.entries(), declarations);
      Throwable cleanupFailure = null;
      for (var entry : java.util.List.copyOf(loaded.entrySet())) {
        var declaration = declarations.get(entry.getKey());
        if (declaration != null && entry.getValue().matches(declaration)) continue;
        loaded.remove(entry.getKey());
        try {
          entry.getValue().close();
        } catch (RuntimeException | Error failed) {
          if (cleanupFailure == null) cleanupFailure = failed;
          else cleanupFailure.addSuppressed(failed);
        }
      }
      if (cleanupFailure instanceof RuntimeException failed) throw failed;
      if (cleanupFailure instanceof Error failed) throw failed;
      content = next;
      failure = null;
    } catch (RuntimeException failed) {
      failure = failed;
    }
  }

  void hide() {
    generation = null;
    if (ui != null) ui.hide();
    for (var view : loaded.values()) view.hide();
  }

  void append(FormPanel panel, ClientTheme theme, ClientNavigation navigation, Runnable rebuild) {
    context.requireCurrent();
    this.rebuild = Objects.requireNonNull(rebuild);
    var token = new Object();
    generation = token;
    if (ui != null) {
      ui.refresh(false);
      ui.append(panel, theme, effect -> guarded(token, effect), rebuild, () -> focus.accept(panel));
      return;
    }
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
        case ClientViewContent.LoadedGraphic<?> graphic ->
            appendGraphic(panel, theme, graphic, token);
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

  private <S> void appendGraphic(
      FormPanel panel, ClientTheme theme, ClientViewContent.LoadedGraphic<S> spec, Object token) {
    @SuppressWarnings("unchecked")
    var view =
        (LoadedGraphicView<S>)
            loaded.computeIfAbsent(
                spec.id(),
                ignored ->
                    new LoadedGraphicView<>(
                        spec, graphics.apply(spec.aggregateLimits(), spec.maximumViews())));
    view.append(panel, theme, effect -> guarded(token, effect), rebuild);
  }

  private void tick() {
    if (!context.isOpen() || generation == null) return;
    if (ui != null) {
      if (ui.refresh(false)) rebuild.run();
      return;
    }
    for (var view : loaded.values()) {
      if (view.changed()) {
        rebuild.run();
        return;
      }
      view.focus(focus);
    }
  }

  private static void graphicDeclarations(
      List<ClientViewContent.Entry> entries,
      java.util.Map<String, ClientViewContent.LoadedGraphic<?>> result) {
    for (var entry : entries) {
      if (entry instanceof ClientViewContent.LoadedGraphic<?> graphic)
        result.put(graphic.id(), graphic);
      else if (entry instanceof ClientViewContent.Group group)
        graphicDeclarations(group.entries(), result);
    }
  }

  private void closeGraphics() {
    Throwable failed = null;
    for (var view : loaded.values()) {
      try {
        view.close();
      } catch (RuntimeException | Error cleanup) {
        if (failed == null) failed = cleanup;
        else failed.addSuppressed(cleanup);
      }
    }
    loaded.clear();
    if (failed instanceof RuntimeException cleanup) throw cleanup;
    if (failed instanceof Error cleanup) throw cleanup;
  }
}
