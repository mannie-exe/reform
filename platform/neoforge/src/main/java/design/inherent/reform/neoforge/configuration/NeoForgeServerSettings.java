package design.inherent.reform.neoforge.configuration;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

/** Genuine NeoForge config owners over the shared actual-server lifetime. */
public final class NeoForgeServerSettings {
  private static final ServerSettings.NativeKind<Map<String, Object>> KIND =
      ServerSettings.nativeKind();

  public static ServerSettings.Document<Map<String, Object>> registered(
      ServerSettings scope, ModContainer container, ModConfig config, DocumentId id) {
    return registered(scope, container, config, id, Map.of(), RegisteredConfig.Limits.STANDARD);
  }

  public static ServerSettings.Document<Map<String, Object>> registered(
      ServerSettings scope,
      ModContainer container,
      ModConfig config,
      DocumentId id,
      Map<FieldPath, ValueType<?>> types,
      RegisteredConfig.Limits limits) {
    return registered(
        scope, container, config, id, types, limits, RegisteredConfig.Policy.STANDARD);
  }

  public static ServerSettings.Document<Map<String, Object>> registered(
      ServerSettings scope,
      ModContainer container,
      ModConfig config,
      DocumentId id,
      Map<FieldPath, ValueType<?>> types,
      RegisteredConfig.Limits limits,
      RegisteredConfig.Policy policy) {
    Objects.requireNonNull(config);
    if (config.getType() == ModConfig.Type.CLIENT
        || !config.getModId().equals(Objects.requireNonNull(container).getModId()))
      throw new IllegalArgumentException("Use this mod's actual server-admitted native config");
    return bind(
        scope,
        container,
        config,
        id,
        limits,
        context -> new RegisteredConfig(context, config, id, types, limits, policy));
  }

  public static ServerSettings.Document<Map<String, Object>> delegated(
      ServerSettings scope,
      ModContainer consumer,
      DocumentId route,
      ModConfig config,
      DocumentId nativeId,
      RegisteredConfig.Delegation delegation,
      Map<FieldPath, ValueType<?>> types,
      RegisteredConfig.Limits limits) {
    return delegated(
        scope,
        consumer,
        route,
        config,
        nativeId,
        delegation,
        types,
        limits,
        RegisteredConfig.Policy.STANDARD);
  }

  public static ServerSettings.Document<Map<String, Object>> delegated(
      ServerSettings scope,
      ModContainer consumer,
      DocumentId route,
      ModConfig config,
      DocumentId nativeId,
      RegisteredConfig.Delegation delegation,
      Map<FieldPath, ValueType<?>> types,
      RegisteredConfig.Limits limits,
      RegisteredConfig.Policy policy) {
    Objects.requireNonNull(delegation).requireTarget(config);
    if (config.getType() == ModConfig.Type.CLIENT)
      throw new IllegalArgumentException("A client registration cannot be delegated on the server");
    return bind(
        scope,
        consumer,
        config,
        route,
        limits,
        context ->
            RegisteredConfig.delegated(
                context, config, nativeId, delegation, types, limits, policy));
  }

  private static ServerSettings.Document<Map<String, Object>> bind(
      ServerSettings scope,
      ModContainer container,
      ModConfig config,
      DocumentId route,
      RegisteredConfig.Limits limits,
      Function<OwnerContext, RegisteredConfig> factory) {
    Objects.requireNonNull(limits);
    return scope.nativePolicy(
        NeoForgeServerRegistration.of(container),
        route,
        config.getFullPath(),
        KIND,
        context -> {
          var owner = factory.apply(context);
          return new ServerSettings.NativeBinding<>(
              owner.definition(),
              owner::session,
              new JsonLimits(limits.maximumBytes(), 64, 16384, 32768, 128, 4096),
              DraftLimits.CONFIGURATION);
        });
  }

  static Optional<ServerSettings.Document<Map<String, Object>>> admitted(
      MinecraftServer server, DocumentId route) {
    return ServerSettings.admittedNative(server, route, KIND);
  }

  private NeoForgeServerSettings() {}
}
