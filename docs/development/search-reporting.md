# Native search reporting

`SearchReport<R>` captures the results of one admitted search. A provider retains candidate enumeration, filtering, deduplication, ranking and scheduling. The report receives a bounded ranked list of immutable rows and the distinct matches observed so far. It keeps enumerated, verified and rejected candidates separate from those matches. A provider that knows the total can declare it once. A configured work limit is a bound and does not count as an enumerated total.

Complete coverage requires every candidate in a known total to have been verified or rejected. A search ending because of a coverage limit, failure or cancellation keeps the rows that were already admitted. The terminal reason and the display limit are separate. A complete search can retain its nearest rows and still report a larger exact number of matches. A partial search reports the count it observed.

## Publication and navigation

`capture(page)` publishes the current results and returns a page with its job UUID and exact report revision. `page(cursor)` follows only that issued capture. Later ranking changes cannot reorder its rows. The default retains eight published snapshots of at most 64 rows each and displays ten rows per page. Receiving observations alone does not evict issued pages. An expired cursor requires a new status/page request.

`notice(tick)` coalesces the number of newly observed matches. Call it with a monotonic native tick when the actual recipient can receive updates. The standard interval is 40 ticks after the first notice. A terminal result flushes any unseen changes. Turning off watching leaves previous observations available through status and paging.

## Outstanding requests and tickets

Use `WorkBudget` on the native scheduling thread and create a `Group` for each admitted job. A group reservation occupies one shared slot until the provider's completion callback closes it. A lower live ceiling restricts new work while existing requests finish. Release a rejected submission through the same reservation closure. Repeated closure has no further effect.

Attach this request's actual native ticket-removal action after acquiring the ticket. A provider may release its tickets early or keep them until settlement, according to its existing native lifecycle. Early ticket release does not free a request slot. Stopping a group prevents new admission while its reservations drain. Ticket-removal failures are reported to the provider and do not prevent settlement accounting or the other owned cleanup actions from running. Keep owner-thread settlement available for the native callback lifetime.

At completion, settle the reservation on the native thread **before** testing whether its originating job is still selected. That test controls whether the delivered data can be used for results. It does not control cleanup. Preserve distinct ticket keys and the native scheduler's actual queue, status and concurrency policy. The library does not start or cancel chunk futures.

Minecraft 1.21.1 region tickets accept a caller key such as a UUID. Minecraft 26.3 instead matches tickets by the exact `TicketType` object and level in a chunk; equal record values do not establish the same native type identity. A provider reusing one type/level for overlapping requests must retain its own sharing count so one cancellation cannot remove another request's physical ticket. `WorkBudget` accepts the provider's actual cleanup action and does not invent UUID keys or a force-ticking facility where the game lacks them.

## Native command binding

Capture `SearchRecipient` from the actual console or current player on the native server thread. It retains the exact level and the origin selected at admission. A movement within that world changes the recipient's current position and leaves the origin intact. A native replacement player on the same play connection can receive the search's results. A different login, another level, or loss of current permission yields no recipient. Providers use that check to retire work when its audience leaves, and handle world unload and server shutdown through their native lifecycle.

`SearchCommands` attaches `status`, `page`, `watch` and `cancel` to an explicit command path. Register its branch as a child of the caller's root and supply the existing search command and native permission gate. Use a noncreating `Binding` to look up current jobs. A provider that retains earlier jobs can also look them up by exact UUID. Generated page links carry the issued job, revision and page. Status and cancellation links address the issuing job. A lookup for a removed job refuses without acting on a successor. All output uses the current native recipient and translatable fallback Components.

The cancellation branch freezes report updates, stops reservation admission and asks the provider to cancel. The provider records the terminal reason and handles its own native cleanup. A terminal result may still display outstanding requests. Search rows can describe coordinates or suggested actions through a bounded consumer renderer. Their presence confirms only the provider's declared result facts.

[Verification](verification.md#native-search-reporting) records evidence from the pinned Minecraft 1.21.1 control flow and independent native fixtures. Consumer adoption must qualify its actual candidate counts, source scheduling, structure identity and result meanings.

On Minecraft or NeoForge updates, recheck native player replacement and connection timing, command parsing and click actions, ticket key comparison, chunk future admission, and owner-thread delivery during world unload and server stop.
