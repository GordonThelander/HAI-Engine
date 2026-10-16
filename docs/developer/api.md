# HAI Engine interfaces

Applies to HAI Engine version 0.5.87.

Everything here except the Automation Map feed (last section) is served by HAI Engine and needs its access token (`?access_token=...`), shown on the HAI
Engine page. The token is a private credential: anyone holding it can edit drafts and put rules on the hub, although
HAI still checks every rule, and, with rule end points on, run, stop, pause, resume
and trigger rules.

**Changing them.** **Change HAI's access addresses**, in its own section of the HAI Engine page, makes a new
access token, new audience keys (`aud`) for the editor, the end points and MCP, a new API token for other apps
(below), and a new access token for HAI Migration's page. Every old address and the old API token stop working
at once: open editor tabs, saved links, dashboard buttons, the MCP client's address and other apps' requests.
Rules keep running. Each rule app's **Open this rule in the editor** link is sent the new address, a few rule
apps at a time.

## Editor API

Base: `/apps/api/<engine app id>/editor/api/`. Bodies are JSON. Errors return
`{ok: false, error: <code>, message: <text for a person>}`.

| Route | Method | Body | Returns |
|---|---|---|---|
| `state` | GET | none | The editor's opening data: devices, modes, hub variables, Rule Machine rules, drafts, the runtime summary and the size limits |
| `fixed` | GET | `?v=<version>` | The field specs, labels, templates and command list. Fixed for a build; cached by the browser when `v` names the current build |
| `device` | GET | `?ids=<id,id,...>` (up to 20) | Those devices' rows with their current values, in the shape of a Check's `values`. Reads only those devices |
| `check` | POST | `{rule}` | Validation findings, read-back, simulation, approvals needed, activation blockers. Stores nothing. |
| `rule` | GET | `?id=<draft or proposal id>` | The rule, with device names refreshed to their current names (`renamed` lists any change) |
| `save` | POST | `{id?, name, rule}` | Saves a draft. No count limit; a draft, and all drafts together, have byte limits. |
| `delete` | POST | `{id}` | Deletes a draft. |
| `send` | POST | `{op: "run", rule}` or `{op: "pause" \| "resume" \| "stop" \| "start", ruleId}` | Signs the request and hands it to the engine's own runtime in the same call. `run` puts the rule on the hub: it is checked and, if it passes, activated. It does **not** run the steps: the rule waits for its triggers (the rule child is created and committed with `runs: 0`). To run the steps once, use `runNow` (the `rules/<rule id>/run` end point below). The outcome shows in the runtime summary. |
| `runtime` | GET | none | The latest runtime summary. |
| `uses` | GET | `?device=<id>` (`dev:12` or `12`) | `{ok, device, rules, drafts}`: the rules on the hub (`id`, `name`, `state`, `paused`, `epoch`, `ruleHash`, `rule`) and the drafts (`id`, `name`, `ruleId`, `rule`) that name that device, with their documents. For the editor's Replace / add alongside / remove. Each rule's index (`deviceIds`, kept in the registry since activation) decides; only the rules it names are read from their apps. Reads nothing else and writes nothing. |
| `backups` | GET | none | `{ok, backups, keep, daily}`: the rule backups, newest first, each `{name, at, bytes, rules}` (`name` is `hai-backup-<yyyyMMdd>.json` in File Manager), from the index the Engine keeps in its state. No file is read. |
| `backup` | GET | `?name=hai-backup-<yyyyMMdd>.json` | `{ok, name, backup}`: that one backup file as written, `{format: "hai.backup/1", engine, at, rules: [{id, name, state, paused, epoch, ruleHash, rule}]}`. Only a name the index lists is read; it reads that one file and nothing else. `NO_SUCH_BACKUP` (404) otherwise. |
| `backup` | POST | `{}` | Back up now: writes today's file whether or not a rule changed, replacing an earlier one from today, and deletes the oldest past ten. `{ok, written, name, bytes, rules, unread, backups}`. |
| `export` | POST | `{rule}` | `{ok, bundle: {portable: ...}, name}` — a portable bundle with no local device ids. The binding manifest is not returned; it is only useful on the exporting hub. See [the bundle format](bundle-format.md) |
| `import` | POST | `{text}` | The rules in a bundle, matched to this hub: `toConfirm` lists devices matched by name, and `toChoose` counts those left to choose. A plain rule is returned as it is. |

**Rule backups.** Once a day at 03:41:43 hub time, the Engine
compares one hash of every live rule's id, version and rule hash, taken from the registry, with the one the last backup
stored. With no change it stops there: no rule is read and nothing is written. Otherwise it reads each live rule's
document once from its rule app (active and stopped rules; never drafts), writes them as one File Manager file,
`hai-backup-<yyyyMMdd>.json`, and keeps the last ten, deleting the one past the tenth. A rule whose document cannot be
read is left out and the change is not recorded, so the next day tries again. The editor's More menu > Backups lists,
downloads, compares and restores them; a restore goes through `check` and `send` like any Put on hub. The files stay in
File Manager if HAI Engine is removed.

### What `state` says about each device

Each entry of `devices` (from `editorDevices` in `engine/lib/hai_editor.groovy`):

| Field | Holds |
|---|---|
| `id`, `name`, `room`, `driver` | The device, and its driver's type name |
| `capabilities` | The capabilities its driver declares |
| `attributes` | Every attribute its driver declares |
| `values` | Each attribute's current value. An attribute with no value is absent |
| `reported` | The attributes that hold a value, sorted. The editor's pickers offer only these, because a capability's standard attribute is not a report: a LIFX bulb declares SignalStrength and reports `rssi`, never `lqi` |
| `classes` | The keys of the Rule Machine device classes it belongs to: those whose capability it declares **and** whose attribute it reports. A Kasa plug declaring PowerMeter with no `power` value is not a Power meter |
| `engineCommands` | Commands HAI's catalogue knows and the device offers |
| `otherDeviceCommands` | Its driver's other commands, sorted, at most 200; `null` when the hub did not list them |
| `otherDeviceCommandsNotShown` | How many were left out past 200. Absent when none were |
| `disabled`, `variable`, `variableName`, `buttons` | Present when they apply |

The inventory behind it reads capabilities, commands and attribute names once per driver type and only the
current values per device (`engineInventory`), which is what keeps `state` fast on a hub with
hundreds of devices.

`fixed` carries `specs`, which holds, besides each block's fields, `deviceClasses`, Rule Machine's 34 device classes as
`{key, label, capability, attribute, values | numeric}`; `neverAttributes`, attributes no picker offers
(`numberOfButtons`, `label`, `uid` and the button events); and `nonReadingAttributes`, attributes a
threshold cannot use.

### Keys in a submitted rule

A rule sent through `send` or `save` passes the strict JSON profile, which accepts only object keys matching
`[A-Za-z][A-Za-z0-9_]*`. So a hand-written rule cannot key its device references as `dev:101`: give each
reference a plain key and put the device id inside it. Rules built by the Rule Machine migration keep `dev:NNNN`
keys, because they never pass through that parser.

### Errors

Every editor route answers `{ok: false, error: <code>, message: <text for a person>}` with the status
below. A client can act on several of these rather than only showing the message.

| Code | Status | When | What a client should do |
|---|---|---|---|
| `NOT_PAIRED` | 409 | Should not occur: the engine signs rules for its own runtime with a key it creates itself (`haiSigningKey`). The code remains in `editorSubmission` | A bug if seen |
| `NO_SUMMARY` | 503 | The engine's runtime has no summary yet, usually just after a restart | Retry in a few seconds. It resolves itself |
| `DRAFTS_FULL` | 409 | All drafts together would pass the byte budget | Offer to delete a draft; do not retry |
| `DRAFT_TOO_LARGE`, `BODY_TOO_LARGE`, `TOO_LARGE` | 413 | Over the size limit for a draft, a request body or a value | Shorten it. The limits are in the `limits` map of `state` |
| `DRAFT_NOT_FOUND`, `RULE_NOT_KEPT`, `NOT_FOUND` | 404 | The draft or rule is gone, usually deleted elsewhere | Refresh from `state`; do not retry |
| `NO_SUCH_ROUTE` | 404 | No such editor route | A bug in the client |
| `NO_SUCH_OPERATION` | 400 or 404 | The operation is not one the hub can do with a rule | A bug in the client |
| `INVALID_JSON`, `EMPTY_BODY`, `INVALID_SHAPE`, `NOT_A_BUNDLE`, `NOT_A_DRAFT` | 400 | The body is not what the route expects | A bug in the client |
| `RULE_MISSING`, `NO_RULE`, `NAME_MISSING`, `NAME_TOO_LONG`, `INVALID_ID` | 400 | A required field is missing or malformed | Correct the field |
| `NOT_A_BOOLEAN`, `NO_VALUE` | 400 | An end point operation was given the wrong sort of value | Correct the value |
| `INTERNAL` | 500 | An unexpected failure. The message says what, and it is also in the hub log | Report it. Do not retry blindly |

**A non-JSON answer is not from HAI.** A 401 with HTML is Hubitat refusing the access token before any HAI
code runs: the token is wrong or missing. Treat that as an authentication failure, not an error from the
route.

The MCP server answers in JSON-RPC and carries its own errors; the Automation Map feed's failure shape is
in its own section at the end of this page.

`send` also takes the end point operations below (`cancel`, `setBoolean`, `trigger`, `setVariable`), with
`value`.

The editor page opens a rule from its address:
- `#open=<draft id>` opens a draft.
- `#rule=<rule id>` opens the draft holding that rule. A rule's app page on the hub links here.

## Rule end points

Rule Machine's local and cloud end points, for HAI rules. All are GETs on HAI Engine (the rule routes
take POST too; the body is not read), with its
access token, and all are open to anyone holding that token; every call is logged. Local base: `http://<hub>/apps/api/<engine app id>/endpoint/`; cloud base:
`https://cloud.hubitat.com/api/<hub UID>/apps/<engine app id>/endpoint/`. The page shows both.

| Route | Does | Runtime operation |
|---|---|---|
| `rules` | `{ok, at, rules: [{id, name, state, paused}]}`, from the runtime's last summary (503 `NO_SUMMARY` before the first) | none |
| `rules/<rule id>/run` | Run now: the steps run once, without the conditions (Rule Machine's Run Rule Actions) | `runNow` |
| `rules/<rule id>/stop` | Ends the rule's running runs, with their waits and delays (Rule Machine's Stop Rule Actions). The rule stays active. | `cancel` |
| `rules/<rule id>/pause`, `rules/<rule id>/resume` | Pause, Resume | `pause`, `resume` |
| `rules/<rule id>/boolean/true`, `.../false` | Sets the rule's Private Boolean; a change can start rules that trigger on it | `setBoolean` |
| `rules/<rule id>/trigger`, `rules/<rule id>/trigger/<value>` | Fires the rule's first `endpoint` trigger, with `<value>` as `%value%` (or `?value=`). The rule's conditions and re-run policy apply. A rule with no `endpoint` trigger does nothing, and its trace says so. | `trigger` |
| `variables/<name>/<value>` | Sets a hub variable, only if it is one chosen under **Approved hub variables** on the HAI Engine page, converted to its type (refused if it cannot hold the value) | `setVariable` |

- **How it travels:** each rule and variable route becomes the same signed request the editor's buttons send
  (`haiRuleSubmit`, signed with the engine's own key), with the value in the signed `rule` field. The
  answer, `{ok, op, ruleId, sentAt}`, means the request was handed over; the engine's runtime checks it, acts,
  logs it to the Logs page, and reports the result in its summary (`lastSubmit`). Some log lines say
  "HAI Runtime", the name of the engine's runtime part.
- **What they cannot do:** activate a rule, deactivate (Stop in the editor) or remove one, change which devices
  or variables rules may use. The one route on the engine that needs no audience secret is the read-only
  Automation Map feed (last section); nothing an end point does reaches it.
- **Values:** up to 1024 characters. A rule id's colon may be sent as `%3A`.
- **Errors:** 404 `NO_SUCH_OPERATION`, 400 `NOT_A_BOOLEAN` or `NO_VALUE`, 409 `NOT_PAIRED`, 413 `TOO_LARGE`.

## HAI rules from other apps

HAI's `RMUtils`: another app on the same hub lists and controls HAI rules with a location event, and gets the
answer as another. Each request carries HAI Engine's API token (below). On by default; a person may switch off **Let other apps on this hub run HAI rules** (under **Rule end points**) on the HAI Engine page,
and then every request is answered with `API_OFF`. The library `engine/public/hai_rule_api.groovy` (add it under Libraries
code, then `#include <namespace>.HAIRuleApi`, with the namespace from the library's own `library(...)` header) wraps
both events.

**Request:** `sendLocationEvent(name: 'haiRuleApi', value: <op>, data: <JSON>)`, where the JSON is
`{op, rule, value, from, id, token}`: `rule` is the HAI rule id, `value` a text, `from` the asking app's name (for the
log only; it is not proof of anything), `id` any text of yours that the reply carries back, and `token` HAI Engine's
API token.

**The token (required).** Any app on the hub can send a location event, so each request must
carry the API token shown, with a Copy button, under **Rule end points** on the HAI Engine page. It is 32 hex
characters, made once per install, and compared over its whole length. A request with no token or a wrong one is
**ignored**: no reply is sent, no rule is touched, and HAI Engine writes one warning to the Logs page for each
sender (by `from`) until the hub restarts. **Change HAI's access addresses** on the same page makes a new token,
and the old one stops working at once.

**Breaking change for existing integrations.** An app that sends no token is ignored, and waits for a reply
that never comes. To update it: copy the token from the HAI Engine page and add it to
the request's data as `token`. With the library, update `hai_rule_api.groovy` under Libraries code, add
`input 'haiApiToken', 'text', title: 'HAI API token'` to your app's page, and paste the token there; the
library sends it with every request.

| `op` | Does | Like `RMUtils.sendAction` |
|---|---|---|
| `list` | The reply's `rules`: `[{id, name, state, paused}]`, `state` being `active` or `disabled` (stopped) | `getRuleList` |
| `run` | Run now: the steps run once, without the conditions | `runRuleAct` |
| `stop` | Ends the rule's running runs, with their waits and delays; the rule stays active | `stopRuleAct` |
| `pause`, `resume` | Pause, Resume | `pauseRule`, `resumeRule` |
| `setBoolean` | Sets the Private Boolean; `value` is `true` or `false` | `setRuleBooleanTrue`, `setRuleBooleanFalse` |
| `trigger` | Fires the rule's first `endpoint` trigger with `value` as `%value%`; its conditions and re-run policy apply | none |

**Reply:** `haiRuleApiReply` location event, value the request's `id` (or its `op` without one), data
`{ok, op, rule, id, from, code?, message?, rules?}`. Subscribe with `subscribe(location, 'haiRuleApiReply', 'handler')`;
every subscriber hears every reply, so match on `id`.

- **Checks:** the engine's runtime handles `run`, `stop`, `pause`, `resume`, `setBoolean` and `trigger` exactly as the same
  buttons in the editor (`rtRuleOp`): a rule that is not active or stopped answers `NOT_AVAILABLE`. Nothing
  activates, deactivates, changes or removes a rule this way, and no hub variable is set.
- **`run` answers whether it ran.** `ok: true` means a run started, or was queued behind one that is still
  going. When the rule declines the run, the reply is `ok: false` with `NOT_AVAILABLE` and a `message` saying
  which of the five reasons it was: the rule is paused, the rule is stopped, it is already running and set to
  ignore a new run, its queue is full, or it is already running as many times at once as it allows. This is
  the one check a caller cannot make for itself, because pausing is not visible in `list`'s `state` - a paused
  rule is still `active` there, with `paused: true` beside it.
- **Log:** each request is written to the hub's Logs page ("HAI Runtime: <from> asked to <op> <rule>: done") and to the engine's own log (`appApi`).
- **Codes:** a request without the right token gets no reply at all, so `NO_TOKEN` and `WRONG_TOKEN` appear only
  in the log. Otherwise `API_OFF`, `UNKNOWN_OPERATION`, `NO_RULE`, `NOT_A_BOOLEAN`, `TOO_LARGE` (value over 1024 characters),
  `NOT_AVAILABLE`, and whatever the rule app answers (`NO_ACTIVE_RULE`).
- **Between HAI rules** the `haiRuleControl` and `haiRuleInvoke` events stay internal; use `haiRuleApi` from
  other apps.

## MCP server

Endpoint: `/apps/api/<engine app id>/mcp`, over JSON-RPC (MCP protocol versions 2024-11-05, 2025-03-26
and 2025-06-18).

| Tool | Arguments | Does |
|---|---|---|
| `hai_describe` | `request` (optional) | Returns the rule format guide, the command catalogue, and the devices, modes, hub variables and rules available |
| `hai_propose_rule` | `request`, `rule`, `requirements` — all required | Checks, reads back and simulates a rule, and stores it as a proposal. It never activates anything. |
| `hai_render_rule` | `rule` — required | The read-back of a rule, alone |
| `hai_get_rule` | `ruleId` — required | A rule's current definition, its read-back, and its state on the hub, including whether that definition is the version the hub runs. Read-only. To change a rule, propose a new version with the same id; a person runs it from the editor. Deleting stays with a person, on the runtime page. |

**The tool list is not a security boundary, and must not be read as one.** Every tool above is draft
only, and none of them activates a rule, commands a device or deletes anything. That is a property of
the tools, not of the credential. `/mcp` is served by HAI Engine with **the same `access_token` as the
rest of the app's endpoints**, so the token alone is not the boundary. What separates the routes is the
audience secret (`aud`, `HAI_ROUTE_AUDIENCE`): `/mcp` takes the `mcp` audience, and `/editor/api/save`,
`/editor/api/send` (the path that submits a rule to the runtime) and the rule end points refuse it with
403 `WRONG_AUDIENCE`. Give an MCP client the token and the `mcp` secret only; whoever holds the `editor`
or `endpoint` secret as well can call those routes directly, whatever the tool list says.

What still holds regardless: submission is signed and replay-checked, and activation is reviewed on the
hub, which is where the device fence and the risky-operation rules live. Those protect the hub from a
bad rule. They do not make a shared token safe.

**How clients learn the rule format:** the guide `hai_describe` returns is
`engine/app/author/author_system.txt`. The deploy puts it in the hub's File Manager as
`hai-author-guide.txt`, and HAI Engine serves it only if its SHA-256 matches the build. A test fails if the
guide misses a trigger, condition or action type the schema has.

## What a consumer may rely on

Automation Map consumes three things from HAI, and pins capability ids by string. This section says what
is a contract, what is content, and how a change is signalled.

### Capability ids are a contract

**An id never changes meaning and never silently disappears.**

- Ids are **stored**, not derived on every build. `engine/app/am/capability_ids.json` maps each
  row of the parity tracker to its id, and the slug is minted **once**, when the row first appears.
- **Rewording a description does not change its id.** Move the row's text to the new key and keep the
  id. `TestAutomationMap` refuses a reworded row that does not carry its id across, naming the row.
- **An id that is withdrawn is retired explicitly**, in the `retired` map, pointing at the id that
  replaces it or at `null` when nothing does. A published id that simply vanishes is a **test failure**,
  not a silent change.
- The replacement publishes the old id as **`formerIds`**, so a consumer following a rename does it in
  one step and needs no coordination with us.

These are enforced, not merely intended: a test constructs a vanished id and asserts the failure, then
retires it and asserts both that the failure clears and that `formerIds` carries it.

### Two hashes, because they answer different questions

| Field | Moves when | Use it to |
| --- | --- | --- |
| `capabilitiesHash` | **any** content changes - a description, an evidence date, a status | decide whether to re-read the list |
| `capabilityIdsHash` | **only** when the set of ids changes - one added or withdrawn | decide whether anything you pin might have moved |

A consumer that pins ids should watch `capabilityIdsHash`. `capabilitiesHash` moving is routine and
means nothing about identity.

### What is content, and may change freely

Everything else: `feature` text, `status`, `note`, `evidence`, `rm51`, `hai`, node and edge contents,
flows and their labels. Read these; do not pin them. A description is a label for people, and it is
improved whenever it is wrong.

### What is not promised

- **Node and edge ids** are hub-local: app ids, device ids and variable names. They are stable only for
  as long as the hub is.
- **The feed's shape** may gain fields. A consumer must ignore fields it does not know. It will not
  lose a field without the contract version moving.
- **`hai.am/1`** is the contract version. A breaking change to the shape gets a new one.

### If we break it anyway

Say so rather than working around it. A construct that reports unmapped, or lands on a capability that
looks wrong, is worth more as a report than as a patch on the consumer's side.

## Automation Map feed

`GET /apps/api/<HAI Engine app id>/automation-map?access_token=<token>`, served by **HAI Engine (Preview)**
with its access token. It is read-only: it builds the answer from the registry's rule documents and changes nothing.
It is the one engine route that needs no audience secret (`aud`), because a sibling app already consumes it
(`haiAudienceOk`). Automation Map does not call it: it reads the Engine's
`hai-am-state.json` from File Manager, or the published `hai-capabilities.json` (pinned by `FEED_LOCK.json`) when
the hub has no HAI Engine.

**Calling it from an app on the same hub:** use `http://127.0.0.1:8080/apps/api/...`, not the hub's LAN
address. An app calling the hub's own LAN IP on port 80 does not get through (the request times out and the
call returns 408); the loopback address on port 8080 is the hub's own web server. This applies to every
endpoint on this page, and to a rule's own `http` step calling back into the same hub.

Each HAI rule is a child app of type **HAI Rule Container (Preview)**, a child of HAI Engine (Preview), so its node id is its installed app id. Contract `hai.am/1`, in
Automation Map's own graph shape:

```
{ "contract": "hai.am/1", "engine": "HAI-1", "haiVersion": "HAI-1/<build>", "generatedAt": <epoch ms>,
  "nodes": [...], "edges": [...], "flows": { "a<rule app id>": [steps] },
  "capabilities": [...], "statusMeanings": { "Runs": "...", "Format": "...", "Partial": "...", "Missing": "..." },
  "capabilitiesError": "..."   // present ONLY when the capability list could not be loaded }
```

### Two properties of the feed that are deliberate, and look like defects

**The feed is always a complete document.** Every rule that is not removed appears, with a node and a
flow, whether or not anything about it changed. A consumer may therefore treat what is absent from a
response as gone. There is no delta form, and if one is ever added it will carry an explicit marker and
will not imply absence — Automation Map's merge strips its own placeholder edges, applies the engine
tag, the editor link and the paused flag, and keys flows, all per rule *present in the response*, so a
rule quietly left out of a response would draw as an anonymous app with no steps and nothing would
report it. Pinned by a test; assembly may be incremental, the served artefact may not.

**Device names in the feed are the hub's current names, not the names stored in the rule.** Before a
rule's steps are rendered, every device reference's name is replaced with that device's name as the hub
reports it today (`tlFreshNames`). So a rule written two years ago whose lamp has since been renamed
still draws correctly on a map. The consequence, which has surprised both sides of this interface: a
device reference's authored name in the rule document is **not** an input to the feed, and a name shown
on a consumer's map can differ from the one a person sees in the HAI editor for the same rule. That is
the design, not a defect in either system, and it means the feed cannot be used to learn what a rule
calls its devices.

### What may change without warning, and what forces `hai.am/2`

Read the feed **tolerantly**: ignore fields you do not know, and do not fail on them.

| Change | Contract |
|---|---|
| A new field on the answer, a node, an edge or a flow step | Additive. Stays `hai.am/1`. Ignore what you do not use |
| A new node `group`, edge `kind`, capability `status` or flow step `kind` | Additive. Stays `hai.am/1`. Treat an unknown value as opaque rather than an error |
| A field removed or renamed, a field changing type, an id scheme changing, or an existing value changing meaning | Breaking. Becomes `hai.am/2`, and `hai.am/1` is served alongside it for at least one release |

### When the feed cannot answer

An empty `capabilities` list **always means "no data", never "nothing is supported"**. Do not draw a
comparison from it, and do not report zero parity: say the feed could not answer, and show
`capabilitiesError`.

**If the feed itself fails**, it answers 500 with JSON, never a hub error page:

```
{ "contract": "hai.am/1", "engine": "HAI-1", "haiVersion": "...",
  "error": "FEED_FAILED", "message": "<what went wrong>" }
```

A 401 is Hubitat's own, not HAI's, and is HTML: it means the token is wrong or missing, and no HAI code
runs. Treat a non-JSON answer as "the token is wrong" rather than as a feed failure.

`capabilitiesError` is present only in that case. It means the capability file in the hub's File Manager is
missing, or is not the one this HAI Engine was built with, which happens when the app is deployed without
the file being uploaded to match. `nodes`, `edges` and `flows` are still correct and still worth drawing.

- **Engine:** `engine` is the rule engine's name and generation, `HAI-1`. A later engine would be `HAI-2`
  and would say so here; `haiVersion` is that name followed by the build. Name the engine HAI-1 in
  anything you publish about it.
- **Rules:** every rule the runtime keeps, paused and stopped included.
- **Node ids:**
  - a device is `d<hub device id>`, from a rule's `dev:<id>`;
  - an app is `a<installed app id>`, each HAI rule's app included;
  - a hub variable is `v<name>`, with the exact name, a trailing period included;
  - a variable connector (a device standing in for a hub variable, read from `getAllGlobalVars()`) is drawn as
    that hub variable: a rule reading it reads the variable, and a rule commanding it writes the variable;
  - a rule variable is `a<rule app id>:<key>`, its `rv:<key>` without the prefix.
- **Nodes:** `{id, label, group}`, `group` being `app`, `device`, `hubVariable` or `localVariable`.
  - A HAI rule's node adds `engine: "HAI"`, `status` and `disabled`.
  - `status` is `active`, `paused` or `stopped` (Deactivated on the runtime page, Stop in the editor), or
    `disabled` for a document with `enabled: false`. `disabled` is true when stopped or disabled.
  - Paused comes from the runtime's latest summary, refreshed seconds after a pause or resume.
  - `url` is the rule's own page on this hub, hub-relative: `/installedapp/configure/<rule app id>`. Opening it
    forwards to that rule in the HAI editor. It carries no credential on purpose: the feed must stay safe to
    hold, so the editor's own token, which can edit drafts and send rules, is never published here. The hub's
    page is behind the hub's login and adds the token itself.
- **Edges:** `{from: "a<rule app id>", to, kind}`, one per rule, target and kind, drawn from reference ids
  only. The kinds:

| Kind | Meaning |
|---|---|
| `trigger` | A trigger fires on the device |
| `constraint` | An Only if, a required expression, an `if` or a wait reads the device. `unused: true` when only disabled steps read it |
| `monitor` | A value is taken from the device: a comparison value, a Set Variable source, a capture |
| `action` | The rule commands the device. `commands` lists the commands; `stateful` is true for lasting state (on, level, colour, lock, restore), false for a moment (push, refresh, speak, custom commands) |
| `read` | A hub or rule variable is read. `usageRole`: `trigger`, `required-expression` (Only if and required expression), `condition` (an `if`, a repeat or a wait), or `value-source` (including a `%name%` in an HTTP, ping or file step's text) |
| `write` | A hub or rule variable is set (Set Variable, a Rule Function's result, an HTTP answer or a ping result) |
| `runs`, `pauseResume`, `cancelTimedActions`, `setspb` | Rule links: run rule or Run Actions, pause or resume, cancel timers, set Private Boolean. `to` is the HAI rule's app, or the Rule Machine rule's own app id |

- **Disabled steps:** a disabled trigger or step draws no edge, except a constraint, marked `unused`.
- **References not drawn:**
  - a mode;
  - the device that fired the trigger;
  - another rule's paused state;
  - a HAI rule the runtime does not know.
- **Flows:** one list per rule, in order.
  - Each step is `{kind, label, devices, ctrl, cond, ruleTargets, variableField}`.
  - `kind` is `trigger` (one per trigger), `required` (Only if, then any required expression), or
    `action`.
  - `label` is the read-back's wording, and `devices` the devices' current names.
  - `ctrl` is null, or `if`, `elseif`, `else` or `endif`; `cond` is the condition on `if` and `elseif`.
  - Failure steps follow the actions as an `if` block on "a step fails".
  - A repeat opens with `repeat (loop)`, its condition in `cond`, and closes with `end repeat`; steps
    done later open with their read-back line and close with `end later`. A step kind this runtime does
    not know that holds steps opens with `<kind> (loop)` and closes with `end <kind>`.
  - HTTP, ping and file steps touch no device. A wait for events reads each event as a constraint.
  - `ruleTargets` lists the app nodes of a rule-link step, and `variableField` the variable node a Set
    Variable step writes.
- **Capabilities:** `[{id, area, feature, status, note, rm51, hai}]`.
  - One entry per row of the parity tracker's Rule Machine sections, then what HAI does beyond Rule
    Machine.
  - `area` is `trigger`, `condition`, `action`, `variable`, `control` or `option`.
  - `status` is `Runs`, `Format`, `Partial` or `Missing`. `note` is the gap for `Partial` and `Format`,
    null otherwise. `statusMeanings` at the top level gives a sentence for each of the four, so a reader
    renders HAI's own wording rather than keeping a copy of it that drifts.
  - `evidence` says how a `Runs` status is known: `{level: "hub", date: "YYYY-MM-DD"}` when the feature has
    been seen working on a hub on that date, or `{level: "simulated", date: null}` when it is built, checked
    and simulated but nobody has watched it run. It is null for the other three statuses, which claim no
    running. The generator refuses to publish a `Runs` row without it.
  - **`Runs` is not the same claim as `Runs [hub]`.** A reader presenting a parity figure should say which
    it is counting, and give both: how many are built, and how many have been seen running on a hub. The
    current pair is generated here:

<!-- hai-status:begin -->
Of Rule Machine 5.1's **140 capabilities**, HAI has **implemented 140**: **140 tested** on the hub and 0 built but not yet tested. 0 are scoped but unbuilt. HAI's own 31 capabilities are counted separately.

Every row, with its evidence, is in the parity tracker. These figures are generated from the same file as the hub's capability feed; do not edit them by hand.
<!-- hai-status:end -->

  - `rm51` names the tracker row. `hai` lists the schema kinds the row names.
  - The 31 rows under `8. HAI only features` also carry **`rmVerdict`** and **`rmNote`**: what it
    costs to carry a rule built on that feature *back* to Rule Machine. `none` means Rule Machine has no
    way to reach the outcome at all; `workaround` means it is reachable by rebuilding the rule
    differently; `partial` means Rule Machine has something close but weaker. `rmNote` says which and
    why. 24 of the 31 are `none`, 4 `workaround` and 3 `partial`.
  - **These rows are the lock-in surface.** They are never counted in the parity figure: a superset
    cannot demonstrate parity, and anything built on them does not move back.
  - The list is generated by the engine's Automation Map suite (`TestAutomationMap`, with `HAI_CAPS_WRITE=1`)
    into `engine/app/am/capabilities.json`. Ids are kept in `engine/app/am/capability_ids.json`: reword a key there, never an
    id.
  - `engine/upload-editor-page.ps1` puts the file in the File Manager as `hai-am-capabilities.json`, and
    HAI Engine serves it only if its SHA-256 matches the build. Otherwise `capabilities` is empty and
    `capabilitiesError` says why.
- **Maintenance:** `TestAutomationMap` fails when a trigger, condition or action kind has no mapping in
  `AM_FIELDS` (`engine/lib/hai_am.groovy`), or when no capability names it. The golden rules and feed are
  test fixtures; `HAI_AM_WRITE=1` regenerates the feed.
