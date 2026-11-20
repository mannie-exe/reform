package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Explicit document presentation/opening capability; metadata alone creates no session or source.
 */
public final class ClientForm {
  public record Availability(boolean ready, boolean canRetry, Component message) {
    public Availability {
      message = Objects.requireNonNull(message).copy();
    }

    @Override
    public Component message() {
      return message.copy();
    }
  }

  private final DocumentId document;
  private final DocumentId route;
  private final Component title;
  private final Supplier<Availability> availability;
  private final Function<Screen, ? extends Screen> opener;
  private final Optional<Runnable> recovery;

  private ClientForm(
      DocumentId document,
      DocumentId route,
      Component title,
      Supplier<Availability> availability,
      Function<Screen, ? extends Screen> opener,
      Optional<Runnable> recovery) {
    this.document = Objects.requireNonNull(document);
    this.route = Objects.requireNonNull(route);
    this.title = Objects.requireNonNull(title).copy();
    this.availability = Objects.requireNonNull(availability);
    this.opener = Objects.requireNonNull(opener);
    this.recovery = Objects.requireNonNull(recovery);
  }

  /** Advanced views still return an actual shared form, whose real document identity is checked. */
  public static ClientForm of(
      DocumentId document,
      Component title,
      Supplier<Availability> availability,
      Function<Screen, ? extends Screen> opener,
      Optional<Runnable> recovery) {
    return new ClientForm(document, document, title, availability, opener, recovery);
  }

  static ClientForm bound(
      DocumentId document,
      Component title,
      Supplier<ClientStatusScreen.State> availability,
      Function<Screen, ? extends Screen> opener,
      Optional<Runnable> recovery) {
    return of(
        document,
        title,
        () -> {
          var state = availability.get();
          return new Availability(state.ready(), state.canRetry(), state.message());
        },
        opener,
        recovery);
  }

  public DocumentId document() {
    return document;
  }

  public DocumentId route() {
    return route;
  }

  /** A navigation alias leaves the checked owner identity and actual source unchanged. */
  public ClientForm atRoute(DocumentId route) {
    return new ClientForm(document, route, title, availability, opener, recovery);
  }

  public Component title() {
    return title.copy();
  }

  public Availability availability() {
    ClientSettingsRuntime.requireClientThread();
    return Objects.requireNonNull(availability.get());
  }

  public boolean canRetry() {
    return recovery.isPresent() && availability().canRetry();
  }

  public void retry() {
    if (!canRetry())
      throw new IllegalStateException("This document has no current binding recovery");
    recovery.orElseThrow().run();
  }

  /**
   * Transfers one actual editor to the caller; repeated opens are independent editing workflows.
   */
  public ClientEditor<?> open(Screen parent) {
    ClientSettingsRuntime.requireClientThread();
    var state = availability();
    if (!state.ready())
      throw new PolicyRefusal("client.document_unavailable", state.message().getString());
    var result = Objects.requireNonNull(opener.apply(parent));
    if (!(result instanceof SettingsScreen<?> screen))
      throw new PolicyRefusal(
          "client.document_unavailable", "The document is not ready for an editing view");
    if (!screen.document().equals(document)) {
      screen.editingActions().close();
      throw new IllegalArgumentException("The form returned another document's editor");
    }
    return ClientEditor.retain(screen);
  }
}
