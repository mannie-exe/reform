package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.DocumentConflicts;
import java.util.Optional;
import net.minecraft.client.gui.components.AbstractWidget;

/** Cached actual conflict comparison and current native choice controls; no new owner or writer. */
public interface ClientConflictInspection {
  Optional<DocumentConflicts.Preview> reviewedInput();

  Optional<AbstractWidget> choiceControl(FieldPath path);
}
