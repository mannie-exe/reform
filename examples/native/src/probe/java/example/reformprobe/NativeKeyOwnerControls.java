package example.reformprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.neoforge.client.KeyBindingOptions;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import example.reform.KeyExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Real native Options serialization, input lookup, authority and effect/readback controls. */
final class NativeKeyOwnerControls {
  private final Minecraft client = Minecraft.getInstance();
  private final OwnerContext context = context();
  private final JsonArray checks = new JsonArray();
  private final JsonObject closingWitness = new JsonObject();
  private boolean readAllowed = true, writeAllowed = true, complete;
  private KeyBindingOptions owner;
  private KeyBindingOptions.Session session;
  private OwnerContext dying;
  private Path path;

  private OwnerContext context() {
    return new OwnerContext(
        client::execute,
        Util.ioPool(),
        () -> {
          if (!client.isSameThread()) throw new AssertionError("Key owner escaped native executor");
        },
        OwnerContext.Limits.STANDARD);
  }

  private <T> T nativeCall(Callable<T> action) throws Exception {
    if (client.isSameThread())
      throw new AssertionError("Fixture coordinator blocked native executor");
    return context.onOwner(action).toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private void check(String name, boolean value) {
    if (!client.isSameThread()) throw new AssertionError("Key check escaped native executor");
    if (!value) throw new AssertionError(name);
    checks.add(name);
  }

  private void verify(String name, Callable<Boolean> condition) throws Exception {
    nativeCall(
        () -> {
          check(name, condition.call());
          return null;
        });
  }

  private KeyBindingOptions.Binding binding(InputConstants.Key key, KeyModifier modifier) {
    return new KeyBindingOptions.Binding(key.getName(), modifier);
  }

  private KeyBindingOptions.Prepared prepare(
      KeyBindingOptions.Observation read, KeyBindingOptions.Binding next) throws Exception {
    var draft =
        nativeCall(
            () ->
                DraftTree.from(
                    owner.definition().type(),
                    Map.of(KeyExample.PRIMARY.getName(), next),
                    DraftLimits.CONFIGURATION));
    return await(nativeCall(() -> session.prepare(read, draft)));
  }

  private KeyBindingOptions.Observation read() throws Exception {
    return await(nativeCall(session::read));
  }

  private KeyBindingOptions.Receipt commit(KeyBindingOptions.Prepared proposed) throws Exception {
    return await(nativeCall(() -> session.commit(proposed)));
  }

  CompletionStage<JsonObject> run(boolean reopen) {
    return context.onWorker(
        () -> {
          nativeCall(
              () -> {
                path = client.options.getFile().toPath().toAbsolutePath();
                owner =
                    new KeyBindingOptions(
                        context,
                        client.options,
                        DocumentId.parse("reform_native_example:native_keys"),
                        List.of(KeyExample.PRIMARY));
                session = owner.session(context.access(() -> readAllowed, () -> writeAllowed));
                return null;
              });
          var initial = read();
          verify(
              "actual native key observation is complete",
              () ->
                  initial.document().report().valid()
                      && initial.document().candidate().isPresent());
          if (reopen) {
            verify(
                "native options key survives second JVM",
                () ->
                    KeyExample.PRIMARY.getKey().equals(InputConstants.Type.MOUSE.getOrCreate(3))
                        && KeyExample.PRIMARY.getKeyModifier() == KeyModifier.NONE);
          } else controls(initial);
          return nativeCall(
              () -> {
                complete = true;
                return report();
              });
        });
  }

  private void controls(KeyBindingOptions.Observation initial) throws Exception {
    var name = KeyExample.PRIMARY.getName();
    var original = Files.readAllBytes(path);
    var noOp =
        commit(prepare(initial, initial.document().candidate().orElseThrow().value().get(name)));
    verify(
        "unchanged native key avoids serializer and refresh",
        () ->
            noOp.persistence() == CommitReceipt.Persistence.UNCHANGED
                && !noOp.mutationStarted()
                && !noOp.mappingRefreshed()
                && !noOp.nativeSaveReturned());
    for (var invalid :
        List.of(
            new KeyBindingOptions.Binding("not-a-native-key", KeyModifier.NONE),
            new KeyBindingOptions.Binding("key.keyboard.31", KeyModifier.NONE),
            new KeyBindingOptions.Binding("key.keyboard.0000000", KeyModifier.NONE),
            new KeyBindingOptions.Binding("scancode.99999999", KeyModifier.NONE),
            binding(
                InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_SHIFT),
                KeyModifier.SHIFT))) {
      try {
        prepare(initial, invalid);
        throw new AssertionError("Invalid native binding prepared");
      } catch (ExecutionException expected) {
        verify(
            "invalid native key/modifier refuses before effects",
            () -> expected.getCause() instanceof PolicyRefusal);
      }
    }
    for (char digit = '0'; digit <= '9'; digit++) {
      var nameOfKey = "key.keyboard." + digit;
      var prepared = prepare(initial, new KeyBindingOptions.Binding(nameOfKey, KeyModifier.NONE));
      verify(
          "canonical symbolic digit binding prepares without mutation: " + digit,
          () ->
              prepared.document().equals(owner.definition().id())
                  && KeyExample.PRIMARY.isDefault()
                  && java.util.Arrays.equals(original, Files.readAllBytes(path)));
    }
    var shifted =
        binding(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_H), KeyModifier.SHIFT);
    var proposal = prepare(initial, shifted);
    verify(
        "preparation changes no native mapping",
        () ->
            KeyExample.PRIMARY.getKey().equals(KeyExample.PRIMARY.getDefaultKey())
                && KeyExample.PRIMARY.getKeyModifier() == KeyModifier.NONE);
    if (!java.util.Arrays.equals(original, Files.readAllBytes(path)))
      throw new AssertionError("Prepare wrote native Options");
    nativeCall(
        () -> {
          client.options.invertYMouse().set(!client.options.invertYMouse().get());
          return null;
        });
    var saved = commit(proposal);
    verify(
        "native serializer and dispatch map receive explicit commit",
        () ->
            saved.persistence() == CommitReceipt.Persistence.SAVED
                && saved.application() == CommitReceipt.Application.APPLIED
                && saved.nativeSaveReturned()
                && saved.mappingRefreshed()
                && KeyExample.PRIMARY.getKey().getValue() == GLFW.GLFW_KEY_H
                && KeyExample.PRIMARY.getKeyModifier() == KeyModifier.SHIFT);
    var lines = Files.readString(path);
    verify(
        "native modifier syntax and unrelated live option are preserved",
        () ->
            lines.contains("key_" + name + ":" + shifted.key() + ":SHIFT")
                && lines.contains("invertYMouse:" + client.options.invertYMouse().get()));
    verify(
        "no additional JSON key source was created",
        () ->
            !Files.exists(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_native_example/native_keys.json")));
    var aliasSource =
        Files.readString(path)
            .replace(
                "key_" + name + ":" + shifted.key() + ":SHIFT",
                "key_" + name + ":key.keyboard.72:SHIFT");
    Files.writeString(path, aliasSource);
    nativeCall(
        () -> {
          client.options.load();
          return null;
        });
    var current = read();
    var afterAliasLoad = Files.readString(path);
    verify(
        "native aliases retain actual loader meaning",
        () ->
            current.document().report().valid()
                && aliasSource.contains("key_" + name + ":key.keyboard.72:SHIFT")
                && afterAliasLoad.equals(aliasSource)
                && current.currentBindings().get(name).equals(shifted)
                && current.document().candidate().orElseThrow().value().get(name).equals(shifted));
    var next = binding(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_J), KeyModifier.NONE);
    var external = prepare(current, next);
    var before = Files.readAllBytes(path);
    Files.writeString(path, Files.readString(path) + "\nfixture_external:true\n");
    var rejected = commit(external);
    verify(
        "exact native source conflict refuses before mutation",
        () ->
            rejected.persistence() == CommitReceipt.Persistence.REFUSED
                && !rejected.mutationStarted()
                && KeyExample.PRIMARY.getKey().getValue() == GLFW.GLFW_KEY_H);
    Files.write(path, before);
    var stale = prepare(read(), next);
    nativeCall(
        () -> {
          KeyExample.PRIMARY.setKeyModifierAndCode(
              KeyModifier.NONE, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K));
          return null;
        });
    var changed = commit(stale);
    verify(
        "changed live mapping is retained",
        () ->
            changed.persistence() == CommitReceipt.Persistence.REFUSED
                && !changed.mutationStarted()
                && KeyExample.PRIMARY.getKey().getValue() == GLFW.GLFW_KEY_K);
    nativeCall(
        () -> {
          KeyExample.PRIMARY.setKeyModifierAndCode(
              shifted.modifier(), InputConstants.getKey(shifted.key()));
          KeyMapping.resetMapping();
          return null;
        });
    var denied = prepare(read(), next);
    nativeCall(
        () -> {
          writeAllowed = false;
          return null;
        });
    var refusal = commit(denied);
    nativeCall(
        () -> {
          writeAllowed = true;
          return null;
        });
    verify(
        "fresh WRITE gates actual native commit",
        () ->
            refusal.persistence() == CommitReceipt.Persistence.REFUSED
                && !refusal.mutationStarted());
    try {
      commit(denied);
      throw new AssertionError("Replayed native key proposal");
    } catch (ExecutionException expected) {
      verify(
          "refused proposal remains one use", () -> expected.getCause() instanceof PolicyRefusal);
    }
    var valid = read();
    var second = nativeCall(() -> owner.session(context.access(() -> true, () -> true)));
    try {
      await(nativeCall(() -> second.prepare(valid, valid.document().draft().orElseThrow())));
      throw new AssertionError("Foreign observation accepted");
    } catch (ExecutionException expected) {
      verify(
          "native key session identity is required",
          () -> expected.getCause() instanceof PolicyRefusal);
    }
    nativeCall(
        () -> {
          second.close();
          readAllowed = false;
          return null;
        });
    try {
      read();
      throw new AssertionError("Revoked key read accepted");
    } catch (ExecutionException expected) {
      nativeCall(
          () -> {
            readAllowed = true;
            return null;
          });
      verify(
          "fresh READ gates native observation",
          () -> expected.getCause() instanceof PolicyRefusal);
    }
    var retained = Files.readAllBytes(path);
    Files.write(path, new byte[] {(byte) 0xff});
    var malformed = read();
    verify(
        "invalid native UTF-8 cannot supply a draft",
        () -> malformed.document().draft().isEmpty() && !malformed.document().report().valid());
    Files.write(path, retained);
    var mouse = binding(InputConstants.Type.MOUSE.getOrCreate(3), KeyModifier.NONE);
    var mouseSaved = commit(prepare(read(), mouse));
    verify(
        "native mouse binding is saved",
        () ->
            mouseSaved.persistence() == CommitReceipt.Persistence.SAVED
                && KeyExample.PRIMARY.getKey().equals(InputConstants.Type.MOUSE.getOrCreate(3)));
    nativeCall(
        () -> {
          while (KeyExample.PRIMARY.consumeClick()) {}
          KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(3));
          check(
              "actual native input lookup sees saved mouse mapping",
              KeyExample.PRIMARY.consumeClick() && !KeyExample.PRIMARY.consumeClick());
          return null;
        });
    var death =
        nativeCall(
            () -> {
              dying = context();
              var owner =
                  new KeyBindingOptions(
                      dying,
                      client.options,
                      DocumentId.parse("reform_native_example:closing_key"),
                      List.of(KeyExample.PRIMARY));
              return owner.session(dying.access(() -> true, () -> true));
            });
    var observed = await(nativeCall(death::read));
    var draft =
        nativeCall(
            () ->
                DraftTree.from(
                    death.definition().type(), Map.of(name, next), DraftLimits.CONFIGURATION));
    var dyingProposal = await(nativeCall(() -> death.prepare(observed, draft)));
    var prior = Files.readAllBytes(path);
    nativeCall(
        () -> {
          KeyExample.PRIMARY.afterSet = dying::close;
          return null;
        });
    var closing = await(nativeCall(() -> death.commit(dyingProposal)));
    boolean fileUnchanged = java.util.Arrays.equals(prior, Files.readAllBytes(path));
    nativeCall(
        () -> {
          closingWitness.addProperty("mutationStarted", closing.mutationStarted());
          closingWitness.addProperty("mappingRefreshed", closing.mappingRefreshed());
          closingWitness.addProperty("nativeSaveReturned", closing.nativeSaveReturned());
          closingWitness.addProperty("persistence", closing.persistence().name());
          closingWitness.addProperty("application", closing.application().name());
          closingWitness.addProperty("contextClosed", !dying.isOpen());
          closingWitness.addProperty("fileUnchanged", fileUnchanged);
          return null;
        });
    verify(
        "reentrant setter closure stops later save",
        () ->
            closing.mutationStarted()
                && !closing.nativeSaveReturned()
                && !closing.mappingRefreshed()
                && closing.persistence() == CommitReceipt.Persistence.UNKNOWN
                && !dying.isOpen());
    if (!java.util.Arrays.equals(prior, Files.readAllBytes(path)))
      throw new AssertionError("Closed key owner wrote native options");
    nativeCall(
        () -> {
          KeyExample.PRIMARY.setKeyModifierAndCode(
              mouse.modifier(), InputConstants.getKey(mouse.key()));
          KeyMapping.resetMapping();
          client.options.save();
          return null;
        });
  }

  JsonObject report() {
    var r = new JsonObject();
    r.addProperty("status", complete ? "PASS" : "RUNNING");
    r.add("checks", checks);
    r.add("closingWitness", closingWitness);
    r.addProperty("contextClosed", !context.isOpen());
    return r;
  }

  void shutdown() {
    KeyExample.PRIMARY.afterSet = null;
    if (dying != null && dying.isOpen()) dying.close();
    if (context.isOpen()) context.close();
  }
}
