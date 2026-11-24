package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.GraphicLedger;
import design.inherent.reform.presentation.graphics.GraphicSourceStamp;
import design.inherent.reform.presentation.graphics.RenderLifetime;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Actual client and attachments owned by the same ledger and exact native-use lifetime. */
public final class NativeGraphicPreparationContext {
  private final Minecraft client;
  private final GraphicLedger ledger;
  private final GraphicSourceStamp stamp;
  private final RenderLifetime lifetime;
  private final Consumer<NativeGraphicResource> own;

  NativeGraphicPreparationContext(
      Minecraft client,
      GraphicLedger ledger,
      GraphicSourceStamp stamp,
      RenderLifetime lifetime,
      Consumer<NativeGraphicResource> own) {
    this.client = Objects.requireNonNull(client);
    this.ledger = Objects.requireNonNull(ledger);
    this.stamp = Objects.requireNonNull(stamp);
    this.lifetime = Objects.requireNonNull(lifetime);
    this.own = Objects.requireNonNull(own);
  }

  public Minecraft client() {
    return client;
  }

  public GraphicLedger ledger() {
    return ledger;
  }

  public GraphicSourceStamp stamp() {
    return stamp;
  }

  /** Register once before allocation. Returned content is retained automatically as well. */
  public <R extends NativeGraphicResource> R own(R resource) {
    own.accept(Objects.requireNonNull(resource));
    return resource;
  }

  RenderLifetime lifetime() {
    return lifetime;
  }
}
