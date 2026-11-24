package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.value.ValueType;

/** Cached identity, bounded subject, and budgets shared by draft and loaded-resource providers. */
public interface ClientGraphicSource<S> {
  DocumentId id();

  ValueType<S> subjectType();

  GraphicLimits limits();

  ReferenceStamp resourceStamp();
}
