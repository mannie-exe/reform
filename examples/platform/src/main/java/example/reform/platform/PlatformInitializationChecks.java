package example.reform.platform;

import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.storage.TextStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;

/** A controlled unexpected owner-construction failure at the real native setup boundary. */
final class PlatformInitializationChecks {
  private static ClientFileSettings<PlatformData.Values> settings;
  private static ClientFileSettings<PlatformData.Values> refused;
  private static Path path;
  private static Path refusalPath;

  static void register(ClientRegistration nativeRegistration) {
    path = nativeRegistration.configurationDirectory().resolve("reform-platform-init-fault.json");
    refusalPath =
        nativeRegistration.configurationDirectory().resolve("reform-platform-init-refusal.json");
    settings = register(nativeRegistration, "init_fault", path, false);
    refused = register(nativeRegistration, "init_refusal", refusalPath, true);
  }

  private static ClientFileSettings<PlatformData.Values> register(
      ClientRegistration nativeRegistration, String name, Path file, boolean expectedRefusal) {
    var store = new FaultyStore(PlatformData.definition(name), file, expectedRefusal);
    var registration =
        new ClientRegistration() {
          @Override
          public String modId() {
            return nativeRegistration.modId();
          }

          @Override
          public Path configurationDirectory() {
            return nativeRegistration.configurationDirectory();
          }

          @Override
          public void onSetup(Runnable callback) {
            nativeRegistration.onSetup(
                () -> {
                  store.broken = true;
                  callback.run();
                });
          }

          @Override
          public void registerMenu(Function<Screen, Screen> factory) {
            nativeRegistration.registerMenu(factory);
          }

          @Override
          public Subscription registerCommands(String literal, CommandTree tree) {
            return nativeRegistration.registerCommands(literal, tree);
          }
        };
    var registered = ClientFileSettings.clientFile(registration, store);
    if (registered.status().lifecycle() != ClientFileSettings.Lifecycle.REGISTERED)
      throw new AssertionError("Fault fixture performed early initialization");
    return registered;
  }

  static JsonObject report() {
    if (!settings.initialization().toCompletableFuture().isDone()
        || settings.status().lifecycle() != ClientFileSettings.Lifecycle.UNAVAILABLE
        || settings.status().report().valid()
        || settings.runtime().isPresent()
        || Files.exists(path)
        || !refused.initialization().toCompletableFuture().isDone()
        || refused.status().lifecycle() != ClientFileSettings.Lifecycle.UNAVAILABLE
        || refused.runtime().isPresent()
        || Files.exists(refusalPath)
        || refused.status().report().issues().stream()
            .noneMatch(issue -> issue.code().equals("fixture.expected_refusal")))
      throw new AssertionError("Unexpected construction failure supplied defaults or performed IO");
    var report = new JsonObject();
    report.addProperty("status", "PASS");
    report.addProperty("lifecycle", settings.status().lifecycle().name());
    report.addProperty("runtimeAbsent", true);
    report.addProperty("sourceAbsent", true);
    report.addProperty("expectedRefusalRetained", true);
    return report;
  }

  private static final class FaultyStore extends TextStore<PlatformData.Values> {
    private boolean broken;
    private final boolean expectedRefusal;

    private FaultyStore(
        DocumentDefinition<PlatformData.Values> definition, Path file, boolean expectedRefusal) {
      super(
          definition,
          new OwnedFile(
              StorageBinding.relative(file.getParent(), file.getFileName().toString()), 262144),
          new JsonLimits(262144, 64, 16384, 32768, 128, 4096),
          DraftLimits.CONFIGURATION,
          "fixture",
          "Controlled construction fault");
      this.expectedRefusal = expectedRefusal;
    }

    @Override
    public DocumentDefinition<PlatformData.Values> definition() {
      if (broken) {
        if (expectedRefusal)
          throw new PolicyRefusal("fixture.expected_refusal", "reform-native-expected-refusal");
        throw new IllegalStateException("reform-native-init-fault");
      }
      return super.definition();
    }

    @Override
    protected DraftTree decode(String text) {
      throw new AssertionError("Fault fixture entered source decoding");
    }

    @Override
    protected String encode(
        Read<PlatformData.Values> baseline, Candidate<PlatformData.Values> candidate) {
      throw new AssertionError("Fault fixture entered source encoding");
    }

    @Override
    protected void validateEncoded(String text) {
      throw new AssertionError("Fault fixture entered source validation");
    }
  }

  private PlatformInitializationChecks() {}
}
