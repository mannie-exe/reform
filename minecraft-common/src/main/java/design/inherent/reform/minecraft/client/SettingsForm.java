package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormTarget;
import java.util.Optional;
import net.minecraft.client.gui.components.AbstractWidget;

/** The actual retained editor presentation, independent of top-level native screen ownership. */
public interface SettingsForm<T> {
  DocumentId document();

  /** Existing admitted operations; their actual authority and lifetime checks still apply. */
  DocumentActions<T> editingActions();

  /** Cached editing state, not a fresh read or permission to save. */
  EditorActor.View<T> editorView();

  /** A currently mounted native control; hidden or retired widgets are not returned. */
  Optional<AbstractWidget> fieldControl(FieldPath path);

  /**
   * Nearest admitted section for the reveal target; empty if its catalog is unavailable or retired.
   */
  Optional<FieldPath> selectedSection();

  /** Select an admitted section, including an inactive section without a focusable control. */
  boolean selectSection(FieldPath path);

  boolean focusField(FieldPath path);

  /** Reveal an existing field or presentation box without manufacturing a data path. */
  default boolean focusTarget(FormTarget target) {
    return target instanceof FormTarget.Field field && focusField(field.path());
  }
}
