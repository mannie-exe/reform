package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.RecipeLinkPolicy;
import design.inherent.reform.presentation.RecipeLinkRequest;
import design.inherent.reform.validation.ValidationReport;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Optional client adapters. Shared entry points never resolve a viewer's classes. */
public final class ClientRecipeLinks {
  private ClientRecipeLinks() {}

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
      return report.issues().isEmpty();
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
                                "No admitted recipe viewer is ready. Install a compatible viewer or return to editing.")
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
