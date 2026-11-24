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

`ClientRecipeLinks.inspect(destination)` also returns the admitted alternatives and their structured reports. Shared entry points contain no foreign API types. Optional adapters register through their viewer's client plugin admission. Dedicated and absent paths must not load those adapter classes.

The workflow preserves native raw input, focus, and all retained owners before the viewer replaces its screen. A successful handoff requires the viewer's actual public parent contract to point to that same root. A failed opening restores the root. Returning does not capture a new inspection, recalculate defaults, validate, save, or apply changes. Hiding the originating task retires its preview interest; returning does not implicitly prepare it again.

## JEI adapter

The adapter supports producing recipes and uses by exact item identity, exact source recipe identity, category identity, and item workstation identity. It queries visible categories and recipes through JEI's public API. Hidden content is not enabled. Item links use native registry lookup and a fresh stack, with no inventory reference. Exact recipe links compare JEI's declared source recipe IDs, including categories that represent native recipes through their own holders.

Lookup is bounded to 1,024 categories and 65,536 inspected recipe or workstation entries. Exceeding a bound reports `recipe.lookup_budget`; it is not a truncated successful match. No enumeration occurs during painting, search, or ordinary availability inspection.

Dependency versions belong to the version catalog and target matrix. The optional metadata admits the selected API floor within its major version. An admitted range is an API boundary, not native qualification of every version in it. Plugin lifetime and prepared calls are guarded by the actual JEI runtime, refresh generation, world, connection, and original screen.

## Remaining integration qualification

The initial implementation compiles against the selected JEI APIs on all four targets. Present-viewer native execution, parent-return behavior, and absence remain separate checks; consult the current verification record for tested artifacts. EMI's public API replaces a non-inventory origin before opening its recipe screen. It must report that return-path limitation or use a separately qualified wrapper. No EMI adapter or 26.3 compatibility is implied by the resolver declaration.

The native recipe book remains an explicit, context-specific integration: it requires an owner-supplied real compatible crafting or inventory screen, a player, and that player's native unlocked recipes. It is not an automatic fallback and must not create a fictitious context or grant unlocks. This limited adapter is not yet implemented.
