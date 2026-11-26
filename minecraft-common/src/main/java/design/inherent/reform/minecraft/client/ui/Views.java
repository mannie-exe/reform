package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.presentation.ui.UiBinding;
import design.inherent.reform.presentation.ui.UiCommand;
import design.inherent.reform.presentation.ui.UiRead;
import design.inherent.reform.value.ValueType;
import java.util.List;
import java.util.function.Function;
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

  public static ViewSpec row(
      String key,
      design.inherent.reform.presentation.UiLayout.Row layout,
      List<ViewSpec> children) {
    return new ViewSpec.Row(key, layout, children);
  }

  public static ViewSpec frame(
      String key, design.inherent.reform.presentation.UiLayout.Frame layout, ViewSpec content) {
    return new ViewSpec.Frame(key, layout, content);
  }

  public static ViewSpec actions(String key, List<ViewSpec> children) {
    return new ViewSpec.Actions(key, children);
  }

  public static ViewSpec section(String key, UiText title, List<ViewSpec> children) {
    return new ViewSpec.Section(key, title, children);
  }

  public static ViewSpec disclosure(
      String key, UiBinding<Boolean> expanded, DisclosureParts parts) {
    return new ViewSpec.Disclosure(key, expanded, parts);
  }

  public static ViewSpec show(
      String key, UiRead<Boolean> test, ViewSpec.Branch yes, ViewSpec.Branch no) {
    return new ViewSpec.Show(key, test, yes, no);
  }

  public static <P> ViewSpec component(String key, UiComponent<P> factory, P props) {
    return new ViewSpec.Component<>(key, factory, props);
  }

  public static <I> ViewSpec forEach(
      String key,
      UiRead<List<I>> items,
      ValueType<I> itemType,
      int maximumRows,
      Function<I, String> itemKey,
      ViewSpec.RowFactory<I> row) {
    return new ViewSpec.ForEach<>(key, items, itemType, maximumRows, itemKey, row);
  }

  public static ViewSpec repeat(
      String key, UiRead<Integer> count, int from, int maximumSlots, ViewSpec.SlotFactory row) {
    return new ViewSpec.Repeat(key, count, from, maximumSlots, row);
  }

  public static ViewSpec switchOn(
      String key, List<ViewSpec.Case> cases, ViewSpec.Branch fallback, boolean exclusive) {
    return new ViewSpec.Switch(key, cases, fallback, exclusive);
  }

  public static ViewSpec choiceList(String key, ChoiceListParts parts) {
    return new ViewSpec.ChoiceList(key, parts);
  }

  public static ViewSpec queryInput(String key, QueryInputParts parts) {
    return new ViewSpec.QueryInput(key, parts);
  }

  public static ViewSpec toggle(String key, ToggleParts parts) {
    return new ViewSpec.Toggle(key, parts);
  }

  public static ViewSpec preview(
      String key, design.inherent.reform.minecraft.client.PreviewController<?, ?> controller) {
    return new ViewSpec.Preview(key, controller);
  }

  public static ViewSpec managed(String key, UiMountLifecycle lifecycle, ViewSpec content) {
    return new ViewSpec.Managed(key, lifecycle, content);
  }

  private Views() {}
}
