package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.client.ui.ViewSpec;
import design.inherent.reform.presentation.ui.UiScope;

/** One admitted generated section, borrowing its actual native fields and original editor. */
@FunctionalInterface
public interface ClientSectionComponent<T> {
  ViewSpec build(UiScope scope, ClientSectionParts<T> parts);
}
