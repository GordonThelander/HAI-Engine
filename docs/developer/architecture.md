# How HAI is built

Applies to HAI Engine version 0.5.87. The wider picture, with Automation Map, is in
[architecture-current.md](../architecture-current.md).

## The apps

HAI is one parent app, one child app per rule, and an optional migration app. Authoring and running live in
the same parent, so there is one device picker and one permission.

| App | Role | Reachable from outside the hub |
|---|---|---|
| HAI Engine (Preview) | The editor, the in-hub AI assistant and the MCP server; and the runtime: the list of rules on the hub, activation, and the device, variable and mode permissions. | Its OAuth endpoints, with its access token: `/editor`, `/editor/api/*`, `/mcp`, the rule end points `/endpoint/*`, and the read-only Automation Map feed `/automation-map`. Every route carries an audience secret (`aud`) except the feed (`haiAudienceOk`). |
| HAI Rule Container (Preview) | One child app per rule. It subscribes to what the rule needs, runs it, and keeps its run history. A rule keeps firing while the engine is being deployed. | No. |
| HAI Migration (Preview) | Optional. One child app of the engine holding Rule Machine migration: the migration page, Automation Map's decode (`hai_ham`), the converter (`hai_rm_map`) and the steps (`hai_migration`). It asks the engine for what only the engine holds through three parent methods, `haiMigrationFacts`, `haiMigrationRecord` and `haiMigrationCall`; the engine never calls it. The engine keeps `hai_ham` too, for the authoring rule index. | Its own OAuth endpoints, with its own access token: `/migration` and `/migration/api/*` (`state`, `apply`, `assess`, `plan`, `dryrun`). Its token reaches nothing else, so there is no audience secret. |

**The migration contract.** HAI Migration (`engine/app/hai_migration_app.groovy`) calls the Engine through
`haiMigrationFacts`, `haiMigrationRecord` and `haiMigrationCall`. The call's ops are `haiRuleOp`,
`activateStopped`, `rmPausedNow`, `importRule` and `dryRun`. The facts carry `contract: MIGRATION_CONTRACT`
(now 2), and the app refuses to migrate on a mismatch. Any change to that seam bumps `MIGRATION_CONTRACT` in
the Engine and `EXPECTED_ENGINE_CONTRACT` in HAI Migration together.

**Reaching HAI Migration's API is not the same as reaching the Engine's.** It is a separate installed app
with **its own app id and its own access token**. An Engine token on an Engine app id will not answer
`/migration/api/*`. It returns 401, which reads like a broken endpoint rather than the wrong credential. Both
differ per hub. To find them:

- **app id**: the installed app list, or the page link on the migration page.
- **token**: the migration page shows it, the same way the Engine page shows the Engine's.
  `GET /installedapp/statusJson/<migration app id>` also carries it. That response holds more than one UUID,
  so confirm which by a request that returns 200 rather than by position.

The full read is then `GET /apps/api/<migration app id>/migration/api/state?access_token=<migration token>`.
It can take tens of seconds on a busy hub and returns a few hundred KB.

**Its rows are rules, not devices.** Each row is keyed `rmId`, a Rule Machine rule id. **Rule ids and device
ids share one number space on Hubitat**, so searching the payload for a device id can match an unrelated
rule. A disabled **device** appears only through the rules that use it, as a row warning
`{kind: 'disabled', names: [...], blocking: false}` naming it. A device no rule references does not appear
at all.

HAI Migration reads Automation Map's decode from `ham-decode-detail.json` (production channel) or
`ham-decode-detail-dev.json` (Dev channel), and the same for the summary file. When both exist it uses the
one written by the newer Automation Map, compared on `instance.appVersion` segment by segment. When only one
exists, it uses that one. `/migration/api/state` reports the files and versions it read under `source`, and
the page says which detail file it read.

## How a rule gets from the editor to the hub

- **Put on hub** posts `send` to the editor API. `editorSubmission` signs the rule with the engine's own key
  (`haiSigningKey`, created on first use), and the engine hands it straight to its runtime in the same
  request (`runtimeSubmitBody`). With one app, the handover is a call, not a message.
- `rtCheckSubmission` checks the signature, an age under two minutes, and no replay (the last 16 signatures
  are kept). The rule is kept as a candidate and activated at once (`runtimeAutoActivate`). Pressing Put on
  hub is the decision, and nothing asks a second time. The activation review still refuses a rule naming a
  device that is not selected, or a command its driver does not offer, and says which.
- Other apps can send the same signed request as a `haiRuleSubmit` location event, which the engine checks
  identically.

## Messages between apps

The engine is the parent of the rule apps, so it calls them directly (`haiSetup`, `haiStage`,
`haiLifecycle`, `haiRuntimeState`, `haiEditorLink` and others, through `runtimeChildCall`). A rule app never
calls its parent. Everything else uses Hubitat location events, delivered after the sender's execution ends,
because two single-threaded apps calling each other can deadlock:

| Event | From, to | Carries | Checked by |
|---|---|---|---|
| `haiRuleInvoke` | One rule app to another | Which rule to run, the caller, the run, and the call depth | The target checks the name is its own, and the depth is within the limit. |
| `variable:<name>` | Hubitat to rule apps | A hub variable's new value | Admitted like a device report. |
| `haiRuntimeSummary` | Engine to anything listening | Each rule's name, state, version and recent runs; the last request's result; which hub variables rules may change | Display only. The engine keeps its own copy in state, because Hubitat does not deliver an app's own location event back to it. |
| `haiRuleApi` / `haiRuleApiReply` | Another app to the engine and back | List, run, stop, pause, resume, set the Private Boolean, trigger | The engine's API token. See "HAI rules from other apps" in [api.md](api.md). |

## What the engine passes to each rule app

`haiSetup(devices, allow, purposes, options)` gives the rule app the devices it may hold and the allow-list.
`options` carries `writableVariables` (the hub variables rules may change), `rules` (the other rules on the
hub), `paused` and `modeAllowed`. `purposes` is empty: devices carry no commissioned purpose. The rule app
never decides any of these for itself.

## Safety layers for devices

1. **The device picker on the HAI Engine page** (**Approved devices**): a rule can only use a device
   chosen there, with any command its driver offers. This is the one permission HAI asks for. A new install
   starts with every device approved; the page says so and how to narrow it.
2. **The activation review:** a rule naming a device that is not selected, or a command its driver does not
   offer, is not activated, and the refusal says which.
3. **The check before every send:** the rule app checks the device is still allowed just before sending, in
   the same execution (`rtChildAllowed`).

**Where validation lives.** Rules are validated in the engine, in the activation review (`rtReview`), before
anything is staged. The Rule Container does not carry `hai_validator`. Its commit loads only the staged
document whose hash matches the one the engine stamped, with every device still on the allow-list. The
simulator still validates on every load through the core's `validate` port.

There is no "test devices only" fence, no commissioning of locks and sirens, and no per-send approval.

## Running a rule

The same core (`engine/lib/hai_core.groovy`) runs in the simulator and on the hub. The hub adapter
(`hai_runtime.groovy`) gives it these through a port of closures:
- the hub clock;
- subscriptions;
- timers stored as absolute deadlines;
- device reads and sends;
- hub variables;
- the calls between rules.

**Proving the hub matches the simulator:** conformance tests replay the simulator's golden scenarios through
the hub adapter, and require the same commands, run outcomes and confirmations.

- **Commands** go through a confirmation ladder: requested, dispatched, then confirmed by the device's
  report or a read of its state. A command whose result cannot be observed is recorded as unknown, and
  never retried on that basis.
- **Waits and "and stays" intervals** are stored as absolute deadlines. After a hub restart each waiting
  run applies its `onRestart` policy.
- **Hub variables** are handled as events on the variable's id (`hv:<name>`) with one attribute, `value`.
  Their triggers therefore get the device machinery's change detection, "and stays" and freshness. Each
  rule app registers the variables it uses (`addInUseGlobalVar`), so the Hub Variables page lists it, and
  follows a rename (`renameVariable`).

## Keeping the hub light

The hub is a constrained device. What HAI does to stay small:
- **Shrunk builds:** each app is assembled from the libraries with `assemble.py --shrink`. It leaves out
  library methods and constants no code refers to (a name in a comment does not count), comment lines,
  blank lines and indentation. It also drops `private`, trailing comments, column-alignment spaces and
  `(Map)`/`(List)` casts that are not straight after a parenthesis. Every suite also runs on the shrunk
  builds, so each transform is tested in the shape the hub gets. `deploy-hub.ps1` compiles each shrunk build
  with Groovy 2.4, the hub's version, before it is deployed (`compile_check.groovy`). Newer Groovy accepts
  shapes the hub refuses: `(x) as int > 0` compiles on newer Groovy and fails on the hub, read as a generic
  type. Write `((x) as int) > 0`.
- **Size checks:** `deploy-hub.ps1` warns when a build passes an advisory size. This is a guardrail, not a
  measured hub limit. It refuses a deploy when free hub memory is low, because every code save costs
  Metaspace until the hub restarts.
- **The editor page is a file, not code:** it lives in the hub's File Manager as `hai-editor.html`, with
  its tables in `hai-editor-specs.json` and the AI guide in `hai-author-guide.txt`, put there by
  `upload-editor-page.ps1`. HAI Engine serves it only if its SHA-256 matches the one it was built with. The
  editor's fixed tables are generated into `engine/app/editor/specs.json`; a test fails if that file is
  stale.
- **A light watchdog:** every 15 minutes (`haiWatchdog`, cron `17 7/15 * * * ?`) the engine's runtime makes
  one small call per rule app. It reads a rule in full only when something disagrees, and rebuilds its
  summary only when something changed.
- **Few deploys:** every code save costs the hub memory until it restarts, so changes are deployed in
  batches.

## Where things are

| Path | What |
|---|---|
| `engine/lib/` | Shared libraries: schema, validator, logic, core, simulator, runtime adapter, timeline, bundle, editor, authoring, migration |
| `engine/app/hai_engine_app.groovy` | HAI Engine, including the runtime |
| `engine/app/hai_migration_app.groovy` | HAI Migration |
| `engine/app/editor/editor.html` | The editor page, uploaded to File Manager as `hai-editor.html`. The engine embeds only its SHA-256 at build time |
| `engine/m5/hai_rule_app.groovy` | HAI Rule Container, one per rule |
| `engine/deploy-hub.ps1` | The reviewed deployment script: backup, optimistic lock, read-back and hash check |
| `dist/` | The three shrunk apps Hubitat Package Manager installs |
