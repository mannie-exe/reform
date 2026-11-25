package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.presentation.ui.UiScope;

/** Builds one immutable declaration per mounted component identity, outside paint. */
@FunctionalInterface
public interface UiComponent<P> {
  ViewSpec build(UiScope scope, P props);
}
