package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.RecipeLinkPolicy;
import design.inherent.reform.presentation.RecipeLinkRequest;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.validation.ValidationReport;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** Optional client adapters. Shared entry points never resolve a viewer's classes. */
public final class ClientRecipeLinks {
  private ClientRecipeLinks() {}

  /**
   * Actual world/connection and resource generation, without retaining native owners or granting
   * authority.
   */
  public record Context(UUID registry, ReferenceStamp resources, boolean resourcesReady) {
    public Context {
      Objects.requireNonNull(registry);
      Objects.requireNonNull(resources);
    }
  }

  public static Context context() {
    ClientSettingsRuntime.requireClientThread();
    var resources = LoadedGraphicResources.snapshot();
    return new Context(NativeItemContexts.registry(), resources.stamp(), resources.ready());
  }

  /** Explicit real native container entry; this never manufactures an inventory or world. */
  public static NavigationAttempt openContainer(
      AbstractContainerScreen<?> origin, ClientDestination.Recipe destination) {
    ClientSettingsRuntime.requireClientThread();
    Objects.requireNonNull(origin);
    Objects.requireNonNull(destination);
    var attempt = new NavigationAttempt(destination);
    var client = Minecraft.getInstance();
    try {
      if (NativeScreens.current(client) != origin
          || client.player == null
          || client.level == null
          || client.getConnection() == null
          || client.player.containerMenu != origin.getMenu())
        throw refusal(
            "recipe.container_context",
            "Open this link from its current native inventory or crafting screen.");
      var before = context();
      var prepared = prepare(destination, origin);
      if (NativeScreens.current(client) != origin
          || client.player == null
          || client.player.containerMenu != origin.getMenu()
          || !before.equals(context()))
        throw refusal(
            "recipe.container_changed",
            "The native screen or its resources changed during lookup. Open the link again from the current screen.");
      try {
        prepared.open();
        if (NativeScreens.current(client) == origin || !prepared.hasParent(origin))
          throw refusal(
              "recipe.return_unavailable",
              "This viewer did not preserve the native screen's return path.");
      } catch (RuntimeException | Error failure) {
        if (client.player != null
            && client.player.containerMenu == origin.getMenu()
            && before.equals(context())) NativeScreens.open(client, origin);
        throw failure;
      }
      attempt.finish(NavigationAttempt.Outcome.HANDED_OFF, ValidationReport.VALID, null);
    } catch (RuntimeException failure) {
      attempt.finish(
          failure instanceof PolicyRefusal
              ? NavigationAttempt.Outcome.REFUSED
              : NavigationAttempt.Outcome.FAILED,
          failure instanceof PolicyRefusal refused
              ? refused.report()
              : refusal(
                      "recipe.open_failed",
                      "The recipe viewer could not open. Return to the native screen and try again.")
                  .report(),
          failure);
    }
    return attempt;
  }

  /** Implemented only by explicitly admitted optional client integrations. */
  public interface Adapter {
    Set<RecipeLinkRequest.Kind> kinds();

    /** Cached context readiness only: no recipe enumeration, IO, or screen creation. */
    Optional<PolicyRefusal> unavailable(Screen origin);

    /** Explicit activation lookup. It must neither modify recipes nor enter gameplay effects. */
    Prepared prepare(RecipeLinkRequest request, Screen origin);
  }

  public interface Prepared {
    /** Open once through the public viewer API, preserving the supplied native origin. */
    void open();

    /** Verify the viewer's actual public parent contract after opening. */
    boolean hasParent(Screen origin);
  }

  public record Availability(
      RecipeLinkPolicy.Provider provider,
      Set<RecipeLinkRequest.Kind> kinds,
      ValidationReport report) {
    public Availability {
      Objects.requireNonNull(provider);
      kinds = Set.copyOf(kinds);
      Objects.requireNonNull(report);
    }

    public boolean ready() {
      return report.valid();
    }
  }

  public record Resolution(
      Optional<RecipeLinkPolicy.Provider> selected,
      ValidationReport report,
      List<Availability> alternatives) {
    public Resolution {
      Objects.requireNonNull(selected);
      Objects.requireNonNull(report);
      alternatives = List.copyOf(alternatives);
    }
  }

  private static final EnumMap<RecipeLinkPolicy.Provider, Adapter> adapters =
      new EnumMap<>(RecipeLinkPolicy.Provider.class);

  /** A stale runtime's close cannot remove a newer runtime registration. */
  public static AutoCloseable register(RecipeLinkPolicy.Provider provider, Adapter adapter) {
    ClientSettingsRuntime.requireClientThread();
    adapters.put(Objects.requireNonNull(provider), Objects.requireNonNull(adapter));
    return () -> {
      ClientSettingsRuntime.requireClientThread();
      adapters.remove(provider, adapter);
    };
  }

  public static Resolution inspect(ClientDestination.Recipe destination) {
    ClientSettingsRuntime.requireClientThread();
    var origin = NativeScreens.current(Minecraft.getInstance());
    var statuses =
        destination.policy().admitted().stream()
            .sorted()
            .map(provider -> inspect(provider, destination.request(), origin))
            .toList();
    var chosen =
        destination.policy().candidates().stream()
            .filter(
                provider ->
                    statuses.stream()
                        .anyMatch(status -> status.provider() == provider && status.ready()))
            .findFirst();
    var report =
        chosen.isPresent()
            ? ValidationReport.VALID
            : destination
                .policy()
                .preferred()
                .map(
                    provider ->
                        statuses.stream()
                            .filter(status -> status.provider() == provider)
                            .findFirst()
                            .orElseThrow()
                            .report())
                .orElseGet(
                    () ->
                        refusal(
                                "recipe.viewer_unavailable",
                                "No recipe viewer is ready. Install a compatible viewer or return to editing.")
                            .report());
    return new Resolution(
        chosen,
        report,
        statuses.stream()
            .filter(status -> chosen.isEmpty() || status.provider() != chosen.orElseThrow())
            .toList());
  }

  private static Availability inspect(
      RecipeLinkPolicy.Provider provider, RecipeLinkRequest request, Screen origin) {
    var adapter = adapters.get(provider);
    if (adapter == null)
      return new Availability(
          provider,
          Set.of(),
          refusal("recipe.viewer_absent", provider + " is not available in this client.").report());
    var kinds = Set.copyOf(adapter.kinds());
    var reason =
        !kinds.contains(request.kind())
            ? Optional.of(
                refusal(
                    "recipe.request_unsupported", provider + " does not support this recipe link."))
            : adapter.unavailable(origin);
    return new Availability(
        provider, kinds, reason.map(PolicyRefusal::report).orElse(ValidationReport.VALID));
  }

  static Prepared prepare(ClientDestination.Recipe destination, Screen origin) {
    var resolution = inspect(destination);
    if (resolution.selected().isEmpty()) throw new PolicyRefusal(resolution.report());
    return Objects.requireNonNull(
        adapters.get(resolution.selected().orElseThrow()).prepare(destination.request(), origin));
  }

  private static PolicyRefusal refusal(String code, String message) {
    return new PolicyRefusal(code, message);
  }
}
