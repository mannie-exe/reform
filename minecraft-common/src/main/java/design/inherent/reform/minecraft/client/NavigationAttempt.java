package design.inherent.reform.minecraft.client;

import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** An identified client-thread reveal attempt, not an editing operation or permission token. */
public final class NavigationAttempt {
  public enum Outcome {
    REVEALED,
    HANDED_OFF,
    UNAVAILABLE,
    MISSING,
    REFUSED,
    SUPERSEDED,
    CLOSED,
    FAILED
  }

  public record Result(
      UUID id,
      ClientDestination target,
      Outcome outcome,
      ValidationReport report,
      Optional<Throwable> cause) {
    public Result {
      Objects.requireNonNull(id);
      Objects.requireNonNull(target);
      Objects.requireNonNull(outcome);
      Objects.requireNonNull(report);
      Objects.requireNonNull(cause);
    }
  }

  private final UUID id = UUID.randomUUID();
  private final ClientDestination target;
  private final CompletableFuture<Result> completion = new CompletableFuture<>();

  NavigationAttempt(ClientDestination target) {
    this.target = Objects.requireNonNull(target);
  }

  public UUID id() {
    return id;
  }

  public ClientDestination target() {
    return target;
  }

  public CompletionStage<Result> completion() {
    return completion.minimalCompletionStage();
  }

  public Optional<Result> result() {
    return Optional.ofNullable(completion.getNow(null));
  }

  void finish(Outcome outcome, ValidationReport report, Throwable cause) {
    completion.complete(new Result(id, target, outcome, report, Optional.ofNullable(cause)));
  }
}
