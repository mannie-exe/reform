package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.client.ui.ViewSpec;
import design.inherent.reform.presentation.ui.UiScope;

/** Semantic field composition over the form's actual control, owner, and retained input. */
@FunctionalInterface
public interface ClientFieldComponent {
  ViewSpec build(UiScope scope, ClientFieldParts<?> parts);
}
