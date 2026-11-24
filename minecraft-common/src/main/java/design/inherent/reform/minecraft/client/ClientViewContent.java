package design.inherent.reform.minecraft.client;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.network.chat.Component;

/** Cached custom body content, measured and mounted by the existing native form panel. */
public record ClientViewContent(List<Entry> entries) {
  public ClientViewContent {
    entries = List.copyOf(entries);
    admit(entries, new HashSet<>(), 0, new int[1]);
  }

  public sealed interface Entry permits Text, Action, Link, Group, LoadedGraphic {
    String id();
  }

  /** An explicit loaded-image slot; constructing content never prepares or opens a document. */
  public record LoadedGraphic<S>(
      String id,
      Component title,
      Component help,
      ClientLoadedGraphicProvider<S> provider,
      S subject,
      design.inherent.reform.presentation.graphics.GraphicLimits aggregateLimits,
      int maximumViews)
      implements Entry {
    public LoadedGraphic {
      id = identity(id);
      title = caption(title);
      help = caption(help);
      Objects.requireNonNull(provider);
      subject = provider.subjectType().freeze(Objects.requireNonNull(subject));
      Objects.requireNonNull(aggregateLimits);
      if (maximumViews < 1 || maximumViews > 16)
        throw new IllegalArgumentException("A workflow supports between 1 and 16 graphic views");
    }

    @Override
    public Component title() {
      return title.copy();
    }

    @Override
    public Component help() {
      return help.copy();
    }
  }

  public record Text(String id, Component title, Component body) implements Entry {
    public Text {
      id = identity(id);
      title = caption(title);
      body = caption(body);
    }

    @Override
    public Component title() {
      return title.copy();
    }

    @Override
    public Component body() {
      return body.copy();
    }
  }

  /** Callbacks must use their original owner guards; this contribution supplies no writer. */
  public record Action(String id, Component label, Component help, Runnable invoke)
      implements Entry {
    public Action {
      id = identity(id);
      label = caption(label);
      help = caption(help);
      Objects.requireNonNull(invoke);
    }

    @Override
    public Component label() {
      return label.copy();
    }

    @Override
    public Component help() {
      return help.copy();
    }
  }

  public record Link(String id, Component label, Component help, ClientDestination target)
      implements Entry {
    public Link {
      id = identity(id);
      label = caption(label);
      help = caption(help);
      Objects.requireNonNull(target);
    }

    @Override
    public Component label() {
      return label.copy();
    }

    @Override
    public Component help() {
      return help.copy();
    }
  }

  public record Group(String id, Component title, Component help, List<Entry> entries)
      implements Entry {
    public Group {
      id = identity(id);
      title = caption(title);
      help = caption(help);
      entries = List.copyOf(entries);
    }

    @Override
    public Component title() {
      return title.copy();
    }

    @Override
    public Component help() {
      return help.copy();
    }
  }

  private static String identity(String id) {
    Objects.requireNonNull(id);
    if (id.isBlank() || id.length() > 128)
      throw new IllegalArgumentException("A view row needs a bounded stable ID");
    return id;
  }

  private static Component caption(Component value) {
    Objects.requireNonNull(value);
    if (value.getString().length() > 4096)
      throw new IllegalArgumentException("View text exceeds its declared allowance");
    return value.copy();
  }

  private static void admit(List<Entry> entries, Set<String> ids, int depth, int[] count) {
    if (depth > 4) throw new IllegalArgumentException("View content exceeds four nested groups");
    for (var entry : entries) {
      Objects.requireNonNull(entry);
      if (++count[0] > 256 || !ids.add(entry.id()))
        throw new IllegalArgumentException("View content requires at most 256 unique row IDs");
      if (entry instanceof Group group) admit(group.entries(), ids, depth + 1, count);
    }
  }
}
