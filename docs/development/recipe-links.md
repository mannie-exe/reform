# Recipe links

Recipe links are explicit, view-only client navigation. They do not transfer ingredients, craft, give items, unlock recipes, or run commands. A recipe viewer remains optional; installing re:form creates no preference file.

```java
var request = new RecipeLinkRequest(
    RecipeLinkRequest.Kind.PRODUCING,
    DocumentId.parse("minecraft:crafting_table"));
var destination = new ClientDestination.Recipe(request);
var availability = workflow.navigation().inspect(destination);
var attempt = workflow.navigation().open(destination);
```

`inspect` reads cached adapter readiness and capabilities. It does not load a document, enumerate recipes, or open a screen. `open` performs the explicit lookup. `HANDED_OFF` means one viewer opened with the actual workflow as its verified native parent. It is not an editing, saving, or application receipt. A missing match reports `recipe.no_match`; it never runs another action as a fallback.

## Selection and return

`RecipeLinkPolicy.standard()` admits JEI and EMI in that order. Automatic resolution selects the first ready provider supporting the requested kind. `choosing(provider)` declares an explicit preference. If it is absent, unsupported, or unavailable in the current context, resolution preserves that failure and offers a ready admitted alternate explicitly. It never silently changes a configured provider. The declaration is immutable and belongs to the consumer; the library persists no global choice.

Installation does not establish adapter admission or readiness. EMI's compatibility layer can skip a mod's JEI plugin when that mod already supplies an EMI plugin. In the tested coexistence configuration, re:form therefore has a ready EMI adapter and no admitted JEI runtime callback. Automatic selection chooses EMI; an explicit JEI preference remains unavailable and does not silently open EMI. The library does not bypass that compatibility policy.

`ClientRecipeLinks.inspect(destination)` also returns the admitted alternatives and their structured reports. Shared entry points contain no foreign API types. Optional adapters register through their viewer's client plugin admission. Dedicated and absent paths must not load those adapter classes.

A lookup remains an identified navigation attempt until activation. A newer destination supersedes a pending lookup before it can open a viewer. Readiness requires a complete, valid report; an incomplete report is unavailable even when it contains no issues.

The workflow preserves native raw input, focus, and all retained owners before the viewer replaces its screen. A successful handoff requires the viewer's actual public parent contract to point to that same root. A failed opening restores the root. Returning does not capture a new inspection, recalculate defaults, validate, save, or apply changes. Hiding the originating task retires its preview interest; returning does not implicitly prepare it again.

## JEI adapter

The adapter supports producing recipes and uses by exact item identity, exact source recipe identity, category identity, and item workstation identity. It queries visible categories and recipes through JEI's public API. Hidden content is not enabled. Item links use native registry lookup and a fresh stack, with no inventory reference. Exact recipe links compare JEI's declared source recipe IDs, including categories that represent native recipes through their own holders.

Lookup is bounded to 1,024 categories and 65,536 inspected recipe or workstation entries. Exceeding a bound reports `recipe.lookup_budget`; it is not a truncated successful match. No enumeration occurs during painting, search, or ordinary availability inspection.

Dependency versions belong to the version catalog and target matrix. The optional metadata admits the selected API floor within its major version. An admitted range is an API boundary, not native qualification of every version in it. Plugin lifetime and prepared calls are guarded by the actual JEI runtime, refresh generation, world, connection, and original screen.

The 1.21.1 adapter compiles against the separately declared 19.71.0.470 API floor; the selected additional runtime remains 19.76.1.481. Its recipes surface uses the public `getRecipesGui()` interface, which the newer runtime still bridges. Opening and parent validation require that actual surface to be the current Minecraft screen: an older viewer's retained parent value alone cannot establish that it is open. The 26.3 seam keeps its GUI-runtime API. The newer runtime's refresh callback remains implemented, while compilation against the older interface does not require that callback to exist there. Exact old/new runtime and coexistence qualification remains a separate verification gate.

## EMI and native containers

The 1.21.1 adapter supports producing recipes, uses, exact recipe identity, and category identity through EMI's public API. It does not advertise workstation-only requests: the public uses screen combines ingredient and workstation relations, and must not be presented as a narrower view. Its plugin observes public recipe-manager publication after registration, with actual world/connection and client-resource generations checked before opening. An empty published catalogue remains distinct from an unpublished manager.

EMI requires a real, already-open native container to preserve its parent. The shared settings root reports `recipe.return_unavailable` and offers a ready admitted alternate explicitly. It never manufactures an inventory screen. Owners with an actual native `AbstractContainerScreen` can call `ClientRecipeLinks.openContainer(owner, destination)`; the current screen and the player's actual menu must still match. The result uses the same identified `NavigationAttempt`. The caller can inspect admitted alternatives and explicitly choose one; no preference is silently rewritten.

After explicit lookup, the helper rechecks the current container, menu, and world/resource context before invoking the prepared action. A lookup that retires its origin is refused without reopening it. Failure restoration also requires the original menu and context; it does not restore a retired world or menu.

`ClientRecipeLinks.context()` exposes world/connection and resource-generation identities without retaining native owners. `resourcesReady` describes client resources only. It grants no read, write, gameplay, or menu authority. An adapter still checks its actual viewer and current native screen.

## Explicit native recipe book

`ClientNativeRecipeBook.inspect(owner)` and `open(owner)` accept the owner's real, current native survival inventory or crafting screen. They do not create a screen, player, world, or menu, and are not the resolver's default fallback. The native book continues to show that player's existing unlocked recipes.

The helper identifies the mounted book and its actual native visibility action, using a client-only read accessor for the button's sprite identity. It invokes the native button so Minecraft updates both book visibility and container placement. Modified, missing, or ambiguous controls report unavailable. Already-visible books stay visible. This does not replace a renderer, craft, transfer, give items, or grant unlocks. The ordinary Minecraft book-open preference can change through its own action; no re:form configuration file is created.

EMI can repurpose that same button without changing its native identity. With EMI installed, re:form conservatively refuses to activate a closed native book with `recipe.book_integration_unavailable`. This is a limit of this helper, not a claim about every EMI configuration. It does not read or override EMI's preference; the screen's own controls and the separately admitted EMI links remain available. An already-visible native book requires no activation.

The result reports the supplied owner's observed visibility, whether that displayed state changed, its structured report, and an optional cause. Check the report even if the book became visible: a failing native callback is not evidence that nothing changed. A stale or retired owner is unavailable and is never reopened implicitly.

## Qualification

Candidate 0.4.0-dev.76 passes two actual client boots on each target for all five JEI link kinds, exact original-parent return with a dirty editor and retained native caret, missing-match refusal, and explicit alternate selection. The fixture leaves its source file unchanged. JEI logged a delayed history-save error after world departure; no upstream suppression or correction is claimed. The four actual client absent paths also pass, with the loaders confirming no JEI or EMI, unavailable resolution, unchanged source, and normal shutdown. Candidate 0.4.0-dev.77 additionally passes a controlled public-adapter lookup supersession case and ordinary JEI handoffs on NeoForge 1.21.1 and Fabric 26.3, with two boots per case and target. This controlled case is not a reproduced JEI defect or proof for arbitrary callbacks inside a foreign viewer. Dedicated-server absence and other context changes remain separate checks; consult the current verification record for tested artifacts.

Candidate 0.4.0-dev.78 passes the supplied native book fixture on NeoForge 1.21.1 and Fabric 26.3, with two normal boots per target and unchanged inventory, unlocks, and owner file. An actual EMI fixture exposed the repurposed native button; a separate controlled adapter lookup exposed restoration of a retired container. Both negative records remain attributed to dev.78.

Candidate dev.79 passes the supplied native survival-inventory book and controlled lookup-retirement checks on all four targets, with two normal boots per target. It also passes both 1.21.1 loaders with EMI installed: four supported public link kinds return to the same native container, the unavailable book integration refuses before activation, and inventory, unlocks, and the owner file stay unchanged. These six pairs total 12 normal exits. Crafting-screen-specific behavior, other coexistence configurations, upstream runtime failure, arbitrary callbacks inside a viewer, and final combined consumer qualification remain distinct checks. No EMI 26.3 compatibility is claimed. Compilation is not evidence of native opening, return, permissions, or unchanged inventory and unlocks.

Additional dev.79 coexistence pairs on both 1.21.1 loaders pass 46 checks per boot with JEI and EMI installed. They observe the actual admission described above, default choice, explicit unavailable preference, unsupported configured request without fallthrough, actual native return, and unchanged inventory, unlocks, and owner file. Both clients exit normally on both boots. EMI logs duplicate recipe-ID warnings from its JEI compatibility import; the logs and displayed warnings remain part of the evidence. No complete upstream catalogue-integrity or two simultaneously enabled recipe-screen claim follows from this configuration.

## Updating and qualifying adapters

Refresh the actual game and loader listings before selecting a version. Resolve both the public API and installable runtime from their publishers, retain their original hashes and source provenance, and keep pins in the catalog through the target matrix. API compilation, installed-version admission, and actual runtime qualification are separate facts. Recheck the native item ID and workstation APIs when crossing game lines. A provider missing from one listing does not establish that no compatible build exists anywhere.

The independent client fixture uses `--recipe-links-client`; add `--recipe-viewer jei` for the selected installed runtime. Without that option it tests absence. `--native-recipe-client` tests a supplied real native book context; add `--recipe-viewer emi` on a declared 1.21.1 target for EMI's corresponding return contract. The fixture reads the loader's actual installed IDs and versions, verifies the original runtime hash against Gradle resolution, and resolves upstream runtime dependencies before launching. Keep required upstream configuration dependencies in the fixture's explicit repository filter; they are not re:form dependencies or bundled classes.

`--minimum-recipe-viewer` selects the separately declared JEI API-floor runtime for a JEI or dual-viewer scene. It refuses unrelated scenes and targets without that input before resolving the library. The same original-runtime hash check applies, and the report directory has an `-api-floor` suffix so this evidence remains separate from the selected additional runtime. No flag changes an installed pack or a personal instance.

The 1.21.1 fixture also declares and verifies the Mezz Config 0.7.1 runtime companion. Older JEI's published dependency metadata does not supply it, although the loader requires it. The companion is an independent fixture input, not a bundled re:form dependency or a reason to rewrite JEI's original archive. Old/new comparisons use the same companion, record its original hash separately, and leave installed packs unchanged.

Use `--native-recipe-client --recipe-viewer both` for the declared 1.21.1 coexistence case. The fixture verifies both original runtime inputs, rather than accepting one provider's hash for the pair. Verification also records actual loader archive identities and matching public class-resource witnesses, retaining one archive copy per content hash. A remapped archive has its own identity; this does not prove independently reproducible remapping or the bytes of transformed executable memory.

Qualify the five request kinds, no match, configured unavailable preference, explicit alternate, actual parent return, unchanged dirty owner/input/source, and normal shutdown on each supported target. Run absent and dedicated paths separately. Preserve resource/context-loss and refused return-path cases when changing lifecycle APIs. Keep these fixtures and upstream binaries out of installable and source artifacts.
