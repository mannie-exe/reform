package design.inherent.reform.minecraft.client;

import design.inherent.reform.operation.OperationSnapshot;
import java.util.Optional;

/** Cached disclosure of an actual retained handle, without operation or write authority. */
public interface ClientOperationInspection {
  Optional<? extends OperationSnapshot<?>> observedOperation();
}
