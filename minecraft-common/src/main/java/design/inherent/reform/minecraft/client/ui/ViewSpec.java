package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.presentation.ui.UiCommand;
import design.inherent.reform.presentation.ui.UiRead;
import design.inherent.reform.presentation.ui.UiScope;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable component declarations. These carry no widget, active draft, or native resource. */
public sealed interface ViewSpec {
  String key();

  record Text(String key, UiText title, UiText body) implements ViewSpec {
    public Text {
      key = identity(key);
      Objects.requireNonNull(title);
      Objects.requireNonNull(body);
    }
  }

  record Action(String key, UiText label, UiText help, UiCommand<Void> command)
      implements ViewSpec {
    public Action {
      key = identity(key);
      Objects.requireNonNull(label);
      Objects.requireNonNull(help);
      Objects.requireNonNull(command);
    }
  }

  record Column(String key, List<ViewSpec> children) implements ViewSpec {
    public Column {
      key = identity(key);
      children = ordered(children);
    }
  }

  record Section(String key, UiText title, List<ViewSpec> children) implements ViewSpec {
    public Section {
      key = identity(key);
      Objects.requireNonNull(title);
      children = ordered(children);
    }
  }

  @FunctionalInterface
  interface Branch {
    ViewSpec build(UiScope scope);
  }

  record Show(String key, UiRead<Boolean> test, Branch yes, Branch no) implements ViewSpec {
    public Show {
      key = identity(key);
      Objects.requireNonNull(test);
      Objects.requireNonNull(yes);
      Objects.requireNonNull(no);
    }
  }

  record Component<P>(String key, UiComponent<P> factory, P props) implements ViewSpec {
    public Component {
      key = identity(key);
      Objects.requireNonNull(factory);
      Objects.requireNonNull(props);
    }
  }

  private static String identity(String key) {
    Objects.requireNonNull(key);
    if (key.isBlank() || key.length() > 256)
      throw new IllegalArgumentException("A bounded stable component key is required");
    return key;
  }

  private static List<ViewSpec> ordered(List<ViewSpec> children) {
    var result = List.copyOf(children);
    var keys = new HashSet<String>();
    for (var child : result)
      if (!keys.add(child.key()))
        throw new IllegalArgumentException("Duplicate sibling component key: " + child.key());
    return result;
  }
}
