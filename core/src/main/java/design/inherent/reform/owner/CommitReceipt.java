package design.inherent.reform.owner;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.validation.ValidationReport;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persistence, observed application and remaining requirements are independent facts. */
public interface CommitReceipt<T> {
  enum Persistence {
    NOT_ATTEMPTED,
    UNCHANGED,
    SAVED,
    REFUSED,
    FAILED,
    UNKNOWN
  }

  enum Application {
    NOT_ATTEMPTED,
    APPLIED,
    STAGED,
    PARTIAL,
    REFUSED,
    UNAVAILABLE
  }

  enum RequirementKind {
    WORLD_RESTART,
    GAME_RESTART,
    NEW_WORLD,
    INCOMPATIBLE,
    OWNER_MANAGED
  }

  record Requirement(FieldPath path, RequirementKind kind, String explanation) {
    public Requirement {
      Objects.requireNonNull(path);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(explanation);
    }
  }

  Persistence persistence();

  Application application();

  Optional<String> savedRevision();

  Optional<? extends OwnerObservation<T>> observation();

  ValidationReport persistenceReport();

  ValidationReport applicationReport();

  default List<Requirement> requirements() {
    return List.of();
  }
}
