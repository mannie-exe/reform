package design.inherent.reform.minecraft.client;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.presentation.graphics.GraphicSceneData;
import design.inherent.reform.presentation.graphics.GraphicScenePreparation;
import design.inherent.reform.presentation.graphics.GraphicSourceStamp;
import design.inherent.reform.value.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Frozen, bounded native input; only the existing host may turn it into a submitted lease. */
public final class NativeGraphicContribution<I> implements GraphicScenePreparation.Contribution {
  private final NativeGraphicAdapter<I> adapter;
  private final DocumentId adapterId;
  private final ValueType<I> inputType;
  private final I input;
  private final GraphicSourceStamp stamp;
  private final GraphicCamera camera;
  private final List<GraphicSceneData.Fact> facts;
  private final long outputBytes;

  public NativeGraphicContribution(
      NativeGraphicAdapter<I> adapter,
      I input,
      GraphicSourceStamp stamp,
      GraphicCamera camera,
      List<GraphicSceneData.Fact> facts) {
    this.adapter = Objects.requireNonNull(adapter);
    adapterId = Objects.requireNonNull(adapter.id());
    inputType = Objects.requireNonNull(adapter.inputType());
    this.input = inputType.freeze(input);
    this.stamp = Objects.requireNonNull(stamp);
    this.camera = Objects.requireNonNull(camera);
    this.facts = GraphicSceneData.freezeFacts(facts);
    var encoded = inputType.codec().encodeStart(JsonOps.INSTANCE, this.input);
    if (encoded.error().isPresent())
      throw new PolicyRefusal("preview.input_invalid", encoded.error().orElseThrow().message());
    outputBytes =
        Math.addExact(
            encoded.result().orElseThrow().toString().getBytes(StandardCharsets.UTF_8).length,
            GraphicSceneData.factBytes(this.facts));
  }

  public NativeGraphicAdapter<I> adapter() {
    return adapter;
  }

  public DocumentId adapterId() {
    return adapterId;
  }

  public I input() {
    return inputType.freeze(input);
  }

  @Override
  public GraphicSourceStamp stamp() {
    return stamp;
  }

  @Override
  public GraphicCamera camera() {
    return camera;
  }

  @Override
  public List<GraphicSceneData.Fact> facts() {
    return facts;
  }

  @Override
  public long outputBytes() {
    return outputBytes;
  }
}
