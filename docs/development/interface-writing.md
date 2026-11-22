# Interface writing

Write for someone editing settings or creating a world. Lead with the action, its scope, and the observed result. Use familiar words and sentence case. Explain the implementation when it helps the person choose a safe next action.

This applies the [inherent.design writing reference](https://github.com/inherent-design/inherent.design/blob/main/packages/docs/knowledge/process-notes/prose-and-communication-reference.md) to re:form. The existing [owner](owners.md), [operation](operations.md), and [creation-recipe](world-recipes.md) contracts determine what a message can truthfully claim.

## Actions and scope

Use consistent verbs for distinct effects. Labels can be short when the context and help make their scope clear.

| Action   | Meaning                                                             |
| -------- | ------------------------------------------------------------------- |
| Edit     | Change the retained draft                                           |
| Validate | Check the complete candidate without saving                         |
| Save     | Persist through the actual file owner                               |
| Apply    | Request the declared runtime or creation-context effect             |
| Reload   | Read the current source through its owner                           |
| Revert   | Restore the editor's observed starting values                       |
| Defaults | Load declared defaults into the draft                               |
| Import   | Review a source and stage an undoable draft replacement             |
| Export   | Produce the reviewed output; name its actual destination or handoff |
| Cancel   | Request cancellation; the operation still reports entered effects   |

Keep explicit labels such as Save for restart or Apply recipe when the ordinary verb would hide an important distinction. Reset must identify what it resets. A button must not imply that saving a profile publishes resources, changes existing terrain, or rebuilds live registries.

## Feedback and recovery

Give routine success a short summary. Keep detailed source, revision, policy, and per-field information available through the existing inspection and result views. Show a result summary once in a view. A successful save does not need a second narrative explaining that it succeeded.

Describe what is known. Saved, applied, staged, partial, failed, and unknown are different outcomes. Keep later edits separate from the submitted values. Preserve a known save when application fails, and retain uncertainty when readback cannot establish the final state.

Prefer “Saved; changes are not confirmed as applied” to a generic failure that implies nothing was written. Prefer “The result is unknown. Review it before trying again” to an instruction to retry a possibly completed write. A cancellation request does not prove rollback or that active work has stopped.

For a problem, name the affected action or value, explain the consequence, and provide an available recovery step. Avoid blame, alarm, apologies, and admonitions such as “do not repeat it blindly.” Do not suggest a button, restart, reload, or retry that the current context cannot provide safely.

## Names and technical detail

Use declared field labels and profile titles when available. Preserve resource IDs, serialized field names, paths, enum codes, and operation identities for diagnostics and accurate identification. Human labels do not replace those identities or grant authority.

In routine feedback, use save, settings, changes, and result before persistence, owner, baseline, and receipt. Use the technical terms in API documentation and detailed inspection when their distinction matters. Do not expose an internal class name as the only explanation of an unavailable value.

## Translation and accessibility

Keep translation keys stable when their meaning is unchanged. Built-in translatable fallbacks must have the same meaning and argument contract as an overridden translation. Keep user-authored values and machine identifiers out of translated templates except as explicit arguments.

Use native Components and narration paths. Retain errors and recovery information as text; color or a disabled button alone is insufficient. Avoid relying on a symbol without an accessible label. Preserve keyboard focus, retained input, and measured wrapping when wording or language changes.

Use complete, direct sentences for explanations and concise phrases for labels. Apply Oxford commas to English lists. Prefer ordinary punctuation to dramatic asides, marketing claims, and repeated caveats. Follow each locale's grammar rather than copying English word order.

## Review

Check related labels, tooltips, command output, and narration together. Verify that the text matches actual permissions, source observations, effect timing, and recovery behavior. Run formatting, links, and affected compilation/tests. Native layout, input-method, and spoken-output claims require their own observed checks; a copy edit does not establish them.
