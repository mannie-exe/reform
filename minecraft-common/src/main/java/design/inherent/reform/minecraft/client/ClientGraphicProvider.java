package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.value.ValueType;
import java.util.concurrent.CompletionStage;

/** Domain capture/compilation, never a private viewport, writer, executor, or live-manager swap. */
public interface ClientGraphicProvider<T, S> {
  DocumentId id();

  /** Bounded serializable selection parameters, not the complete document or texture payload. */
  ValueType<S> subjectType();

  GraphicLimits limits();

  /** Cheap client-thread observation of the actual resource snapshot identity; never discovery. */
  ReferenceStamp resourceStamp();

  /** Compose explicit worker/native stages; do not block while waiting for a worker result. */
  CompletionStage<GraphicScenePreparation> prepare(
      GraphicRequest<T, S> request, ClientGraphicWork work);
}
