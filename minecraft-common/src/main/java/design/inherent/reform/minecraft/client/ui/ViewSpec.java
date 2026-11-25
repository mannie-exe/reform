package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.minecraft.client.ClientFieldParts;
import design.inherent.reform.presentation.ui.UiCommand;
import design.inherent.reform.presentation.ui.UiRead;
import design.inherent.reform.presentation.ui.UiScope;
import design.inherent.reform.value.ValueType;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

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

  /** A capability-bound part of one actual field, not an arbitrary native widget. */
  record FieldPart(String key, ClientFieldParts<?> field, ClientFieldParts.Role role)
      implements ViewSpec {
    public FieldPart {
      key = identity(key);
      Objects.requireNonNull(field);
      Objects.requireNonNull(role);
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

  @FunctionalInterface
  interface RowFactory<I> {
    ViewSpec build(UiScope scope, String key, UiRead<I> item, UiRead<Integer> index);
  }

  /** Keyed values, not positional widget recycling. Row reads always describe the current item. */
  record ForEach<I>(
      String key,
      UiRead<List<I>> items,
      ValueType<I> itemType,
      int maximumRows,
      Function<I, String> itemKey,
      RowFactory<I> row)
      implements ViewSpec {
    public ForEach {
      key = identity(key);
      Objects.requireNonNull(items);
      Objects.requireNonNull(itemType);
      Objects.requireNonNull(itemKey);
      Objects.requireNonNull(row);
      if (maximumRows < 1)
        throw new IllegalArgumentException("A finite positive row allowance is required");
    }
  }

  @FunctionalInterface
  interface SlotFactory {
    ViewSpec build(UiScope scope, int slot);
  }

  /** Fixed positional decoration, not identity for editable domain rows. */
  record Repeat(String key, UiRead<Integer> count, int from, int maximumSlots, SlotFactory row)
      implements ViewSpec {
    public Repeat {
      key = identity(key);
      Objects.requireNonNull(count);
      Objects.requireNonNull(row);
      if (maximumSlots < 1)
        throw new IllegalArgumentException("A positive slot allowance is required");
      Math.addExact(from, maximumSlots - 1);
    }
  }

  record Case(String key, UiRead<Boolean> test, Branch content) {
    public Case {
      key = identity(key);
      Objects.requireNonNull(test);
      Objects.requireNonNull(content);
    }
  }

  record Switch(String key, List<Case> cases, Branch fallback, boolean exclusive)
      implements ViewSpec {
    public Switch {
      key = identity(key);
      cases = List.copyOf(cases);
      Objects.requireNonNull(fallback);
      var keys = new HashSet<String>();
      for (var entry : cases)
        if (!keys.add(entry.key())) throw new IllegalArgumentException("Duplicate switch case key");
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
