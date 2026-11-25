package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.presentation.ui.UiCommand;
import design.inherent.reform.presentation.ui.UiRead;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Standard descriptions; the native host supplies scope, layout, input, and retirement. */
public final class Views {
  public static ViewSpec text(String key, UiText title, UiText body) {
    return new ViewSpec.Text(key, title, body);
  }

  public static ViewSpec text(String key, Component title, Component body) {
    return text(key, UiText.of(title), UiText.of(body));
  }

  public static ViewSpec action(String key, UiText label, UiText help, UiCommand<Void> command) {
    return new ViewSpec.Action(key, label, help, command);
  }

  public static ViewSpec column(String key, List<ViewSpec> children) {
    return new ViewSpec.Column(key, children);
  }

  public static ViewSpec section(String key, UiText title, List<ViewSpec> children) {
    return new ViewSpec.Section(key, title, children);
  }

  public static ViewSpec show(
      String key, UiRead<Boolean> test, ViewSpec.Branch yes, ViewSpec.Branch no) {
    return new ViewSpec.Show(key, test, yes, no);
  }

  public static <P> ViewSpec component(String key, UiComponent<P> factory, P props) {
    return new ViewSpec.Component<>(key, factory, props);
  }

  private Views() {}
}
