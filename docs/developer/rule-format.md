# Rule format reference (`hai.rule/1`)

Applies to HAI Engine version 0.5.87.

**Engine: HAI-1.** `hai.rule/1` is HAI-1's rule format. A later engine generation would carry its own
schema id; this page always describes the one the running hub reports.

A HAI rule is one JSON document. The editor, the in-hub assistant, the MCP server and the importer all
produce this document, and the validator, read-back, simulator and runtime all read it. This page lists
every field. The schema itself is `engine/lib/hai_schema.groovy`; a test fails if this page misses a
trigger, condition, action, command or rule setting that the schema has.

**General rules**
- An unknown field is refused, never ignored: an ignored field would be intent the engine silently dropped.
- Never write `null`. Leave the field out instead.
- Numbers are plain decimals.
- Every field that holds a number has a range, listed below. Values outside it are refused when the rule is
  checked, not discovered when it runs.

## The rule

| Field | Type | Default | Meaning |
|---|---|---|---|
| `schema` | string | required | Always `hai.rule/1`. |
| `id` | string | required | The rule's identity on the hub. Running a rule with the same id replaces that rule. |
| `name` | string | required | Shown in the editor, in the Apps list and under a device's In Use By, as "[HAI] <name>". |
| `description` | string | none | Notes for people. |
| `refs` | object | required | Every device, variable, mode and rule the rule uses, by key. See References. |
| `triggers` | list | required | What starts the rule. Any one trigger starts it. |
| `conditions` | condition | none | Must be true for the steps to run ("Only if"). |
| `actions` | list | required | The steps, in order. |
| `rerunPolicy` | `restart` \| `ignore` \| `queue` \| `parallel` | `restart` | What happens when a trigger fires while the rule is still running. |
| `queueLimit` | integer | 1 | With `queue`: how many runs may wait. |
| `parallelLimit` | integer | 1 | With `parallel`: how many runs may happen at once. |
| `onUnknown` | `block` \| `proceed` | `block` | What happens when a condition cannot be checked (a device offline, a reading too old). |
| `ifUnknown` | `stop` \| `otherwise` | `stop` | What an `if` does when its condition cannot be checked. `stop` takes no branch and fails the step with `DECISION_UNKNOWN`, so `errorPolicy` decides whether the run ends (`stopRun`, the default), runs `onFailure`, or carries on after the `if` (`continue`); `otherwise` treats that branch as not met, so the next `elseIf`, then the `otherwise` (ELSE), is tried, as Rule Machine does. A copy of a Rule Machine rule gets `otherwise`. |
| `errorPolicy` | `continue` \| `stopRun` \| `failurePath` | `stopRun` | What happens when a step fails. |
| `onFailure` | list of actions | none | With `failurePath`: the steps to run when one fails. |
| `onCancel` | list of actions | none | The steps to run when a run is **cancelled** - by the required expression turning false with `cancelOnFalse`, or by another rule cancelling this one's timers. Without it a cancelled run stops where it stands, leaving whatever it had already set. It does not run for a fade or flash (those are background runs), and it cannot re-enter itself, so a gate that stays false cancels once. A step that fails inside it ends the run. |
| `enabled` | boolean | true | Whether the rule is switched on when it is activated. |
| `initialEvaluation` | boolean | false | Run once when the rule is activated, as if triggered. |
| `conditionTiming` | `atTrigger` \| `atRunStart` | `atTrigger` | When `conditions` are read: when the trigger is admitted, or when a (queued) run starts. |
| `requiredExpression` | condition | none | Triggers count only while this is true, judged as it stood just before the event (a schedule or "and stays" trigger reads it at that moment; "and stays" checks it when the interval starts and when it ends). Run now is not held back. |
| `cancelOnFalse` | boolean | false | With a required expression: when it stops being true, runs under way are cancelled, with their waits and delays. Checked after every event and before every step, so it also holds after a restart. |
| `pauseMode` | `finish` \| `hold` | `finish` | When the rule is paused, runs already going finish, or (`hold`) wait where they are and carry on from there when it is resumed. |
| `logEvents`, `logTriggers`, `logActions` | boolean | false | Rule Machine's logging options: one short info line on the hub's Logs page per event the rule receives, per run starting and ending, and per step it carries out (a command, a variable set, a mode, a rule call, a wait, an HTTP request). The run timeline records the same whether or not these are on. |

## References

Every reference has a key, used by the rest of the rule, and a `kind`.

| Kind | Fields | Notes |
|---|---|---|
| `device` | `id` (`dev:<hub id>`), `name`, `room`, `driver`, `capabilities`, `required` | `capabilities` lists what the rule needs from the device. On import to another hub, the device is found by these, never by id. |
| `variable` | `id`, `name`, `type`, `scope`, `required`, `initial` | `scope: "hub"` is a Hubitat hub variable, id `hv:<name>`. `scope: "rule"` belongs to this rule alone and keeps its value between runs, id `rv:<key>`, and may give its starting value in `initial`. `type` is `string`, `number`, `boolean`, `date`, `time` or `datetime`. |
| `mode` | `name`, `required` | A hub mode. |
| `room` | `name` | A room. |
| `rule` | `id`, `name` | Another HAI rule on the hub. |
| `rmRule` | `id`, `name` | A Rule Machine rule on the hub, by its app id. |
| `self` | none | This rule. |
| `eventDevice` | none | The device whose event started the run (Rule Machine's "the device that fired the trigger"). Usable as a command's device; a run started without a device event sends nothing to it. Commands with a risk class cannot use it. |

**Private Boolean.** Every rule has one, a boolean rule variable with id `rv:privateBoolean` that starts
true. To read it or start on it, declare it: `{"kind": "variable", "id": "rv:privateBoolean", "name":
"Private Boolean", "type": "boolean", "scope": "rule", "initial": true}`. Any rule may set it with
`setRuleBoolean`. A rule variable's change can start the rule, through a `hubVariable` trigger on it.

## Triggers

Any trigger may carry `condition`, a condition read just after its event: the trigger counts only if it is true (Rule Machine's conditional trigger).

| Kind | Fields | Fires when |
|---|---|---|
| `deviceAttribute` | `ref`, `attribute`, `value` (optional), `op` (optional, see below), `discipline` (`transition` default, or `event`), `eventType` (optional: `physical` or `digital`) | The attribute changes (to `value`, or to a value satisfying `op`, if given). `event` fires on every report, even of the same value. With `eventType`, only events of that Hubitat type count: `physical` for a paddle pressed by hand, `digital` for a command from an app or the hub. |
| `threshold` | `ref`, `attribute`, `op` (`above` \| `below` \| `atLeast` \| `atMost`), `value` | The reading crosses the value. A first reading is not a crossing. |
| `duration` | `seconds` (1 to 86400), `predicate` (a condition) | The predicate becomes true and stays true, without a break, for `seconds` ("and stays"). |
| `button` | `ref`, `number` (1 to 100), `action` (`pushed` \| `held` \| `doubleTapped` \| `released`) | The button is used. Two pushes are two triggers. |
| `schedule` | `at` (`HH:mm`, `sunrise`, `sunset`, or `{"ref": <variable key>}`), `offsetMinutes` (-720 to 720, or `{"ref": <variable key>}`); optional `days` (`mon` to `sun`) or `date` (`yyyy-MM-dd` for one day, `MM-dd` for every year) | The time is reached, on a chosen day or date if given. A variable holding a time fires daily; one holding a date and time fires once, on that date. When the variable changes the schedule is armed again; with no value it waits. |
| `periodic` | `cron`: a Quartz cron string, `second minute hour day-of-month month day-of-week [year]`. Supports `*`, `?`, lists, ranges, steps (`*/15`), names (`MON`, `JAN`), and `L`, `L-n`, `nW`, `LW`, `nL`, `n#k`. Day of week counts Sunday as 1. | The schedule comes round. Every wall-clock time goes through the daylight-saving policy, like `schedule`. More than once a minute is allowed but warned (`PERIODIC_FREQUENT`). |
| `mode` | `to` (list of mode names, optional) | The hub's mode changes (to one of `to`, if given). |
| `hubVariable` | `ref` (a hub variable) | The hub variable's value changes. |
| `ruleInvocation` | none | Another rule runs this one with `runRule`. |
| `rulePaused` | `ref` (a rule or an `rmRule`), `value` (true, the default, or false) | That rule is paused (`true`) or resumed (`false`). A Rule Machine rule's pause is noticed within fifteen minutes: HAI Engine reads the paused state of the Rule Machine rules active HAI rules refer to once per watchdog pass, in one read of the hub's app list. |
| `hsmStatus` | `to` (optional list of `armedAway`, `armingAway`, `armedHome`, `armingHome`, `armedNight`, `armingNight`, `disarmed`, `allDisarmed`) | Hubitat Safety Monitor's status changes (to one of `to`, if given). The `arming...` statuses are the delayed arming (exit delay) states. |
| `hsmAlert` | `alerts` (optional list of `intrusion`, `intrusion-delay`, `intrusion-home`, `intrusion-home-delay`, `intrusion-night`, `intrusion-night-delay`, `smoke`, `water`, `rule`, `arming`, `cancel`) | Hubitat Safety Monitor raises an alert (one of `alerts`, if given). Every alert counts, even a repeat. |
| `locationEvent` | `events` (one or more of `systemStart`, `severeLoad`, `zigbeeOff`, `zigbeeOn`, `zwaveCrashed`, `sunrise`, `sunset`, `sunriseTime`, `sunsetTime`) | The hub reports one of those events. `sunriseTime` and `sunsetTime` are when the hub sets the next sun time; for sunrise or sunset with an offset, use `schedule`. |
| `endpoint` | none | Its end point is called: an HTTP GET to `/endpoint/rules/<rule id>/trigger` on HAI Engine, optionally with `/<value>`, which the run reads as `%value%` (Rule Machine's Local and Cloud End Point trigger). The rule's conditions apply. See `docs/developer/api.md`. |

## Conditions

| Type | Fields | True when |
|---|---|---|
| `all` | `of` (list of conditions), `rmGrouping` (optional) | Every condition in `of` is true. |
| `any` | `of` (list of conditions), `rmGrouping` (optional) | At least one is true. |
| `not` | `of` (one condition) | That condition is false. |
| `xor` | `of` (two or more conditions) | An odd number of them are true, as Rule Machine chains XOR: for two, one or the other but not both. Unknown if any is unknown. |
| `count` | `of` (two or more conditions), `min`, optional `max` | At least `min` of them are true, and no more than `max` when it is given: `min: 2` is "at least 2 of these", `min: 1, max: 1` is "exactly one of these". Every member is read. Unknown when the unknown members could still tip the answer either way. HAI only; Rule Machine has no equivalent. |
| `dayOfWeek` | `days` (`mon` to `sun`) | Today is one of the days. |
| `timeSince` | `ref` (a device), `attribute` (optional: without it, anything the device reports), `value` (optional, needs `attribute`), `op` (`atLeast`, the default, or `below`), `seconds` | At least (or less than) that long has passed since the device last reported the attribute, or that value of it. A value other than the current one is known only from events seen since the rule became active; before that it is unknown. It cannot be waited on (`TIME_SINCE_PLACEMENT`), because nothing reports the passing of time. |
| `dateRange` | `from`, optional `to`: `yyyy-MM-dd`, or `MM-dd` for every year (both the same form), or `{"ref": <variable key>}` | Today is that day, or between the two, inclusive. An every-year range may cross the new year (`12-01` to `01-31`). A variable with no date reads as unknown. |
| `deviceAttribute` | `ref` or `refs`, `match` (`any` \| `all`, needed with several devices), `attribute`, `op`, `value`, `freshSeconds` (optional), `unknownAs` (optional: `false`) | The device's reading compares true. With `freshSeconds`, a reading older than that counts as unknown. With `unknownAs: false`, a reading that cannot be read compares false instead of unknown, device by device, so `not` of it is true. This is Rule Machine 5.1's behaviour, and the converter sets it on every condition it builds. The editor keeps it as it is. |
| `variable` | `ref`, `op`, `value`, `unknownAs` (optional: `false`) | The variable compares true. An unset variable is unknown, not zero or empty; with `unknownAs: false` it compares false, as in Rule Machine. |
| `mode` | `ref` (a mode reference) or `value` (a mode name) | The hub is in that mode. |
| `timeWindow` | `from`, `to` (`HH:mm`, `sunrise`, `sunset`, or `{"ref": <variable key>}`), `fromOffsetMinutes`, `toOffsetMinutes` (a number or `{"ref": <variable key>}`) | The time is inside the window. Windows may cross midnight. A variable with no time makes it unknown. |
| `triggeredBy` | `triggers` (list of trigger positions, from 0) | The run was started by one of those triggers. |
| `rulePaused` | `ref` (a rule or an `rmRule`), `value` (true, the default, or false) | That rule is paused (or, with `false`, is not). |
| `hsmStatus` | `value` (a list of HSM statuses, as for the trigger) | Hubitat Safety Monitor is in one of them. Unknown when the hub has no HSM status. |
| `matches` is a comparison, not a condition type: any condition or trigger taking `op` may use it, and `value` is then a pattern. It finds the pattern anywhere in the text. Patterns are bounded **by shape and size, not by time**: at most 200 characters, no unbounded quantifier applied to a group that already has one (`(a+)+`, the catastrophic-backtracking form), and the text is truncated to 4000 characters before matching. A time cap is not possible inside Hubitat's sandbox - it needs a guarded `CharSequence` or a watchdog, and an inner class capturing a local compiles to `groovy.lang.Reference`, which the hub rejects. So the guarantee is that the known-pathological shapes cannot be saved or run and the input cannot grow without limit; it is not a promise that no pattern can ever be slow. A refused pattern makes the comparison false rather than stopping the rule. |
| `waitOutcome` | `value` (`event`, the default, or `timeout`) | How the most recent wait in **this run** ended: on the thing it was waiting for, or by giving up. Use it after a `wait` whose `onTimeout` is `continue`, to take a different path for each. Unknown outside a run - in a trigger predicate or a rule gate there is no wait to ask - so the ordinary unknown handling applies. It answers from the wait itself rather than from how recently a device reported, which a near-timeout report makes wrong. |

`rmGrouping: true` on an `all` or `any` is information only and changes nothing about how the rule runs.
The Rule Machine converter sets it on a group it built from an unbracketed mix of AND and OR, which Rule
Machine reads from the left as right-nested, `A AND (B OR C)` (see `docs/developer/rm-semantics.md`,
"Mixing AND and OR without brackets"). The editor shows a one-line note under such a condition, and drops
the marker when its join is changed.

**Device trigger with a comparison:** a `deviceAttribute` trigger may carry `op` (any comparison below except
`contains` and `isEmpty`) and `value`. It fires when the reading changes to a value that satisfies it; with
`"discipline": "event"` it fires on every report that satisfies it, as Rule Machine's comparison triggers do.
A crossing (fires once as the value passes a line) is the `threshold` trigger.

**Comparisons** (`op`, for `deviceAttribute` and `variable`): `is`, `isNot`, `above`, `below`, `atLeast`,
`atMost`, `contains` (text), `isEmpty` (no `value`; an unset variable is empty), `between` (`value` is
`[low, high]`, inclusive) and `notBetween`. The `value` may also be `{"ref": "<variable key>"}` or
`{"ref": "<device key>", "attribute": "<name>"}`, to compare with a variable or another device's reading, read
when the condition is. Either may carry `"offset": <number>` ("2 more than the hallway's temperature"); the
value is then a number, and unknown when what it reads is not one.

**Keypads and lock codes.** These are device attributes, so `deviceAttribute` covers them. A keypad
(SecurityKeypad) reports `securityKeypad`: `armed away`, `armed home`, `armed night` or `disarmed`; no `value`
means any change. A lock or keypad with codes reports the name of the code last used as `lastCodeName`; a
trigger on it with `"discipline": "event"` starts the rule each time that code is used, even twice in a row.

Logic is three-valued: a condition can be true
, false or unknown. By default an `if` whose condition is unknown takes
neither branch and fails the step (`DECISION_UNKNOWN`), so `errorPolicy` decides what follows; with `"ifUnknown": "otherwise"` that branch counts as not met and the
next `elseIf` or the `otherwise` runs, and the trace records `decisionUnknownSkipped` and
`decisionElseOnUnknown`. `onUnknown` decides what the rule's own `conditions` do.

## Steps (actions)

| Type | Fields | Does |
|---|---|---|
| `command` | `ref`, and or `refs` (up to 64 more devices), `command`, `args`, `approval` (accepted and ignored; kept so older stored and converted rules still load), `meterMs` (0 to 10000, optional), `onlyIfOn` (optional) | Sends a command from the operation catalogue (below). With `refs`, to each device in turn, `meterMs` apart; with `onlyIfOn`, only to devices whose switch is on. Each device is checked, confirmed and arbitrated as a single command would be. |
| `setVariable` | `ref`, then `value` (a literal), or `op` and `args` (below) | Sets a variable. A hub variable can be written only if it is chosen under **Approved hub variables** on the HAI Engine page; an Integer hub variable refuses a literal fraction. |

**Set Variable operations** (`op`, with `args` in order). Each argument is a literal (text has its `%values%`
filled in), `{"ref": key}` for a variable, or `{"ref": key, "attribute": name}` for a device reading.

| `op` | `args` | Gives |
|---|---|---|
| `set` | source | the source's value: copy a variable, read a device, or text with `%values%` |
| `add` | number | the variable plus the number |
| `math` | a, operator, b | `+ - * / %`; or, with no b, `negate absolute round random sqrt sin cos tan asin acos atan log toRadians toDegrees` |
| `hour`, `minute`, `epoch` | none | the current hour, minute, or time in milliseconds |
| `now` | format (optional, a Java date pattern) | the current date and time as text, `yyyy-MM-dd HH:mm` by default |
| `sunrise`, `sunset` | none | today's time as `HH:mm` |
| `length` | source | the number of characters |
| `remove` | source, text | the source without the text |
| `replace` | source, find, with | the source with each find replaced |
| `token` | source, delimiter, index | one part of the source split at the delimiter, counting from 0 |
| `lowerCase`, `urlEncode`, `urlDecode` | source | the source changed so |
| `compare` | a, comparison, b | true or false; comparison as in conditions |
| `and`, `or`, `xor` | a, b | true or false |
| `not` | a | true or false |
| `formatDate` | source, format (a Java date pattern) | a date, date and time, or time written in that format |
| `addTime` | source, amount, unit (`seconds`, `minutes`, `hours`, `days`) | the date, date and time, or time moved by the amount (negative moves back); a time wraps round midnight |
| `timeBetween` | from, to, unit | the whole number of units from one to the other (negative if `to` is earlier) |
| `file` | name | the text of a File Manager file, up to 1024 characters; a longer file, or none, fails the step |

An operation's result must suit the variable: numbers for `add`, `math`, `length` and the times, text for
the text operations, true or false for the last five. A whole-number (Integer) hub variable gets whole-number
arithmetic, as Rule Machine's Number does: fractions are cut toward zero. Division by zero fails the step.
Dates and times are read from `yyyy-MM-dd`, `yyyy-MM-ddTHH:mm[:ss][.SSS][offset]` (Hubitat's DateTime,
where a `9999-99-99` date means a time alone) and `HH:mm[:ss]`. Comparisons (`above`, `below`, `atLeast`,
`atMost`, in conditions and `compare`) take dates and times too; a date against a time of day is unknown.
| `wait` (`waitKind: "delay"`) | `seconds` (1 to 604800, or `{"ref": <variable key>, "unit": "seconds" \| "minutes" \| "hours"}`); optionally `modeSeconds` (`[{"ref": <mode key>, "seconds": 0 to 604800}]`), `randomSeconds` (1 to 604800), `cancelable` (true or false); `onRestart`. At least one of `seconds`, `modeSeconds` and `randomSeconds` | Waits for a time, worked out when the wait starts. In a mode `modeSeconds` names, that mode's length is used (0 is no delay); in any other mode, `seconds`, or no delay without it. `randomSeconds` adds a random time of up to that long. A length from a variable that is not a number in range fails the step (`DELAY_UNUSABLE`), so the wait is never silently skipped. A `cancelable` delay is ended by `cancelDelays`, and with it the rest of the run. |
| `wait` (`waitKind: "forEvent"`) | `ref`, `attribute`, `value`, `timeoutSeconds` (1 to 86400), `onTimeout`, `onRestart` | Waits for the next event on that attribute. |
| `wait` (`waitKind: "forEvents"`) | `events` (1 to 16, each written as a trigger of kind `deviceAttribute`, `threshold`, `button`, `mode`, `hubVariable`, `schedule` or `duration`), `match` (`any`, the default, or `all`), `timeoutSeconds`, `onTimeout`, `onRestart` | Waits for any one of the events, or until each has happened at least once (`all`). Each event is matched as that trigger would be; a `schedule` event is its next time; a `duration` event is its predicate staying true that long without a break (Rule Machine's "and stays"), timed from the wait's start if it already holds. A disabled event is left out, from `all` too. |
| `wait` (`waitKind: "until"`) | `predicate`, `timeoutSeconds`, `onTimeout`, `onRestart`; optionally `staysSeconds` (1 to 86400) | Waits until the predicate is true, which may already hold. With `staysSeconds` it must stay true that long without a break, timed from when it became true (or from the start, if it already was). |
| `later` | `seconds`, `modeSeconds`, `randomSeconds`, `cancelable` (as a delay), `actions` (the steps), `onRestart` | Rule Machine's delay on an action: the steps run after the delay while the run carries on past this step. They are not one of the rule's running runs, so a new trigger's re-run policy leaves them alone. Cancel Rule Timers, disabling the rule and a new version end them, and so does `cancelDelays` if they are `cancelable`. At most 16 lots of a rule's steps wait at once (`LATER_LIMIT`). After a restart they meet `onRestart` like a wait. |
| `cancelDelays` | none | Rule Machine's Cancel Delayed Actions: ends this rule's `cancelable` delays and `later` steps. A run held by a cancelable delay ends there. |
| `if` | `condition`, `then`, `elseIf` (list of `{condition, then}`), `otherwise` | Branches. |
| `repeat` (`repeatKind: "every"`) | `everySeconds` (1 to 86400), `times` (1 to 1000, optional), `steps`, `onRestart` | Runs `steps` at once, then again every `everySeconds`, counted from the start of each pass: `times` passes, or without `times` until a `stopRepeat` or the rule stops it (warned `REPEAT_ENDLESS` if nothing in the rule can stop it while it runs). The steps after it run once it ends. |
| `repeat` (`repeatKind: "while"` \| `"until"`) | `condition`, `everySeconds` (optional), `steps`, `onRestart` | `while` checks the condition before every pass, so the steps may not run at all; `until` before every pass but the first, so they run at least once. Without `everySeconds` one of `steps` itself must be a wait (`REPEAT_UNPACED`), and such a repeat stops after 1000 passes (`REPEAT_LIMIT`). A condition that cannot be evaluated is an error (`REPEAT_UNKNOWN`), as for an unknown `if`. |
| `stopRepeat` | none | Ends every repeat of this rule's runs before its next pass. A repeat waiting for its interval ends at once and its run carries on after it (Rule Machine's Stop Repeating Actions). |
| `byMode` | `cases` (list of `{modes, then}`: `modes` is a list of mode names, `then` the steps), `otherwise` (optional) | Runs the steps of the first case whose `modes` include the current mode, or else `otherwise`. One step for all of Rule Machine's per-mode forms: set or choose switches, set dimmers and colours, and delays, per mode. A mode not on the hub is refused (`REF_MISSING`); a mode in two cases is warned (`MODE_CASE_REPEATED`). If the mode cannot be read the run fails (`MODE_UNKNOWN`), as for an unknown `if`. |
| `flashSwitches` | `refs` (1 to 32 switches), `seconds` (1 to 60), `times` (1 to 1000, optional) | Turns the switches on, then off `seconds` later, and again, `times` times (ending off), or without `times` until a `stopGradual` or the rule stops it (warned `FLASH_ENDLESS` if nothing in the rule stops it). Works on any switch; the `flash` command is for drivers with the Flash capability. |
| `deviceEnabled` | `refs` (1 to 32 devices), `enabled` (true or false) | Disables the devices, so the hub ignores them for every app, or enables them again: the hub's own `POST /device/disable` call with the device's bare number, sent to the hub itself; each device is answered and read back from `/device/fullJson` before the next, and a device that does not read as asked fails the step. Rule Machine's Disable/Enable devices. Only approved devices; the device's own disabled flag in `/device/fullJson` is the read-back. |
| `fade` | `refs` (1 to 32 devices), `attribute` (`level` default, or `colorTemperature`), `to` (0 to 100, or 1000 to 30000 kelvin), `seconds` (1 to 86400), `stepSeconds` (optional) | Rule Machine's fade over time: each device goes from where it is (a light that is off from 0%) to `to` over `seconds`, in steps sent as `setLevel` or `setColorTemperature`. Without `stepSeconds`, one step per unit of change (1% or 10 K), at most one a second and at most 100; a step that would not change a device is not sent. |
| `stopGradual` | `what` (`level` \| `colorTemperature` \| `flash`, optional: all of them) | Ends this rule's fades or flashing before their next step (Rule Machine's Stop fade and stop changing colour temperature). Warned `GRADUAL_NOTHING_TO_STOP` if the rule starts none of that kind. |
| `track` | `refs` (1 to 32 devices), `attribute` (`switch` \| `level`) | Rule Machine's Track event switch and Track event dimmer: the devices follow the device that fired the trigger, on or off as its switch, or at its level. The value is the event's own when it reported that attribute, otherwise the device's reading now. With no such device or value, nothing is sent. The rule needs a device trigger (`EVENT_DEVICE_NONE`). |
| `runRule` | `ref` (a rule reference); optionally `input` (a literal the called rule reads as its rule variable `rv:input`), `wait` (true), `timeoutSeconds` (1 to 86400, needed with `wait`), `result` (a variable key), `onTimeout` | Runs another rule, which must have a `ruleInvocation` trigger. Without `wait` the caller carries on. With `wait` the caller waits until the called run ends (or never starts, being paused or its conditions false), keeps its returned value in `result`, then carries on. This is Rule Machine's Rule Function. |
| `return` | `value` (a literal) or `ref` (a variable key), one of the two | Ends the run and gives the value back to the rule that waits for it. |
| `capture` | `refs` (1 to 32 device keys) | Remembers each device's switch, level, colour temperature, hue, saturation and colour mode. The rule keeps them until its next capture. |
| `restore` | `refs` | Puts each captured device back through ordinary commands: its colour or colour temperature and level if it was on, then on or off. Devices never captured are left alone. |
| `customCommand` | `ref` (a device), `command`, `args` (up to 8 values, optional) | Sends a command the device's driver offers that is not in the catalogue (Rule Machine's Run Custom Action). Refused for commands the catalogue covers and for any command to a lock, garage door, door, alarm, keypad or valve, because HAI's risk rules apply to those. Nothing to confirm; never retried. |
| `log` | `text` (up to 1024 characters), `level` (`info` default, or `warn`) | Writes a line to Hubitat's Logs page. |
| `setMode` | `ref` (a mode reference) or `value` (a mode name), one of the two | Changes the hub's mode. It commands no device, but every automation on the hub reads the mode, so it has its own permission: a person turns on **Allow rules to change the mode** on the HAI Engine page. Without that, the step is refused. Reading the mode and triggering on it never need this. |
| `hsm` | `op`: `armAway`, `armHome`, `armNight`, `disarm`, `disarmAll`, `armAll`, `armRules`, `disarmRules` or `cancelAlerts` | Asks Hubitat Safety Monitor to arm, disarm, arm or disarm its custom rules, or cancel its alerts (Hubitat's `hsmSetArm` location event). It is in the `security` risk class, which names what the step reaches rather than gating it: describing an action is not the same as forbidding one. Nothing approves it and nothing fences it; what stops an HSM step is that nobody wrote one into a rule. |
| `http` | `url` (http or https, up to 2048 characters), `method` (`get` default, or `post`), `headers` (optional, up to 16 `{"Name": "text"}`), `body` and `contentType` (POST only; `application/json` default, `application/x-www-form-urlencoded`, `text/plain`, `text/xml`), `result` (optional, a variable key), `field` (optional, with `result`: a path into a JSON answer such as `data.temp` or `items.0.name`), `timeoutSeconds` (1 to 60, default 10) | Sends an HTTP GET or POST without holding the hub: the answer comes back later. With `result`, the run waits for the answer and keeps it, or the `field` of it, in the variable (Rule Machine's Set Variable from HTTP response); an answer other than 2xx, none within the timeout, or a value the variable cannot hold fails the step (`HTTP_FAILED`), and the rule's error policy decides. Without `result` the run carries on at once. |
| `ping` | `host` (an IP address; Hubitat's ping takes an address), `into` (optional: `{"<result>": "<variable key>"}` for any of `transmitted`, `received`, `loss` (lost responses, as the hub reports it), `rttMin`, `rttAvg`, `rttMax` (milliseconds)), `timeoutSeconds` (1 to 60, default 10) | Pings the host three times. With `into`, the run waits and keeps those results; a ping that cannot be made fails the step (`PING_FAILED`). |
| `file` | `op` (`write` \| `append` \| `delete`), `name` (a File Manager name: letters, digits, `.`, `-`, `_`, up to 64; never `hai-...`, HAI's own files), `text` (not for `delete`, up to 4096 characters) | Writes the text as the whole file, adds it to the end (a missing file starts empty; the file may reach 65536 characters), or deletes the file. A failure fails the step (`FILE_FAILED`). |

**Values in text.** The text of `deviceNotification`, `speak` and `log` may contain `%device%`, `%value%`
and `%text%` (what started the run), `%date%`, `%time%` and `%now%`, and any variable as `%Its Name%` (this
rule's own, or a hub variable). They are filled in when the step runs. A `%name%` that matches nothing is
left as written.
- `%date:<pattern>%`, `%time:<pattern>%`, `%now:<pattern>%` give the time in a Java date pattern, such as
  `%date:EEE d MMM%`.
- After a `forEvent` or `forEvents` wait, `%device%`, `%value%` and `%text%` describe the event the wait saw;
  after a wait that timed out, `%value%` and `%text%` read `timeout`.
- `%elapsed%` is how many seconds the run's last wait took.
- The same values are filled in an `http` step's `url`, `headers` and `body`, a `ping` step's `host`, and a
  `file` step's `name` and `text`. They go in as they are: use the `urlEncode` operation first for a value in
  an address. An address or name with a `%value%` in it is checked again when the step runs.
- A rule's name may contain `%Hub Variable%`: its app's name in Hubitat shows the value, refreshed when HAI
  Engine next rebuilds its summary (on any change, and at least every 15 minutes).
| `exit` | none | Ends the run at this step. The remaining steps do not run. |
| `comment` | `text` (up to 500 characters) | A note. Does nothing. |
| `setRuleBoolean` | `rules` (list of `self`, `rule` or `rmRule` keys), `value` (true or false) | Sets each rule's Private Boolean. |
| `controlRule` | `rules`, `op` (`pause` \| `resume` \| `cancelTimers` \| `runActions` \| `disable` \| `enable`) | Pauses or resumes each rule, or ends its waiting and running runs (`cancelTimers`). `runActions` runs a Rule Machine rule's actions; an HAI rule is run with `runRule`. `disable` and `enable` switch a Rule Machine rule's app off or on, as the hub's Apps list does; they take `rmRule` keys only (an HAI rule is stopped from the editor, `REF_WRONG_KIND`). A Rule Machine rule switched over to its HAI copy is still named by its `rmRule` key: disable and enable stay with the Rule Machine app, while the other ops go to the copy. |

A disable or enable step names the Rule Machine rule by an `rmRule` reference, its app id as text, and puts that
key in `rules`. A bare id in `rules`, or a `rule` reference with the Rule Machine id, is refused (`REF_UNDECLARED`,
`REF_MISSING`):

```json
"refs": { "porchRule": { "kind": "rmRule", "id": "123", "name": "Porch lights" } },
"actions": [ { "type": "controlRule", "op": "disable", "rules": ["porchRule"] } ]
```

Any trigger or step may carry `disabled: true`:
- It stays in the rule and is skipped.
- A disabled trigger never starts the rule.
- The read-back says it is disabled.

- `waitKind` is `delay`, `forEvent`, `forEvents` or `until`.
- "And stays" inside a wait reads only readings taken since the last restart, as a `duration` trigger does:
  time that passed while the hub was down is not counted.
- `repeatKind` is `every`, `while` or `until`. A repeat's interval is a wait: `pauseMode: "hold"` holds it,
  `finish` ends the repeat at its next pass; disabling the rule, `cancelTimers` and a new run under
  `restart` end it; after a hub restart its `onRestart` applies as for a wait. The per-run limit of 2048
  steps counts the steps between two waits, so a repeat that waits between passes can go on for as long as
  it is meant to. An interval under a minute with no `times` is warned (`REPEAT_FREQUENT`).
- **Fades and flashing run beside the steps.** A `fade` or `flashSwitches` step starts a background run of
  the same rule and the steps after it carry on at once, as in Rule Machine. The background run sends
  ordinary, confirmed commands, and waits between its steps with the same durable timers as a delay, so
  pause, disabling the rule, `cancelTimers` and a new version end it or hold it as they do a delay; after a
  hub restart it carries on from the step it had reached (`catchUp`). It is not one of the rule's runs for
  `rerunPolicy`: a new trigger neither waits for it nor restarts it. A new fade or flash of a device ends
  the one of the same kind it had.
- `onTimeout` is `continue`, `stopRun` (default) or `failurePath`.
- `onRestart`, what a waiting run does if the hub restarted meanwhile, is:
  - `reevaluate` (default): check the rule's conditions again, using only readings taken since the restart,
    and end the run if they are false or unknown;
  - `catchUp`: carry on without that check;
  - `skip`: end the run.

  In every case, later steps use only readings taken since the restart.

## Operation catalogue (commands)

| Command | Arguments | Needs | Confirmed by | Risk |
|---|---|---|---|---|
| `on`, `off` | none | Switch | `switch` | none |
| `toggle` | none | Switch | `switch` (resolved to on or off when requested) | none |
| `setLevel` | level 0 to 100, fade seconds 0 to 3600 (optional) | SwitchLevel | `level`, `switch` | none |
| `setColorTemperature` | kelvin 1000 to 30000, level (optional), fade seconds (optional) | ColorTemperature | `colorTemperature` | none |
| `lock`, `unlock` | none | Lock | `lock` | access |
| `open`, `close` | none | GarageDoorControl | `door` | motion |
| `siren`, `strobe`, `both`, `alarmOff` | none | Alarm | `alarm` | alarm |
| `push` | button number 1 to 100 | PushableButton | nothing to confirm | none |
| `hold` | button number 1 to 100 | HoldableButton | nothing to confirm | none |
| `doubleTap` | button number 1 to 100 | DoubleTapableButton | nothing to confirm | none |
| `release` | button number 1 to 100 | ReleasableButton | nothing to confirm | none |
| `deviceNotification` | text, up to 1024 characters | Notification | nothing to confirm | none |
| `refresh` | none | Refresh | nothing to confirm | none |
| `speak` | text, up to 1024 characters; volume 0 to 100 (optional) | SpeechSynthesis | nothing to confirm | none |
| `setVolume` | volume 0 to 100 | AudioVolume | `volume` | none |
| `mute`, `unmute` | none | AudioVolume | `mute` | none |
| `beep` | none | Tone | nothing to confirm | none |
| `playSound` | sound number 1 to 100 | Chime | nothing to confirm | none |
| `setColor` | hue 0 to 100, saturation 0 to 100, level (optional) | ColorControl | `hue`, `saturation` | none |
| `flash` | rate in ms 100 to 10000 (optional) | Flash | nothing to confirm | none |
| `adjustLevel` | change -100 to 100, fade seconds 0 to 3600 (optional) | SwitchLevel | `level`, `switch` (sent as `setLevel` to the current level plus the change, kept within 0 to 100) | none |
| `toggleLevel` | level 1 to 100 | Switch, SwitchLevel | `switch` (sent as `off` if the switch is on, otherwise `setLevel` to the level) | none |
| `toggleColorTemperature` | kelvin 1000 to 30000, level (optional) | Switch, ColorTemperature | `switch` (sent as `off` if the switch is on, otherwise `setColorTemperature`), Rule Machine's Toggle colour temperature | none |
| `toggleColor` | hue 0 to 100, saturation 0 to 100, level (optional) | Switch, ColorControl | `switch` (sent as `off` if the switch is on, otherwise `setColor`), Rule Machine's Toggle colour | none |
| `startLevelChange` | `up` or `down` | ChangeLevel | nothing to confirm | none |
| `stopLevelChange` | none | ChangeLevel | nothing to confirm | none |
| `openShade`, `closeShade` | none | WindowShade | `windowShade` (sent as the driver's `open` and `close`) | none |
| `setPosition` | position 0 to 100 | WindowShade | `position` | none |
| `stopPositionChange` | none | WindowShade | nothing to confirm | none |
| `openValve`, `closeValve` | none | Valve | `valve` (sent as the driver's `open` and `close`) | none |
| `setSpeed` | `low`, `medium-low`, `medium`, `medium-high`, `high`, `on`, `off` or `auto` | FanControl | `speed` (any speed after `on`) | none |
| `cycleSpeed` | none | FanControl | nothing to confirm | none |
| `setHeatingSetpoint`, `setCoolingSetpoint` | setpoint 0 to 120, in the hub's scale | Thermostat | `heatingSetpoint`, `coolingSetpoint` | none |
| `adjustHeatingSetpoint`, `adjustCoolingSetpoint` | change -20 to 20 | Thermostat | as above (sent as the current setpoint plus the change) | none |
| `setThermostatMode` | `auto`, `off`, `heat`, `emergency heat` or `cool` | Thermostat | `thermostatMode` | none |
| `setThermostatFanMode` | `auto`, `on` or `circulate` | Thermostat | `thermostatFanMode` | none |
| `play`, `pause`, `stop` | none | MusicPlayer | `status` (`playing`, `paused`, `stopped`) | none |
| `nextTrack`, `previousTrack` | none | MusicPlayer | nothing to confirm | none |
| `playTrack`, `setTrack`, `restoreTrack`, `resumeTrack` | track URI, up to 1024 characters | MusicPlayer | nothing to confirm | none |
| `playText` | text, up to 1024 characters | MusicPlayer | nothing to confirm | none |
| `volumeUp`, `volumeDown` | none | AudioVolume | nothing to confirm | none |
| `togglePlay` | none | MusicPlayer | `status` (sent as `pause` if playing, otherwise `play`), Rule Machine's toggle play/pause | none |
| `toggleMute` | none | AudioVolume | `mute` (sent as `unmute` if muted, otherwise `mute`), Rule Machine's toggle mute | none |
| `poll` | none | Polling | nothing to confirm | none |
| `initialize` | none | Initialize | nothing to confirm | none |

- **Values from variables:** any argument may be `{"ref": "<variable key>"}` instead of a value, such as a
  level, a colour, a chime number or a fade time. It is read when the step runs and checked against the
  same range; a value outside it, or a variable that is not set, fails the step. A number argument needs a
  number variable.
- **Built from other commands:** `toggle` and `alarmOff` work on any device that has `on` and `off`, or
  `off`, even when its driver has no command of that name. Likewise `adjustLevel` needs `setLevel`,
  `toggleLevel` needs `off` and `setLevel`, `toggleColor` needs `off` and `setColor`, `toggleColorTemperature` needs `off` and `setColorTemperature`, `togglePlay` needs `play` and `pause`, `toggleMute` needs `mute` and `unmute`, the adjust setpoint commands need the matching set command,
  and the shade and valve commands need `open` or `close`. A change by an amount is worked out when the step
  runs, from the current reading; if that reading is not known, the step fails (`CURRENT_VALUE_UNKNOWN`)
  rather than guess. What is sent is the absolute value, so a retry never adds the amount twice.
- **Shades and valves** have their own command names so that they can never reach a garage door. A garage
  door's `open` and `close` are its own commands, on a device a person selected in the picker.
- **Fades:** `setLevel` and `adjustLevel` take a fade time, and `setColorTemperature` a transition time, both
  carried out by the device's driver, up to an hour. Rule Machine's stepped "fade over time", which works
  on any dimmer and for longer, is the `fade` step.
- **Text with fixed values:** a fan speed, direction or thermostat mode must be exactly one of the listed
  values, including from a variable.
- **Risk:** the risk class names what a command reaches: `access` (locks and entry control), `motion` (garage
  doors, gates, anything that moves and can trap) or `alarm` (sirens and alarm state). It asks for no extra
  approval; the device picker is the one permission. A command with a risk class cannot be sent to
  `eventDevice`, and `customCommand` cannot reach these devices.
- **Retries:** a command is never retried unless the catalogue allows it. Even then, never after a later
  command reached the same device.

## Permissions

Everything above runs on the hub. There is one device permission, the Hubitat device picker (**Approved
devices**). What refuses a step is a device the person has not selected, or a command its driver does not
offer.

Two further switches exist, and neither is an approval:
- **Changing the mode** needs "Allow rules to change the mode" on the HAI Engine page. It commands no device
  but every automation on the hub reads the mode, so it has its own switch.
- **HTTP and ping** may reach **any** address. There is no host allow-list; do not plan containment around
  one. On any host, an address under `/hub/` that restarts, shuts down, updates, restores, resets, backs up or
  deletes is refused unless the step's own address names it. One reached through a `%value%` filled in at
  run time is always refused, because no person saw it.
