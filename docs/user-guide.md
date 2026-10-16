# HAI user guide

**This engine is HAI-1.** That name covers the rule format, the runtime and everything in this guide. A
later generation would be HAI-2 and would say so; the hub reports HAI-1 followed by the build.

HAI is a rule engine for Hubitat. Your rule reads as plain lines with Rule Machine's IF, ELSE and END-IF, each
step edited in a form of its own; HAI reads it back to you in plain English, checks it against your devices and lets you try it in a
simulator, and then runs it on your hub with one press. This guide covers everything HAI does today. Where HAI replaces a Rule Machine feature, the Rule
Machine name is given, so you can find what you are used to.

HAI is one app, **HAI Engine**, with one child app per rule. There used to be a second app, HAI Runtime,
with a pairing code and a Candidates page; it was merged into HAI Engine on 2026-09-27, and neither exists
any more. The one permission HAI asks for is the Hubitat device picker.

For a one-page picture of Automation Map, the HAI Engine and the optional migration app, open [the toolkit overview](user/toolkit.html) in a browser.

## Contents

0. [Installing HAI](#installing-hai)
1. [Getting started](#getting-started)
2. [The editor](#the-editor), including [AI drafting and privacy](#drafting-with-ai-and-privacy)
3. [Triggers: what starts a rule](#triggers)
4. [Conditions: only if](#conditions)
5. [Steps: what the rule does](#steps)
6. [Variables](#variables)
7. [One rule running another](#one-rule-running-another)
8. [Running, pausing and stopping](#running-pausing-and-stopping), and [rule end points](#rule-end-points)
8a. [How big a rule can be](#how-big-a-rule-can-be)
9. [When a rule did not do what you expected](#troubleshooting), and the [refusals you are likely to meet](#refusals-you-are-likely-to-meet-and-what-to-do)
9a. [Moving from Rule Machine](#moving-from-rule-machine)
    A step-by-step guide for home owners: [Moving your Rule Machine rules to HAI](migration-guide.md)
10. [Locks, sirens, garage doors and HSM](#locks-sirens-garage-doors-and-hsm)
11. [Moving rules between hubs](#moving-rules-between-hubs)
12. [Rule Machine names and HAI names](#rule-machine-names-and-hai-names)
13. [Appendix: Quick start](#appendix-quick-start)

## Installing HAI

HAI is a preview, published on the `preview` branch of the HAI-Engine repository on GitHub, with an
`INSTALL.md` that gives these same steps. You need a Hubitat hub on firmware 2.4 or later and a web browser;
nothing else. Install through Hubitat Package Manager or by hand; on a preview build the app names end in
"(Preview)". Optional AI
drafting sends rule-authoring context to Anthropic, as described under
[Drafting with AI and privacy](#drafting-with-ai-and-privacy).

The folder holds three pieces of app code in `apps/` and five files in `file-manager/`:

| App code | What it is |
|---|---|
| `hai_engine_app.groovy` | **HAI Engine**: the editor, and the part that runs your rules |
| `hai_rule_app.groovy` | **HAI Rule Container**: one per rule. You never add it yourself; HAI does |
| `hai_migration_app.groovy` | **HAI Migration**, optional: moves Rule Machine rules, see [Moving from Rule Machine](#moving-from-rule-machine) |

1. **Download.** Open the preview repository on GitHub, press **Code**, then **Download ZIP**, and unzip it.
2. **Add the app code.** On your hub open **Apps Code**, press **New App**, paste the whole of one `.groovy`
   file and press **Save**. Do this for each of the three files.
3. **Turn on OAuth for HAI Engine.** On its Apps Code page press **OAuth**, enable it, and save. The editor
   cannot open without it. Do the same for HAI Migration if you added it. The rule container needs none.
4. **Upload the five files** in **Settings → File Manager**, keeping their names exactly as they are:
   `hai-editor.html`, `hai-migration.html`, `hai-author-guide.txt`, `hai-editor-specs.json` and
   `hai-am-capabilities.json`. The editor page, the Rule Machine page, the drafting guide and the editor's
   tables live there so that the apps stay inside Hubitat's size limit. HAI checks each file against the
   app code it came with and refuses one from another version.
5. **Add HAI Engine.** **Apps → Add User App**, choose **HAI Engine**, look through **Approved devices**
   (below), and press **Done**. It appears under Apps.
6. **Optional: Rule Machine rules.** Install Automation Map 2.4.36 or later and let it scan, then press
   **Add HAI Migration** on the HAI Engine page.
7. **Optional: the AI Rule Assistant.** Add your Claude API key under **AI token and cost management** on the
   HAI Engine page. Everything else works without it.

There is nothing to pair: the editor and the part that runs rules are the same app.

**To update:** paste the new code over the old on each Apps Code page, press **Save**, and upload the five
files again. Your rules, their history and your settings are untouched.

**To uninstall:** remove HAI Engine from Apps, which removes its rules and HAI Migration with it. Then delete
the three app codes in Apps Code and the five `hai-` files in File Manager. Rule backups stay in File Manager
until you delete them.

**For developers:** a working copy of the repository deploys with `engine/deploy-hub.ps1` (one target per
app, with the library lists the apps are built from) and uploads the five files with
`engine/upload-editor-page.ps1`. `python engine/make-dist.py` writes the three apps to `dist/`, and
`python tools/make_preview_package.py` builds the preview folder from them.

## Getting started

HAI is one app with a child per rule:
- **HAI Engine:** builds and runs your rules, and holds what rules may use. Open it from Apps.
- **[HAI] <rule name>:** one app per rule, listed in Apps and under each device's In Use By. Opening one
  shows the rule's status, when it was created, last changed and last ran, and a link, **Open this rule in
  the editor**.

The **HAI Engine** page has these sections:

| Section | What it holds |
|---|---|
| **The rule editor** | **Open the rule editor**, where you write, check, simulate and run rules, and a line saying where to get help or report a problem (its links are still to come) |
| **Rule Machine** | **Rule Machine Migration Facility**, when the optional HAI Migration app is installed: discover, select, assess and controlled migration of RM rules, with rollback option. Without it, the section says so and offers **Add HAI Migration**. See [Moving from Rule Machine](#moving-from-rule-machine) |
| **Approved devices** | The one permission HAI asks for. Once devices are chosen it is collapsed, with the count in its heading ("Approved devices: (37)"); click the heading to open it |
| **Mode and approved hub variables** | The heading shows whether rules may change the mode and how many hub variables they may change ("Mode [ON] and approved hub variables (12)"). Inside: **Allow rules to change the mode**, which lists your hub's modes, then **Approved hub variables** |
| **Stop a rule** | Choose a rule and press **Deactivate**, so a rule can be stopped from the hub even when the editor cannot open. Deactivate also stops anything the rule is part way through: its waits, delays and repeats do not carry on |
| **AI token and cost management** | The Claude API key, with a link to your keys at platform.claude.com/settings/keys, the spending limit for drafting, this month's AI drafts with their total and average cost (a retry or a change to a rule counts as a draft; key tests are left out), the effort setting and **Test the API key** |
| **Rule end points** | What may run your HAI rules from outside the editor: the switch for other apps on this hub, the API token those apps must send, with a **Copy** button, and the private web address for dashboards, shortcuts and other hubs. See [Rule end points](#rule-end-points) |
| **MCP endpoint** | Off until you switch on **Let an AI assistant connect (MCP)**. Then it shows the address an AI assistant such as Claude Desktop uses to write rules with you. They arrive as drafts in the editor; the assistant cannot turn a rule on or run one |
| **Change access addresses** | **Change HAI's access addresses**, for when someone may have a link to HAI they should not have. Every HAI link changes, so bookmarks, dashboards and other apps need the new ones. See [Changing HAI's access addresses](#changing-hais-access-addresses) |
| **Health and diagnostics** | One line saying the app is alive and how many rules are on the hub, and **Turn diagnostics on for an hour** |

Before your first rule, open **HAI Engine** and choose:
- **Approved devices.** This is the one permission HAI asks for. A rule may use any device you select
  here, with any command its driver offers. That includes locks, sirens and garage doors.
- **A new install approves every device.** When HAI Engine is first installed it selects every device on the hub under
  **Approved devices**, and a line above the list says so. Open the list to untick any device you want to keep HAI away from, then press
  **Done**. If the hub will not give HAI its device list (with hub login security on, for example), nothing is selected
  and the section says why. An existing install is never changed.
- **Approve new devices automatically,** under **Approved devices**, is on unless you turn it off. While it is on, a
  Z-Wave or Zigbee device you pair with the hub joins **Approved devices** as soon as the hub reports that it joined.
  A virtual device, or one a cloud or LAN integration creates, is not reported that way, so add those by hand. HAI
  does nothing for this while the setting is off, and while it is on it acts only when a device joins.
- **Allow rules to change the mode,** under **Mode and approved hub variables**, is on for a new install; turn
  it off if rules must not change the hub's mode. This is the hub's mode (Day, Night, Away and so on), not
  Hubitat Safety Monitor: HAI rules cannot arm or disarm. A choice you have already saved is kept.
- **Approved hub variables,** in the same section. On a new install every hub variable is ticked; untick any
  rules must not set. Rules can read and start from any hub variable without this.

## The editor

**The rule reads as plain lines,** like Rule Machine's rule page, under three headings in the order
they happen: **Starts when**, **Only if** and **Then, in order**. Two more appear when a rule uses them:
**Prerequisite** above, and **If a step fails** or **If a run is cancelled** below.

- **Triggers** are joined by **or**: any one of them starts the rule.
- **Flow uses Rule Machine's words,** laid out as in a programming editor, with no step numbers:
  ```
  IF (Hall Motion is active) THEN
      Turn on Hall Light
  ELSE-IF (Front Door is open) THEN
      Say "Door open" on Kitchen Speaker
  ELSE
      Turn off Hall Light
  END-IF
  ```
  IF, ELSE-IF, ELSE and END-IF line up; the steps inside start where the IF's words start, with a line down
  the left. Repeats read **REPEAT every 1 minute, 5 times**, **REPEAT WHILE (…)** or **REPEAT UNTIL (…)** and
  close with **END-REP**; **Depending on the mode** has **MODE IS Home or Night:**, **ANY OTHER MODE:** and
  **END-MODE**; **STOP REPEATING** and **EXIT RULE** say what they do. A line that must be fixed is tinted red.
- **Every setting that is not a default is written on the line:** "all of Hall Motion and Porch
  Motion are inactive and stay so for 10 minutes, giving up after 1 hour and then carry on". A value
  not chosen yet is marked in amber, such as **choose a device**.
- **true now / false now** beside the Prerequisite, an Only if, an IF, an ELSE-IF or a REPEAT WHILE / UNTIL says
  whether it is true now (Rule Machine's **Display current values**). The hub works it out with the same
  evaluator the rule runs on, from what the hub reports now: device readings, the mode, hub variables, the
  time, sunrise and sunset with offsets. Where the answer lives only in a running rule (the rule's own
  variables, its Private Boolean, which trigger started a run), it says nothing rather than guess.
  **Show current values**, above the rule, turns them off; the editor remembers your choice in this browser.
- **Each line has an icon** in a column of its own. A line about devices shows what kind of device they are,
  with the same icon Automation Map uses for it: a bulb for lighting, a hand for buttons, a walking figure for
  motion. HAI decides this itself, so the icons are there whether or not Automation Map is installed. Other
  steps have their own: a branch for **If**, an hourglass for a wait, a clock for **Wait until**, an envelope
  for a notification, a speaker for **Say**, a gear for a driver command.
- **The hub's reading** of the rule is the **Read-back** on the right; under **Show the rest** it also gives the
  hub's own sentence for each line. **Simulate** shows what each trigger would do. With unsaved edits to a rule
  that is on the hub, **Show changes** beside **Unsaved changes** marks what differs from the version running
  there (**Hide changes** takes the marks away).
- **Starts when** lists its triggers A to Z when there are several (**any of these**), and every device list is A to
  Z by name (a list grouped by room keeps its rooms). This is only how they are shown: the rule keeps its own order.
  **Then** steps and **Only if** conditions are never sorted, because their order is what the rule does. Triggers
  shown A to Z have no **Move up** or **Move down**.
- **Unsaved edits** turn **Put on hub** / **Update on hub** and **Save draft** amber, with **Unsaved changes**
  beside them. **Save draft** is always there and never touches the hub. Leaving a rule with unsaved edits asks whether to put them
  on the hub, save them as a draft, discard them, or keep editing.
- **Draft or hub version.** A line above the rule says which one is open. A rule under **On the hub** always
  opens the version the hub is running; editing it and saving makes a draft, and the hub keeps running its
  version until you press **Update on hub**. A rule under **Drafts** says whether it is not on the hub, the same
  as the hub, or different from it, with **Update the hub with this draft** and **Open the hub version** beside it.
  Each hub rule has one draft: saving the hub's copy again replaces it.
- **The rule list and the right-hand panel** each fold away with the small arrow on their inner edge; the arrow
  stays where it was, to bring the panel back.

**Changing a line:** click it. One form opens for that step, with its fields labelled as you would say them:

- **Devices:** a list with a tick box on every device, **Choose one or more**, a search, and the devices
  already chosen ticked at the top under **Chosen**. Each device shows its room, its icon and what it reports
  now. With more than one device chosen, **Any of them** or **All of them** appears.
- **Choices** are buttons side by side (**is** / **is not**, **Pushed** / **Held** / **Double-tapped**). The
  comparisons offered suit the reading: a switch is **is** or **is not**, a temperature also **is above**,
  **is at least**, **is between** and the like.
- **Values a person can know:** a device's value is always a dropdown. It offers what the driver says the
  attribute can be, then its current value (marked **now**), then **Other value…** to type anything else.
  In a device trigger the dropdown has tick boxes, with **Any change** first: tick **opening** and
  **closing** and the line reads "door changes to opening or closing". The rule keeps one trigger per
  value and shows them as one line.
- **Numbers** show their unit and range. **is between** takes two numbers side by side ("between 18 and 24
  °"). **Lengths of time** start in minutes, shown before you type. **Dates** are chosen on a calendar; tick
  **Every year** for the same day each year.
- **Yes / no settings** are a tick box marked **Yes**: tick it for yes.
- **Waits** have no time limit unless you tick **Give up after a while**. Then say how long, and what to do
  when it gives up: **Carry on** is chosen for you, **Stop** or **Run the failure steps** if you want.
- **Conditions** are cards, one per condition, inside the form of what holds them: the If, the Only if, a
  Wait until, a Repeat. **+ Add another condition** adds a card without leaving the If, and **Combine them
  with and / or** says how they go together. **Not** on a card makes it true when the condition is not.
- **An IF's form** also holds its branches: **+ Add an ELSE-IF**, each with its own conditions, and
  **+ Add an ELSE** for the steps when nothing above is true. Each becomes a branch with **Remove this branch**.
  A new IF opens on its IF condition; ELSE-IF and ELSE appear only when you add them.
  Clicking an **ELSE-IF** or **ELSE** line opens the IF at that branch. **Depending on the mode** has **+ Add
  ANY OTHER MODE** the same way.
- **Settings left at their defaults** wait under **More settings**. Anything set is always in view.
- **The line will read** shows the line as it will be, with its **true now** or **false now**.
- **Done** keeps what you changed; **Cancel** puts it back exactly as it was. **Done** waits, and says why in
  plain words beside it, while something the step needs is missing or a value cannot be right ("Add a
  condition to the IF.", "Enter how long to wait before giving up.", "There is no 31 February."). Click the
  message to scroll to what it is about. **✕** or Escape after a change asks **Keep
  your changes?** in the form itself; with nothing changed they simply close it. On a phone the form takes
  the whole screen.

**Adding:** **+ Add a trigger**, **+ Add a condition** and **+ Add a step** are at the end of each section,
and every branch has its own **+ Add a step**. Beside every **+ Add a step** is **+ Add flow**, for an IF,
**Depending on the mode**, **Repeat**, **Repeat while**, **Repeat until**, **Stop repeating** and **Exit rule**;
searching the step menu finds those too. They are always shown. The search also finds device commands by
their words, and the words people use for them ("volume" or "loudness", "dim", "color", "announce"):
**Set the volume of a device** opens **Do something to a device** with that command chosen and only the
devices that offer it. Typing a device's name lists that device with its commands; picking one opens the
step with the device and command chosen. After an IF, or inside one, the step menu starts with **Add ELSE-IF
to: IF (…)** and, while it has none, **Add ELSE to: IF (…)**; **New IF block** starts a separate IF. When
you have copied a step, the menu also offers **Paste:** and the step, so an empty branch can take it. A
command only one kind of device offers,
such as infrared on a light, is not in **Do something to a device**'s list: use **Use a non-standard device
command**, choose the device, and its driver's own commands are listed. Choose what kind of trigger, condition
or step, and its form opens at once; **Done** puts the line in, **Cancel** adds nothing. A new step starts
empty and asks for what it needs: no time, date, colour, level or time limit is chosen for you, and an HSM
step never arrives already set to arm. A new trigger on a device can take several devices: each one becomes a
trigger of its own, so two buttons that do the same thing take one form. On a phone, the round **+** button
adds too.

**Moving, copying and deleting:** the **⋯** at the end of a line, or **More** and **Delete** in its form:

- **Delete** removes the line; **Undo** in the message that follows puts it back. Deleting an If deletes its
  branches and their steps; deleting a condition is done on its card in the If's form, and leaves the If.
- **Run now** (steps only) runs that one step on the hub straight away: no trigger, no conditions, nothing
  before or after it. It runs a device command, a mode change, HSM or a log line, exactly as the step says,
  and reports what it sent or why it failed. Any other step (If, Repeat, a wait, a variable, a web request,
  another rule, or a value worked out while the rule runs) says it cannot run on its own; run the whole rule
  instead. A disabled device is not sent to.
- **Duplicate** puts a copy below. **Copy**, then **Paste after this** on another line, here or in another
  rule; the devices and rules it uses come with it.
- **Move up**, **Move down**, and **Move to…**, which lists every branch in the rule by the step that holds
  it ("Inside IF (Hall Motion is active) THEN", "ELSE of IF (…)") and moves the step to its end. **Copy to…**
  puts a copy there instead.
- **Run it only if…** puts the step inside a new If and asks for its condition.
- **Remove, keep its steps** takes away an If, a Repeat, **Do these steps later** or **Depending on the mode**
  and leaves its steps where it was.
- **Skip it for now** keeps a step or trigger in the rule but skips it; the line says **skipped**.
- Ctrl+click or Shift+click selects several lines in one list; the bar above the rule then deletes, moves or
  copies them together, and the **⋯** of any selected line offers **Move N steps to…**, **Copy N steps to…**,
  **Duplicate N steps** and **Delete N steps**.
- Lines can be dragged within their list or into another branch; an empty branch shows **Drop here** while
  you drag. Drop a line on an **END-IF**, **END-REP** or other end line to put it just after that block. Hold Ctrl (Option on a Mac) to copy instead of move. Where a line cannot go, the cursor says so.
- **The keyboard:** the arrow keys move between lines, Enter opens one, Delete removes it, Alt+↑ and Alt+↓
  move it, Insert adds a step after it, and Ctrl+C, Ctrl+X and Ctrl+V copy, cut and paste it.

**Copy and paste between rules:** open the ⋯ of a step and choose **Copy** (or select it and press Ctrl+C).
Open the other rule, then either choose **Paste after this** from the ⋯ of the line it should follow, or
**+ Add a step → Paste:** at the end of a list or in an empty branch. The devices, variables and rules the
step uses come with it. What you copied stays until you copy something else, in this browser.

**Check** lists what must be fixed and what is worth a look. Each finding says where it is in words, by its
step and the IF or repeat around it ("In \"Set the colour of Hall Light to green\", inside \"IF (Hall Motion is
active) THEN\""); click it to go to the line.

**Rule settings** are the last line: what it does if triggered again while running, if a step fails and the
like, written out when they are not the defaults. Click it for its form.

**The Document tab** shows the rule as JSON, for copying or for a change the forms do not offer. A change
typed there shows in the rule as soon as it is valid JSON.

- **Show tips**, ticked unless you untick it, shows a tip with an example when you rest the mouse on a
  step that is not obvious, such as **Wait for several events** or **Return a value**. Unticked, no tip
  shows. The editor remembers the choice in this browser.
- **Explanations**, a tick box beside **Show tips**, hides the one-line explanations under each choice and the
  banner at the top when unticked. Both boxes act at once and are remembered in this browser.
  What a choice needs, such as **Choose one or more** or the values and number of devices beside a device
  type, stays.
- **Undo:** the arrows at the top, or Ctrl+Z, outside a form. A form's whole change is one undo step.
- **New rule** starts an empty rule. If the rule you are leaving has unsaved changes, a box over the page
  asks first: **Discard changes** or **Keep editing**.
- **Discard** throws away the changes made since the rule was opened or last saved, in one click. It does
  not ask; **Undo** in the message that follows brings them back.

### Drafting with AI and privacy

**AI author is optional.** You can build, check, simulate, save and run a rule in the editor without
using the Claude API. AI drafting requires a Claude API key and a spending limit in HAI Engine. Pressing
**AI author** (writing a new rule, or changing the one open) sends a request over HTTPS to Anthropic's Claude API. HAI sends more than the sentence
you type:

- Your request in your own words, plus HAI's rule-authoring guide and the format for Claude's answer.
- Every enabled device selected in HAI Engine, the most relevant to your request first. For each one,
  the request can include its hub ID, name, room, capabilities, commands, commissioned purpose and
  **current attribute values**. These values may reveal occupancy or security state.
- The names of available modes; hub-variable names, IDs, types and whether HAI may write them; and the
  names and IDs of HAI and Rule Machine rules. The initial request does not include the full definitions
  of those other rules or the hub's full device inventory.
- If a draft needs correction, later calls can include Claude's earlier reply, the proposed rule and
  feedback from HAI's validation and simulation.

The Claude API key is sent to Anthropic in an authentication header, not as rule text. HAI also keeps
the words you entered as an Engine setting on your hub. While a draft is in progress, a bounded
conversation is kept in app state; when it finishes, that conversation is cleared, while proposal
summaries and some recent proposed rules remain available in the editor.

**MCP is a separate, optional route.** HAI's MCP endpoint does not itself call the Claude API. It gives
an AI client access to HAI's selected-device context and draft-only tools. What that client sends to
its own cloud provider depends on the client.

**The tools are draft-only; the token is not.** MCP is served by HAI Engine on the same access token as
the rest of the app, so a client holding it can also reach the editor's own routes — including the one
that sends a rule to the hub. The limited tool list describes what the tools do, not what the credential
permits. Give an MCP client that token only if you would give it your HAI Engine link.

Before using AI drafting, review which devices HAI Engine has selected and avoid including details in
your request that you do not want sent to the provider. For Anthropic's current handling of API data,
see its [retention policy](https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-my-organization-s-data)
and [model-training policy](https://privacy.claude.com/en/articles/7996885-how-do-you-use-personal-data-in-model-training).

**Checking a rule:**
- **Read-back** (right): HAI's own description of the rule. If it doesn't say what you meant, the rule
  isn't what you meant.
- **Current values** (under the read-back): what each device reading, hub variable and the mode the rule
  uses is now. **Refresh values** asks the hub again.
- **Check:** problems that stop the rule running.
- **Simulate:** HAI runs the rule against pretend events and shows what it would do. Nothing is sent to
  any device.

**Running a rule:**
- **Save draft** keeps the rule in the editor and changes nothing on the hub.
- **Put on hub** saves the rule and puts it live on the hub. After a change, the button reads
  **Update on hub**. It installs the rule; it does not run it. **Run now** does that.
- The rule's state shows beside the button: **Being activated**, then **On the hub**, **Paused** or
  **Stopped**, with **Run now**, then always in this order a **Running / Stopped** switch, **Pause** (or
  **Resume**) and **Delete**. Pause is greyed on a stopped rule and Delete on a running one.

### What happens when you press Put on hub

**Pressing Put on hub is the decision; nothing else asks you.** There is no pairing code, no Candidates
page and no digest to type any more. The editor signs the rule and hands it to the part of HAI Engine that
runs rules, which checks it and, if it passes, activates it straight away:

1. **The rule is checked.** Every device it names must be one you selected under **Devices rules may
   use**, every command must be one that device's driver offers, the rule must be within HAI's size limits,
   and it must pass the same checks as **Check** in the editor.
2. **The rule is activated.** HAI creates the rule's app (**[HAI] <rule name>**), or updates it, and
   subscribes it to its triggers. While this happens the editor shows **Being activated**; once it is done,
   **On the hub**.

If a check fails, the rule is not activated, and the reason shows under **On the hub** in the editor.

### Deleting a rule

Deleting is two steps on purpose, so a running rule cannot vanish under you:

1. Press the **Running** switch so it reads **Stopped**. It stops acting at once and its running runs are
   cancelled. The rule stays on the hub; pressing the switch again brings it back. The **Stop a rule** section on the HAI Engine page does the same with
   **Deactivate**.
2. Press **Delete**. The editor asks "Are you sure?", then the rule's app is deleted from the hub, with
   its state and history there.

Both are beside **Update on hub**, on the rule's row in the **On the hub** list and on the **On the hub**
panel. **Delete from hub** is also in the rule's **⋯** menu. It cannot be pressed until the rule is stopped. The rule stays in the editor as a draft, so you can put it on the hub again later; delete the
draft in the editor if you want it gone as well.

### Replacing a device, adding one beside it, or removing it

When a device is swapped out, a second one should do the same job, or a device should no longer be used, **Replace /
add alongside / remove...** changes every rule at once. It is at the top of the **More** menu, top right of the editor.

1. **Choose the device.** HAI lists every rule on the hub and every draft that uses it, and under each rule the lines
   that mention it. The dialog names this device in red wherever it appears, so it never reads as the new one.
2. **Choose what to do.**
   - **Replace** puts the new device in place of the old one on the ticked lines.
   - **Add alongside** keeps the old device and adds the new one wherever a line lists devices: a trigger gets a
     trigger of its own for the new device, and a step or a condition that already names several devices gets the
     new one in its list.
   - **Remove** takes the device out of the ticked lines. A line where it is one of several devices keeps the
     others; a condition that is one of several in an **all** or **any** group leaves the group. Where it is the
     only device in a step, a trigger or a wait, the **whole line is removed**: the line says "Removes the whole
     step" (or "Removes this trigger"), so untick it to keep it. A condition that is the whole test of its line, or
     a comparison with the device's reading, is marked **Needs your decision**. If a removed trigger was the only one
     a "started by" condition named, the preview says so. Remove needs no new device, so step 3 is skipped.
3. **Choose the new device** (Replace and Add alongside), under **Choose a replacement device for** (or **Choose a new device to add
   alongside**) and the old device's name. Only devices under **Approved devices** on the HAI Engine page are offered; to use
   another, add it there first.
4. **Tick the lines.** A line the new device cannot do (it does not report what the line reads, it has no such
   command, it has fewer buttons) says why and starts unticked. A line that takes exactly one device, such as a
   condition on one device, a wait for one device or a comparison with one device's reading, is marked **Needs your
   decision** and left as it is: open that rule and choose. Words that name the old device, such as a log line, are
   left as written.
5. **Preview** shows each rule as it will read, with the changed lines marked and a tick box per rule. With
   **Remove**, every line that disappears is listed by name above the rule, struck through.
6. **Apply** changes the ticked rules one at a time. A rule on the hub is checked and put on the hub as a new
   version, the same as **Update on hub**, so a refusal is reported and that rule is left as it was. A draft is saved
   as a draft.
7. **Undo these changes** then puts each changed rule back as it was, again as a new version.

### Rule backups

Each night at 03:41, if any rule on the hub changed that day, HAI writes the rules on the hub (running and stopped;
not drafts) to one file in the hub's **File Manager**, named `hai-backup-<date>.json`, and keeps the last ten. A night
with no change writes nothing. **More > Backups...** in the editor shows them:

- **The list.** Each backup with its date, how many rules it holds and its size. **Download** saves it as a file;
  **Back up now** writes one straight away.
- **Compare** sets a backup beside the rules on the hub now. Each rule is **Changed since the backup**, **Not on the
  hub now** (deleted since), **Unchanged** or **Added since the backup**. A changed rule shows its lines worded as the
  editor words them: **+** marks a line added since the backup, **-** (struck through) a line the backup had. A device
  renamed since the backup is shown by its name now, with "renamed since the backup: <old name>", and does not count as
  a change, because rules refer to devices by their id.
- **Devices first.** If the backup names devices HAI cannot use now, a warning comes before anything else, listing them.
  After a fresh install this is usually every device: approve them on the HAI Engine page, then press **Check again**.
  **Restore anyway** goes on to the rule-by-rule choices below, for a device that has really gone.
- **Restore this rule...** or **Restore all** shows each rule as it will be put on the hub. Every line that names a
  device no longer on the hub (deleted, or no longer under **Approved devices**) is flagged "Device no longer on the
  hub: <old name>", with **Replace with...** (choose a device for that line) or **Remove** (take the device out of
  that line, or remove the line if it was its only device, as Remove does above). A rule is not put on the hub until
  every flagged line has a choice. **Restore** then checks each ticked rule and puts it on the hub as a new version,
  the same as **Update on hub**, one at a time; a rule deleted since the backup is put on the hub again. **Undo these
  restores** puts back what was running before, again as new versions (a rule that was not on the hub before is left:
  stop it and delete it from the hub if you do not want it).

The files stay in File Manager if HAI Engine is removed.

### New in 0.5.35

- **Remember / Put devices back** keeps each device's full state: on/off, level, colour and colour temperature for lights, plus valve, shade position, fan speed and thermostat settings. Locks are only ever put back to locked. Put devices back lists only devices a Remember step earlier in the rule kept, and it stops any flash or fade still running on them first.
- **Current values** refresh when you press Check, after Run now, and with the **Refresh** button beside "Show current values". Unticking the box hides every value shown on the page.
- **A disabled device** is a warning, not a block: the rule still goes on the hub, and that step skips the device.
- **Skip several steps** at once: select them, then use ⋯ → Skip. Skipped steps are greyed, and skipping a flow skips everything inside it.
- **Triggers** offer only what the chosen devices report, with explanations on mouse-over. They can also wait with "and stays that way for", or fire on a date. Variable triggers can wait for a variable to change or to become a value. End point triggers show their address with Copy.
- **Variables**: value fields match the variable's type (time, date, true/false, number, text). Conditions can compare with another variable or a device reading. The Variables panel shows current values.
- **Check tips**: Check suggests moving a mode, time window or darkness condition into the prerequisite, which is cheaper. It also flags duplicate conditions, IFs that are always true and steps after an EXIT that can never run, each with a one-click fix.
- **Devices**: door lists include door contact sensors. "Chime or siren" is its own step. Audio files from File Manager are offered for track steps.
- **Copy and paste between rules**: copy a step from its ⋯ menu in one rule, then use **+ Add a step → Paste** in another.

### New in 0.5.39

- **Show tips** in the header: tips with an example on steps that are not obvious. On by default.
- **The step menu** keeps rare steps under **More...** (non-standard commands, disabling devices or rules, Private
  Boolean, ping, files, Return a value and the like). Searching still finds every step.
- **Cancel delayed actions** says it cancels the delayed steps in this rule; **Cancel delayed actions in another
  rule** sits beside it.
- **If a step fails** is always shown under the steps, saying when it is not used and how to turn it on.
  **Return a value** says who receives the value, and **Wait for several events** says when it has no events yet.
- **Note** lines have their own colour.
- **Dragging** a line onto an END-IF, END-REP or other end line moves it out of that block, just after it.
- **Running / Stopped**: one switch replaces Stop and Start.
- **Pickers** no longer offer HAI Engine or HAI Migration as a rule or a device.
- **Check** clears its own tips as soon as you fix them, without waiting for the hub's answer.

### New in 0.5.44

- **Conditions in everyday words**: under **+ Add a condition**, **In everyday words** offers **Light level** (dark,
  dusk or dawn, overcast, bright day, or a number of lux) on a light sensor, and **Weather** (hot, cold, raining,
  windy, snowing) from an OpenWeatherMap device. Without one, Weather is shown unavailable with how to add it.
- **The weather device**: weather words read an OpenWeatherMap device. Hubitat has a built-in OpenWeatherMap driver:
  create a Virtual Device with that driver, enter your OpenWeatherMap API key in it, then choose it in HAI Engine under
  **Everyday words**, **Weather device**. Choosing it there also approves it for HAI. With none chosen, the editor uses
  an approved device that reports temperature, wind speed and the weather, as before.
- The levels behind the words are in HAI Engine's settings, under **Everyday words**, to fit where you live. They start at generic values; change them to suit your own climate and preferences:
  lux for dark, overcast and bright, temperatures in your hub's scale, and wind in your weather device's unit. Each box
  shows the level in use: dark under 10 lux, dusk or dawn up to and overcast from 200 lux, bright day 2000 lux or more,
  hot 27 °C (80 °F) or more, cold 4 °C (40 °F) or less, windy 30 or more (20 on a Fahrenheit hub). An empty setting
  means that default. Rules
  follow these settings: after a change, HAI Engine updates every rule that uses a changed word, one at a time, each as
  a new version (a running rule stays running, a stopped one stays stopped), and says on its page how many it updated.
  A draft takes the new levels when it is opened in the editor. A rule reads back with both: "it is dark (Hall Lux illuminance is below 10 lux)".

### New in 0.5.43

- **Search by device name** in **Add a trigger** and **Add a condition**, as in **Add a step**: type part of a
  name, choose the device, and the trigger or condition starts on it.
- **Run now** on a single step, from its **⋯** menu.
- **Explanations** is a tick box beside **Show tips**; both stick when unticked.
- **Delete** replaces **Remove** for a rule on the hub, and the hub controls keep one order: the switch, Pause, Delete.
- **True now / false now** is worked out only from values read in the last minute; older ones are read again first.
- **Attributes**: a condition or trigger lists everything the device reports, standard ones first.
- **Put devices back** restores hue and saturation as well as colour temperature, ending in the mode the light was in.

## Triggers

| Trigger | Starts the rule when | Rule Machine name |
|---|---|---|
| A device reports a value | A device changes, to a value you choose or to any value. Under **More settings** in its form you can compare instead: changes to a value above, below, between or not between numbers, or not equal to one. You can also choose to start on every report, even of the same value, or only on physical events (a switch pressed by hand) or only digital ones (a command from an app). A keypad's **securityKeypad** (armed away, armed home, armed night, disarmed, or any change) and the **lastCodeName** of a lock or keypad (the name of the code just used; choose every report so the same code twice counts twice) work the same way. | Any capability trigger: Switch, Motion, Contact, Presence, Battery, Custom attribute and so on; Physical switch, Digital switch, Physical dimmer level; Security keypad; Keypad codes, Lock codes |
| A reading crosses a threshold | A number goes above or below a value. A first reading is not a crossing. | Temperature, Humidity, Illuminance and other value triggers with >, <, >=, <= |
| Something stays true for a while | A condition becomes true and stays true, without a break, for a time you choose. | "And stays?" (sticky trigger) |
| A button is used | A button is pushed, held, double-tapped or released. | Button |
| A time of day | A time, or sunrise or sunset, with an offset, or the time in a hub variable (a time repeats daily; a date and time happens once). Under **More settings**, only on chosen days of the week, or only on one date (with a year) or every year on a date (without). | Certain Time (with optional date), Time of day, Days of Week |
| A repeating schedule | Every few seconds, minutes or hours; every day, on chosen days of the week, or every month at a time. Choose **Custom** to type a cron string for anything else, such as the third Friday of each month (`0 0 9 ? * FRI#3`). More than once a minute works but keeps the hub busy, so the check warns. | Periodic Schedule, Cron String |
| The mode changes | The hub's mode changes, to any mode or one you choose. | Mode |
| A variable changes | A hub variable, or one of this rule's own such as its Private Boolean, gets a new value. | Variable, Private Boolean becomes true or false |
| Another rule runs this one | Another HAI rule has a "Run another rule" step for this rule. | Rule without triggers, run by Run Rule Actions |
| Another rule is paused or resumed | Another HAI rule, or a Rule Machine rule, is paused, or resumed. A Rule Machine rule's change is noticed within a minute. | Rule paused |
| HSM status changes | Hubitat Safety Monitor is armed away, home or night, is arming (the exit delay), or is disarmed, to any status or ones you choose. | HSM status |
| HSM raises an alert | Hubitat Safety Monitor raises an alert: intrusion (away, home or night, with or without delay), smoke, water, a custom rule, arming, or cancel. | HSM alert |
| The hub reports an event | The hub starts up, reports a severe load, the Zigbee radio goes off or on, the Z-Wave radio crashes, or it reports sunrise, sunset, or the next sunrise or sunset time. | Location event |
| Its end point is called (HTTP) | Something on your network or the internet calls the rule's address in HAI Engine, optionally with a value the steps read as `%value%`. See [Rule end points](#rule-end-points). | Local End Point, Cloud End Point |

**Choosing by device type.** Under **+ Add a trigger** or **+ Add a condition**, the list has a group **By device
type**: Rule Machine's own device classes, such as Contact, Motion, Switch and Power meter, each with the
number of your devices in it. Typing a type in the search finds them. Picking one opens its form with that
attribute already chosen (a number, such as power, starts **A reading crosses a threshold**), and the
device list then shows only devices of that type that actually report it.

**Which attributes are offered.** For a device, the attribute list shows the standard attributes first:
those Rule Machine offers for that kind of device, then everything else the device has, like Rule Machine's
Custom attribute. What the device's driver declares is offered before the device has used it: a new
button offers pushed, held, double-tapped and released straight away, and a reading such as a colour light's
colour or a plug's power is listed greyed as **no reading yet** until the device first reports it. Driver
housekeeping such as `lqi`, `rssi` or `threeAxis` is not offered unless the device reports it. With several
devices, only attributes every one of them has are offered.

**Example:** a rule to turn on the hallway light at night:
- **Trigger:** Hallway Motion becomes active.
- **Only if:** Hallway Night Mode is on.
- **Step:** turn on Hallway Light.

## Conditions

Conditions go in the orange **Only if** box, or inside an **If** step.

| Condition | True when | Rule Machine name |
|---|---|---|
| All of these | Every condition inside is true | AND, with parentheses |
| Any of these | At least one is true | OR, with parentheses |
| One or the other, not both | One of two conditions is true, but not both. Tap the join word between two conditions and choose **one or the other, not both**: just those two go in brackets, and the other joins keep their and or or. (Older rules with three or more joined this way still work: true when an odd number of them are.) | XOR |
| At least ... of these | At least the number you choose of the conditions inside are true, such as at least 2 of 3. Set the number in the group's form, or tap the group's first line in the rule. | (Rule Machine has no direct equivalent) |
| Exactly one of these | One of the conditions inside is true, and no more | (Rule Machine has no direct equivalent) |
| This is not true | The condition inside is false. Or press **NOT** on a condition to turn it round; press it again to take it off. | NOT |
| A device is in a state | A device (or any or all of several) is on, off, above 20, between 18 and 24 and so on. You can ask for a reading no older than a time. | Any capability condition |
| A variable has a value | A hub variable, or one of this rule's own, equals a value | Variable condition |
| Private Boolean is true or false | This rule's Private Boolean is true, or false. Searching "private" or "boolean" finds it | Private Boolean |
| The mode is | The hub is in a mode | Mode |
| The time is between | The time is inside a window, which may cross midnight and use sunrise, sunset or times from hub variables | Between two times |
| The day of the week is | Today is one of the days you choose | Days of week |
| Time since a device last reported | At least, or less than, a time has passed since a device last reported anything, an attribute, or one value of it | Time Since Event |
| The date is | Today is a date, or between two dates, chosen on a calendar. Tick **Every year** for the same days each year; a range such as 1 December to 31 January crosses the new year. | On a day, Between two dates |
| The run was started by | A particular trigger started this run | (Rule Machine has no direct equivalent) |
| Another rule is paused | Another HAI rule, or a Rule Machine rule, is paused, or is not | Rule paused |
| HSM status is | Hubitat Safety Monitor is in one of the statuses you choose | HSM status |
| Light level (In everyday words) | A light sensor reads dark, dusk or dawn, overcast or bright day, or a number of lux you choose. Measured, never worked out from sunrise and sunset | Illuminance condition |
| Weather (In everyday words) | It is hot, cold, raining, windy or snowing, from an OpenWeatherMap device. Without one the choice says how to add it | Capability conditions on a weather device |

**Prerequisite** (**+ Add a prerequisite**, under **Starts when**): triggers count only while it is true, judged as things stood just before the event. Tick **Cancel runs under way** to stop the rule's waits and delays when it turns false. This is Rule Machine's Required Expression. A single trigger can also have its own **Only when this is also true**, under **More settings** in its form: Rule Machine's conditional trigger.

**How HAI evaluates conditions:**
- **Reading order:** conditions are listed top to bottom, one per line, with the join word (**and**, **or**)
  on its own line between them. They are checked in that order, and checking stops as soon as the answer is
  certain.
- **Grouping: AND before OR.** When a list mixes **and** and **or**, the conditions joined by **and** are
  checked together first, as × comes before + in arithmetic. The editor always draws the brackets, so you
  never have to know the rule: listed top to bottom as Back Door is open, **or** the mode is Guest, **and** it
  is Monday, HAI shows and checks Back Door is open **or** (the mode is Guest **and** it is Monday).
- **Changing one join** changes only that join. The brackets move to show the new grouping. A bracket that
  changes nothing (all **and** inside an **and**, or all **or** inside an **or**) is not shown.
- **Rule Machine groups differently.** Rule Machine reads the same top-to-bottom list but treats everything
  after each join as one group: Back Door is open **and** the mode is Guest **or** it is Monday means, in Rule
  Machine, Back Door is open **and** (the mode is Guest **or** it is Monday). HAI would read that list as
  (Back Door is open **and** the mode is Guest) **or** it is Monday. A rule copied from Rule Machine keeps
  Rule Machine's meaning: HAI writes the brackets in, so it behaves exactly as before, and the editor adds a
  short note under the condition. If that is not what you meant, change the joins in the editor.
- **One or the other, not both** joins two conditions only. Choosing it between two conditions puts those two
  in brackets of their own: A and (B or C, but not both).
- **At least ... of these** and **Exactly one of these** show a first line, such as "At least 2 of these are
  true", with their conditions indented below it, and no and or or between them. Every condition inside is
  checked. If some cannot be checked, the answer is still given when it cannot change: at least 2 of three,
  with two true, is true whatever the third turns out to be; otherwise it counts as unknown (see below).
- **Unknown values:** if a condition cannot be checked, for example because a sensor has not reported,
  the rule does nothing by default. **Rule settings** can change that.

## Steps

| Step | Does | Rule Machine name |
|---|---|---|
| Send a command | Turn on, off or toggle, set a level (with a fade time), set a colour temperature, lock or unlock, sound or silence a siren, push a button, send a notification, speak, set the volume, mute or unmute, sound a tone or chime, set a colour, refresh; flash, change a level by an amount, toggle a dimmer to a level, start raising or lowering and stop; open, close, position or stop shades; open or close valves; set or cycle fan speed; set or change thermostat setpoints, set thermostat mode and fan mode; play, pause, stop, skip or play a track on a music player; poll; toggle a colour (off if on, otherwise the colour) | Toggle color, Turn switches on/off, Toggle, Set dimmer, Set color temperature, Lock/Unlock, Control siren, Push button, Send a message, Speak, Refresh devices, Flash, Adjust dimmers, Toggle dimmers, Start/Stop raising or lowering, Open/Close shades, Set shade position, Open/Close valves, Set fan speed, Cycle fans, Set thermostats, Control music player, Poll devices |
| Set a hub variable (or this rule's variable) | Sets a variable to a value, or works one out: maths, adding, a device's reading, another variable, text operations (replace, remove, split, lower case, URL encoding), comparisons, and, or, not, the current hour or minute | Set Variable |
| Wait for a time | Pauses the steps. Under More settings: a different length in some modes (or none), a random extra time, and whether **Cancel delayed actions** can cancel it | Delay Actions, with Delay per mode, random delay and Cancelable |
| Wait for a device | Waits for the next event on a device, for as long as it takes, as Rule Machine does. Tick **Give up after a while** for a limit: when it runs out the rule carries on, unless you choose otherwise | Wait for Events |
| Wait for events | Waits for any one, or all, of several events: a device reporting, a button, the mode changing, a time of day, a variable changing, or something staying true for a while ("and stays"). No time limit unless you set one, as above | Wait for Events, with any or all, and stays |
| Wait until a condition | Waits until a condition is true, which may already be so. No time limit unless you set one, as above. Under **More settings**, **And it stays true for** makes it wait until the condition has stayed true for a while | Wait for Expression, with duration |
| Do steps later | Runs some steps after a delay while the rest of the rule carries on. A new trigger does not cancel them. The delay can differ by mode, have a random part, and be cancelable | The delay on an individual action |
| Cancel delayed actions | Cancels this rule's delays and later steps marked cancelable | Cancel Delayed Actions |
| New IF block (**+ Add flow**) | Branches on conditions | IF (expression) THEN, ELSE-IF, ELSE, END-IF |
| Repeat (**+ Add flow**) | Runs some steps now and again every so often, a number of times or until stopped | Repeat Actions (every n, n times, stoppable), END-REP |
| Repeat while, Repeat until (**+ Add flow**) | Runs some steps again and again while a condition is true, or until one is; optionally every so often | Repeat While Expression, Repeat Until Expression, END-REP |
| Stop repeating (**+ Add flow**) | Ends this rule's repeats before their next time round | Stop Repeating Actions |
| Depending on the mode (**+ Add flow**) | Runs the steps for the modes the hub is in now, with steps for any other mode | Set switches, dimmers, colours or delays per mode; Choose switches per mode |
| Flash switches | Turns any switches on and off, each for a few seconds, a number of times or until stopped | Flash switches |
| Fade a level or colour temperature over time | Moves lights from where they are to a level or colour temperature, a step at a time, over minutes or hours | Fade dimmer over time, Change color temperature over time |
| Stop fading or flashing | Ends this rule's fades or flashing | Stop fade, Stop changing color temperature |
| Follow the device that started the run | Makes lights go on and off with the device that fired the trigger, or match its level | Track event switch, Track event dimmer |
| Run another rule | Runs another HAI rule. It can wait for the rule to finish and keep the value that rule returns in a variable. | Run Rule Actions; Rule Functions |
| Return a value | Ends the run and gives a value back to the rule that ran it and is waiting | Rule Function's return |
| Exit rule (**+ Add flow**) | Ends the run at that step, usually inside an IF; it reads **EXIT RULE** | Exit Rule |
| A note | A comment in the steps. It does nothing. | Comment |
| Write to the log | Writes a line to Hubitat's Logs page | Log |
| Remember how devices are set, Put devices back as remembered | Saves lights' on or off, level and colour, and later puts them back | Capture Devices, Restore Devices |
| Use a non-standard device command | Sends a command only that device's own driver offers, such as infrared on a light or a scene on a controller, by its driver's name. Use it when the command is not in **Do something to a device**'s list. With several devices, only the commands every one of them offers are listed. A driver with more than 200 has the rest left off the list, and the help says how many; **Enter another command** lets you type one by name. Refused only if that device's driver does not offer the command. | Run Custom Action |
| Change the mode | Changes the hub's mode. It commands no device, but every automation on this hub reads the mode, so it has its own switch: turn on **Allow rules to change the mode** on the HAI Engine page. Reading the mode, and starting a rule when it changes, never need that. | Set Mode |
| Arm, disarm or cancel alerts with HSM | Arms Hubitat Safety Monitor away, home or night, or all; disarms it, or all; arms or disarms its custom rules; or cancels its alerts. Nothing approves it and nothing fences it; what stops an HSM step is that nobody wrote one into a rule. It is listed here so you know what it reaches. | Arm/Disarm HSM, Cancel HSM alerts |
| Send an HTTP request | Calls a web address with GET or POST, with headers and a body if you like. It can keep the answer, or one part of a JSON answer such as `data.temperature`, in a variable; then the rule waits for it, up to 10 seconds unless you change it, and a failed call stops the step. The hub is never held up while it waits. | Send HTTP GET, Send HTTP POST, Set Variable from HTTP response |
| Ping an address | Checks whether an IP address answers, and can keep how many packets came back, the loss and the round-trip times in variables | Ping IP address |
| Write, add to or delete a file | Writes text to a file in the hub's File Manager, adds it to the end, or deletes the file. A variable can be set from a file's text with the Set a variable step's **file** operation. HAI's own files (names starting `hai-`) are never touched. | Write to local file, Append to local file, Delete local file, Set Variable from file |
| Set a rule's Private Boolean | Sets the true or false flag of this rule, other HAI rules or Rule Machine rules | Set Private Boolean |
| Pause, resume or cancel a rule | Pauses or resumes other rules, cancels their waits and delays, or runs a Rule Machine rule's actions | Pause Rules, Resume Rules, Cancel Rule Timers, Run Rule Actions (for Rule Machine rules) |

**Several devices at once:** a command can go to several devices in one step, one after another. You can
add a short pause between them (Rule Machine's metering), and choose to change only the lights that are
already on. A step can also act on **the device that fired the trigger**, for example turning off whichever
switch was just turned on. Locks, doors and sirens always need a named device.

**Colours:** a colour command offers the usual colours as circles and a colour wheel: go around it for the
hue and out from the centre for saturation, in Hubitat's 0 to 100 scales; the hue and saturation boxes move with it.

**Values from variables:** a command's setting, such as a level, colour, chime number or fade time, can
come from a variable instead. It is read when the step runs, and a value outside the allowed range stops
the step rather than sending something unexpected.

**Values in messages:** a notification, something to speak, or a log line can include `%device%`,
`%value%` and `%text%` (what started the run, such as "Hallway Motion motion is active"), `%date%`,
`%time%` and `%now%`, and any variable by name, such as `%Hall Level%`. They are filled in when the step
runs. Rule Machine uses the same markers. Add your own date format after a colon, as in
`%date:EEE d MMM%`. After a **Wait for a device** or **Wait for events** step, `%device%` and `%value%`
describe what it saw, and read `timeout` if it gave up. `%elapsed%` is how many seconds the last wait took.
A rule's name can contain a hub variable too, like `Heating (%Target%)`.
The same markers work in an HTTP address, headers and body, a ping's address, and a file's name and text.

**HTTP, ping and files:** HTTP requests and pings may reach any address, and file steps may change any File
Manager file except HAI's own. One address is still refused: a rule reaching one of the hub's own restart,
shutdown, update, restore, reset, backup or delete pages through a value filled in when the rule runs, because
then nobody saw which page it would call. To call this hub from one of its own rules, use
`http://127.0.0.1:8080/...`: the hub's own LAN address on port 80 does not answer a call from an app on the
same hub, and the step fails with 408.

**Disable** (in a step's or trigger's **⋯** menu) keeps it in the rule but skips it, which is handy for
trying things out. Rule Machine calls this disabling an action. HAI also lets you disable a trigger.

**Rule settings,** under the flow:
- **Triggered again while running:** start over, ignore the new trigger, queue it, or run both at once
  (with a limit).
- **A step fails:** stop, carry on, or run separate failure steps.
- **A condition can't be checked:** do nothing, or carry on.
- **If a device in an IF can't be read:** stop the run (the default), or run the ELSE. See
  [When a device in an IF can't be read](#when-a-device-in-an-if-cant-be-read).
- **When conditions are read:** when the trigger happens, or when the run starts.
- **Run once on activation:** run the rule once when it's activated.

### When a rule is triggered again while it is running

By default, a new trigger **starts the rule over**: the run in progress is cancelled, its delays and waits
included, and a new run starts from the first step. The steps the old run had not reached never happen.

A motion light shows why. The rule is: when Hall Motion becomes active, turn on Hall Light, wait 5 minutes,
turn off Hall Light.

- Someone walks past at 8:00. The light comes on and the rule waits.
- They walk past again at 8:03. HAI cancels the first run, so its "turn off" at 8:05 never happens, and
  starts again: the light stays on and a new 5 minutes begins.
- With no more motion, the light goes off at 8:08, 5 minutes after the last movement.

Rule Machine does not cancel. It runs the rule again for each trigger, and each run keeps its own delay, so
the light goes off at 8:05 while someone may still be there, and is told to go off again at 8:08. On a light
the second "off" changes nothing, but on a notification, a counter or a toggle it is the difference between
doing something once and doing it twice.

To change it, open **Rule settings** and pick another choice for **If triggered again while running**:
ignore the new trigger, queue it, or run alongside, which is what Rule Machine does. A rule copied from Rule
Machine keeps Rule Machine's way, and the migration page offers starting over as an upgrade (see
[Moving from Rule Machine](#moving-from-rule-machine)).

### When a device in an IF can't be read

Sometimes an IF cannot be answered: the device is offline, was removed, or has never reported the reading.
Each rule chooses what happens then, under **Rule settings**, **If a device in an IF can't be read**:

- **Stop the run** (the default). Neither the THEN nor the ELSE runs. The IF counts as a step that failed,
  with a "could not be checked" error in the run timeline, so **If a step fails** decides what happens next:
  stop there (the default, so nothing after the IF runs), run the failure steps, or carry on with the step
  after the IF.
- **Run the ELSE.** The condition counts as not met, as Rule Machine does. HAI tries the next ELSE-IF, then
  the ELSE, and carries on after the IF. The run timeline, and the hub log when **Log actions** is on, say
  the reading was missing and that the ELSE ran because of it.

Which is right depends on what the ELSE does:

- **An ELSE that is a safety catch should run.** "IF the bathroom heater's temperature is below 22 THEN keep
  the heater on ELSE turn the heater off": if the thermometer stops reporting, turning the heater off is the
  safe thing, so choose **run the ELSE**.
- **An ELSE that claims something is true should not.** "IF any window is open THEN announce which one ELSE
  announce all clear": if a window sensor cannot be read, nobody knows it is all clear, so keep **stop the
  run**.

A rule copied from Rule Machine is set to **run the ELSE**, as Rule Machine behaves, and the migration page
offers **Stop instead of running the ELSE when a reading is missing** as an upgrade.

**Repeats:** the steps inside a repeat run, then run again, and the steps after the repeat run once it
ends. **Repeat every** counts from the start of each time round. **Repeat while** checks its condition
before each time round, so it may not run at all; **Repeat until** checks it before each time round after
the first, so its steps always run at least once. Without an interval, one of a while or until repeat's own
steps must be a wait, and it goes round at most 1000 times. A **Stop repeating** step ends every repeat of
the rule: one waiting for its next time round ends at once, and its run carries on after it. Run the stop
from another trigger with **Triggered again while running** set to run both at once, or put it inside the
repeat. Pausing a rule holds its repeats (when paused runs wait) or ends them at their next time round;
stopping the rule, cancelling its timers, or a new run that starts over ends them too.

**Fades and flashing** run beside the rule: the steps after them carry on at once, as in Rule Machine.
A new trigger does not stop them; **Stop fading or flashing**, stopping or pausing the rule, or cancelling
its timers does. A new fade or flash of a light replaces the one it had. They carry on after a hub restart
from where they were. Flashing with a number of times ends with the switches off.

**Room Lighting:** turn a Room Lighting instance on or off through its activator device, a switch it
creates for you. Activating it for one chosen mode or period is not something another app can ask Room
Lighting to do, so HAI has no step for it.

**Waits and hub restarts:** waits, delays and steps set aside for later survive a hub restart. By default,
after a restart a waiting rule checks its conditions again with fresh readings before carrying on. Time the
hub was down never counts toward "stays true for": the time starts again at the next fresh reading.

## Variables

**Hub variables** are Hubitat's own, under Settings, Hub Variables. They are shared with dashboards and
other apps.
- **In the editor:** the variable list shows each one with its type and current value.
- **Reading and starting:** a rule can read any hub variable and start when one changes.
- **Changing:** a rule can change one only if it is chosen under **Approved hub variables**, in
  the **Mode and approved hub variables** section of the HAI Engine page. The others show as "read only".
- **Whole numbers:** a Number hub variable holds whole numbers, and HAI refuses to write 2.5 into one.
- **In use:** a rule that uses a hub variable appears in that variable's "in use by" list on the Hub
  Variables page.
- **Renames:** if you rename a variable there, the rule follows the new name.
- **Connectors:** a hub variable's connector (a device such as a switch that stands in for it) shows in the
  device list as the connector for its variable. A rule can use it, but HAI suggests the variable itself, and
  Automation Map shows it as the variable.

**Private Boolean:** every rule has one, a true or false flag that starts true. Any rule can set it, and
a rule can use its own in a condition or start when it changes. For a condition, choose **Private Boolean
is true or false** under **+ Condition** (searching "private" finds it), then **True** or **False**. It is
also in the variable list, under **Which variable**. Rule Machine rules have one too, and HAI can set theirs.

**This rule's own variables** belong to one rule and keep their value between runs. They are Rule Machine's
local variables. To add one, choose **New variable for this rule** in the variable list, name it, and pick
its type.

## One rule running another

1. Give the rule to be run the trigger **Another rule runs this one**.
2. In the other rule, add the step **Run another rule** and choose it.

**What happens:**
- The called rule's own conditions and "triggered again" setting still apply.
- The calling rule carries on without waiting, unless you tick **Wait for it to finish**. Then it waits,
  up to the time you give, and can keep what the other rule returns (with a **Return a value** step) in a
  variable. You can also pass it one value, **With the input**, which it reads as its own variable **Input**.
  This is Rule Machine's Rule Function. If the other rule does not run at all, for example
  because it is paused, the caller carries on straight away.
- Chains of rules calling rules are limited in depth, so a loop cannot run forever.

**Acting on other rules:** the step **Pause, resume or cancel a rule** pauses or resumes other rules, or
cancels their waits and delays. It works on HAI rules and on your Rule Machine rules. For a Rule Machine
rule it can also run its actions.

## Running, pausing and stopping

| You press | What happens |
|---|---|
| Put on hub | The rule is saved, checked and activated on the hub, usually within seconds. It then runs when its triggers happen. |
| Update on hub | The changed rule replaces the running version. The old version is kept in history. |
| Run now | The rule's steps run once, straight away, without a trigger and without checking its conditions. Rule Machine calls this Run Actions. A paused rule does not run. |
| Pause | No new runs start. A run already going finishes, or, if **When paused, runs already going** is set to wait, it stops where it is and carries on from there when you resume. Rule Machine always does the second. |
| Resume | Runs start again. |
| Running / Stopped (to Stopped) | The rule stops acting and its subscriptions are removed. It stays on the hub. **Deactivate** under **Stop a rule** on the HAI Engine page does the same. |
| Running / Stopped (to Running) | A stopped rule starts again. |
| Delete | A stopped rule's app is deleted from the hub, after you confirm. See [Deleting a rule](#deleting-a-rule). |

**Logging:** in the rule's settings, **Log events**, **Log triggers** and **Log actions** write a short line
to Hubitat's Logs page for each event the rule receives, each run starting and ending, and each step, as Rule
Machine's logging options do. The run timeline records all of this either way. Off by default.

**If triggered again while running:** set it to **ignore** for Rule Machine's "Ignore trigger events while
running": a trigger that arrives while a run is still going, its delays and waits included, is ignored.

**From other apps:** with **Let other apps on this hub run HAI rules** switched on under **Rule end points** on the HAI Engine page (it is on unless
you turn it off), another app can list your
HAI rules and run, stop, pause, resume or trigger them, or set their Private Boolean, as apps do with Rule
Machine's RMUtils (docs/developer/api.md). Each request is logged. It cannot activate, change or remove a rule.

**The other app needs HAI's API token (changed 13 October 2026).** Any app on your hub can send these
requests, so each must now carry the API token shown under **Rule end points** on the HAI Engine page. A request
without it is ignored, and HAI writes one warning to the Logs page for that app ("ignored a request from ... it
carries no API token").

**If you already use another app with HAI, update it:**
1. Open **HAI Engine**, then **Rule end points**, and press **Copy** beside **API token for other apps**.
2. Paste the token into the other app. An app built on the HAI Rule API library has a **HAI API token**
   setting for it; first update the library under **Libraries code** to the current `hai_rule_api.groovy`. For
   any other app, ask its author to send the token as `token` in each request.
3. Try one request, such as listing your rules. If the Logs page still says "ignored a request", the token was not
   copied whole.

Opening a rule's app ("[HAI] <name>") from Apps or a device's In Use By shows its status, version, when it
was created, last changed and last ran, and the link **Open this rule in the editor**.

## Rule end points

Like Rule Machine's end points, another system can act on your HAI rules with a web address. The
**Rule end points** section of the HAI Engine page shows the local address. It carries HAI Engine's access
token, which also opens the editor, so keep it private.

| Add to the address | What happens |
|---|---|
| `/endpoint/rules` | Lists the rules on the hub, with whether each is paused |
| `/endpoint/rules/<rule id>/run` | Run now, as the button does |
| `/endpoint/rules/<rule id>/stop` | Ends its running runs and their waits and delays (Rule Machine's Stop Rule Actions). The rule stays active; Stop in the editor deactivates it. |
| `/endpoint/rules/<rule id>/pause`, `/resume` | Pause, Resume |
| `/endpoint/rules/<rule id>/boolean/true` or `/false` | Sets its Private Boolean |
| `/endpoint/rules/<rule id>/trigger`, or `/trigger/<value>` | Starts a rule that has the trigger **Its end point is called**, with the value as `%value%`. Its conditions still apply. |
| `/endpoint/variables/<name>/<value>` | Sets a hub variable, only one chosen under **Approved hub variables** |

**What the answer means (changed 27 September 2026).** An end point answers **`202`** with
`accepted: true` and `applied: "pending"`. It is telling you the request was accepted and sent, not that
it has been carried out: HAI Engine answers before the outcome exists. There is no `ok` field any more:
it used to say `true` at that moment, which read as "done" and sometimes was not. Match the `sentAt` in
the answer against `lastSubmit` at `/editor/api/runtime` to find out what actually happened, or look at
the editor's **On the hub** panel.

One refusal is immediate: setting a hub variable rules are not allowed to change answers **`404`**
`NOT_WRITABLE` and names the variables that may be changed, so you learn at once rather than from
silence. Choose them under **Approved hub variables** on the HAI Engine page.

End points cannot activate, stop (deactivate) or remove a rule. Removing a rule that is still running, by
stopping it first, is a request only the editor can sign; an end point or other caller cannot make it.

## Changing HAI's access addresses

Use this if you think someone has a link to HAI they should not have, for example from a screenshot, a shared
message or an old phone. Open **HAI Engine**, then **Change access addresses**, and press **Change HAI's access
addresses**. Every HAI link changes at once and the old ones stop working. Your rules keep running as before.

What stops working until you update it: bookmarks and saved links to the HAI editor, editor pages you already
have open, and any dashboard, shortcut, AI assistant or other app that controls HAI. Open the editor from the
HAI Engine page again, and give those other things the new links shown on that page.

### Technical

In one press, HAI makes new:
- editor, end point and MCP addresses
- the API token for other apps
- the address of the Rule Machine Migration page

Each rule's **Open this rule in the editor** link is updated within a few minutes.

Everything that used the old ones stops working: editor tabs that are open, saved links and bookmarks,
dashboard buttons and phone shortcuts that use an end point, your AI assistant's MCP address, and other apps
that send the API token. Copy the new address or token to each place you use it.

## How big a rule can be

| | |
|---|---|
| A single text value inside a rule | up to 65,536 characters |
| The rule you send | up to 16,000 characters |
| What HAI stores for each rule | your rule and the version you can roll back to, up to 16,384 bytes each, plus 29,971 bytes of room to run in, enough to keep the last 3 runs of even a long rule - 62,739 bytes in total |

**Anything the editor lets you send is a rule the hub can run.** That sentence is the whole point of the
third row, and it was not true before 27 September 2026.

For scale: the 62 Rule Machine rules on the hub this was built against convert to HAI rules of between
92 and 9,036 bytes, with half of them under 1,543. A rule twice the size of the largest real one still
fits.

These are HAI's limits, not Hubitat's. Hubitat will hold far more; HAI bounds it so that one rule cannot
spend the hub on itself. If a rule ever is too large, the refusal says so, gives both numbers, and the
rule stays where you can shorten it.

### What went wrong before, and what to do if you saw it

Two faults, both fixed on 27 September 2026:

- **A rule over about 4,096 characters was lost silently** on its way to the hub. The editor said it was
  sent and nothing arrived. If you have a rule that vanished, send it again.
- **A rule of ordinary size could stop running and never start again.** HAI counted the stored rule
  against the room the rule needed to *run*, and reserved nothing for the record of its own runs. A rule
  of about 7,000 characters would be accepted, run once or twice, and then refuse every trigger it was
  subscribed to - permanently, through a hub restart, with no way back except deleting it and making it
  again. A rule now stops taking **new** work when it is short of room and recovers on its own once its
  history is trimmed, and a run already under way is never abandoned part-done.

## Troubleshooting

- **On the hub** (right, in the editor) lists what the rule did recently, and why each run started. If
  the rule could not be activated, the reason is shown there too.
- **The rule's own app** ("[HAI] <name>", in Apps) shows its status and version, and when it was created,
  last changed and last ran.
- **Health and diagnostics** on the HAI Engine page says whether the app is alive and how many rules are on
  the hub. **Turn diagnostics on for an hour** records far more detail, and turns itself off an hour later.

**What a command's result means:**

| Result | Meaning |
|---|---|
| Confirmed | The device reported the value the command set. |
| Unknown | The device gave no way to confirm it. Notifications and button pushes always end here, and so does a command that a later one overtook. |
| Mismatch | The device reported something else. HAI does not retry commands whose repeat is unsafe. |

### Refusals you are likely to meet, and what to do

HAI refuses things on purpose, and says so by name. These are the ones you are likely to see:

| What you see | What it means | What to do |
|---|---|---|
| `RUNTIME_DEVICE_NOT_AUTHORISED` | The rule names a device that is not among the devices you selected | Add it under **Approved devices** on the HAI Engine page |
| `REF_MISSING` | The rule names a device, mode, variable or rule this hub does not have, or a device HAI may not use | Select the device under **Approved devices**, or fix the name in the rule |
| `VARIABLE_NOT_WRITABLE` | The rule changes a hub variable rules may not change | Choose it under **Approved hub variables** on the HAI Engine page |
| `DEACTIVATE_FIRST` | A rule was asked to be removed while it is still active. You should rarely see it: the editor greys **Delete** out until the rule is stopped | Press the **Running** switch so it reads **Stopped**, then **Delete** |
| `CANDIDATE_LIMIT` | HAI holds a copy of each rule it was sent but could not put on the hub, up to 12. When 12 different rules are held, all from the last day, a new rule is refused. A held copy clears itself a day after it arrived, once room is needed | Wait for the oldest to clear, or fix one of the held rules and put it on the hub again: its new version replaces its held copy |
| `SCHEMA_UNKNOWN_FIELD` | A rule sent by hand or by an AI client uses a field HAI does not know. HAI refuses rather than silently ignoring it | Correct the field. The editor cannot produce this |

If a rule is **active, subscribed and silent** — it should have run and there is no trace of it — look at
**Logs → Past logs**, filtered to that rule's app. A step that is refused as it runs, rather than at
activation, reports there and nowhere else. That is a fault in HAI, not in your rule: please report it.

## Moving from Rule Machine

**New to moving rules?** Start with [Moving your Rule Machine rules to HAI](migration-guide.md): the steps
from Assess to Switch over, what each next step in the Conversion report means, the order to move rules in, and
the choices about how a copy behaves. This section covers installing the app and what the page shows.

Migration is a separate, optional app, **HAI Migration**, for people who want to move Rule Machine rules.
HAI Engine works the same without it; its **Rule Machine** section then says "Install HAI Migration to
migrate Rule Machine rules" and nothing else changes.

To install it: add `dist/hai_migration_app.groovy` as a new app code (**Developer tools → Apps code → New
app**, paste, **Save**), turn on **OAuth** for it, then on the HAI Engine page press **Add HAI Migration**.
The HAI Migration app then appears inside HAI Engine; its page has the link to the migration page. HPM
offers it as an optional app of the package. It uses the same `hai-migration.html` File Manager file.

**Rule Machine Migration Facility**, in the **Rule Machine** section, opens HAI Migration's page; its link
opens a page listing every Rule Machine rule on the hub. It reads what each rule does from Automation Map's
decode of your rules, so Automation Map must be installed and must have scanned; the page says how old that
scan is. When both the production and the Dev Automation Map have written a decode, it reads the one from
the newer Automation Map version, and says which file and version it read.
Without a decode the page still lists the rules and which side is running, with no reading of what each does.

For each rule the page shows:

- **What HAI makes of it:** **Ready to read**, **Needs a decision**, **Cannot be read** or **Not
  assessed**, how involved the rule is in words, and the reasons. Where HAI can rebuild the rule's
  conditions it says so and shows them in HAI's own words, beside a link to the rule in Rule Machine.
- **Warnings that reach beyond the rule:** other rules that reach into this one (move it alone and they go
  on commanding the paused Rule Machine copy), and devices it uses that are not selected under **Devices
  rules may use**.
- **Which side is running:** **Rule Machine**, **HAI**, **Both at once** or **Nothing running**.

And it offers these steps, each greyed out with the reason when it cannot be used:

- **Create a disabled HAI copy** builds an HAI rule from the Rule Machine rule as it stands at that
  moment and puts it on the hub stopped. The copy is not running, and the Rule Machine rule is not touched.
  Actions that are turned off in Rule Machine are left out, as Rule Machine leaves them out. The page says
  what happened, and the row then shows the copy. If HAI cannot set the copy up on the hub (a device it uses
  is not selected, for example), the copy is held for review, the page says why, and **Switch over** starts
  it.
- **Switch over to HAI** pauses the Rule Machine rule, checks that it really has paused, and only then
  starts the HAI copy. If the pause cannot be confirmed, HAI is not started.
- **Switch back to Rule Machine** stops the HAI copy, then resumes the Rule Machine rule.
- **Deleting either side** is not done here. HAI never deletes a Rule Machine rule; delete it in Rule
  Machine when you are ready. Delete the HAI copy in the editor.

**A copy that converts is not proof that it behaves identically.** No converted rule has been run side by
side with its Rule Machine original. Check the read-back before you rely on one.

**The one rule that matters: never leave two engines driving the same devices.** A Rule Machine rule and
an HAI rule watching the same motion sensor and commanding the same light will both fire, and the result
is whichever ran last. It looks like a flickering light or a rule that "sometimes does not work".

A changeover that stays safe, one rule at a time:

1. **Read the Rule Machine rule** and write the same intent in HAI. The table at the end of this guide
   gives the HAI name for each Rule Machine feature.
2. **Check the read-back, not the diagram.** HAI describes in plain English what it will do. If that
   sentence is not what you meant, the rule is wrong however right the flow looks.
3. **Pause the Rule Machine rule** — do not delete it. It stops acting and keeps its definition.
4. **Put the HAI rule on the hub** and live with it for a few days. Both rules exist; only one is acting.
5. **If it misbehaves**, stop the HAI rule with its **Running** switch and resume the Rule Machine one. You are back where you
   started in seconds, which is why step 3 is a pause.
6. **Delete the Rule Machine rule** only once you are sure.

Two things to check before you start:

- **Hub variables are shared.** Both engines read and write the same variables, so a half-migrated pair
  can fight over one. Migrate rules that share a variable together.
- **Four Rule Machine features are not fully there** (the table at the end of this section): two HAI does
  not have at all — disabling a device, and starting or stopping the hub's Z-Wave poller, neither of
  which Hubitat lets an app do — and two it does partly, thermostats and Room Lights. If a rule leans on
  one, leave it in Rule Machine for now. Everything else in Rule Machine 5.1 has an HAI equivalent.
- **Two behaviours differ even where the feature exists**, and neither announces itself:
  - a **retrigger runs the rule's actions again in Rule Machine, and restarts them in HAI**. Rule Machine
    runs the whole action list once per trigger, so a rule retriggered three times during a delay does its
    later steps three times; HAI, by default, does them once. On a light nobody notices, but on a
    notification, a counter or a file it is the difference between three and one. **Set the rule's re-run
    behaviour to "run them side by side" to match Rule Machine** — tested against Rule Machine on a hub, and the
    only setting that does;
  - a device trigger fires **when the value changes**, where Rule Machine fires on every report of it. If
    your rule depends on repeats, switch the trigger to **start on every report**.

  A rule copied by the migration page keeps Rule Machine's behaviour in both, and in a third: when a
  device in an IF can't be read, the copy runs the ELSE, as Rule Machine does. Each is offered as an
  upgrade on the page, with a tip saying when it is the better choice.

  `docs/developer/rm-semantics.md` lists every behavioural difference known, and is honest about which are
  tested and which are only claimed.

## Locks, sirens, garage doors and HSM

These used to be fenced: a lock had to be commissioned for entry control, a garage door asked a person
before every single opening, and HSM waited for an approval and for a test-device switch to be turned off.
All of that was removed on 2026-09-27.

**There is one permission: the device picker.** A rule may use any device you selected under **Devices
rules may use**, with any command that device's driver offers. A lock, a siren and a garage door are
devices like any other. If you do not want a rule commanding your front door lock, do not select the lock.

The reasoning, stated plainly: no other app on this hub asks for more than the
device picker, Rule Machine included. A second permission that only HAI asked for was not protecting
anything Rule Machine was already free to do, and it cost real confusion - including a build that silently
refused every real device because the page holding the switch had been deleted.

**What still refuses a step**, and both are real rather than ceremonial:
- a device the rule names that you have not selected;
- a command that device's driver does not offer.

Changing the hub's mode keeps its own switch (**Allow rules to change the mode**), because it commands no device
but every automation on the hub reads it.

**A rule's description never promises an approval.** If you open an old rule that still carries an
`approval` field from before this change, HAI keeps the field so the rule still loads, ignores it, and does
not tell you the command will wait for anyone. It will simply run.

## Moving rules between hubs

- **Exporting:** **⋯**, then **Export for another hub**, saves a file.
  - **What it holds:** each device is described by name, room and what the rule needs from it, never by
    this hub's device numbers.
- **Importing:** **⋯**, then **Import a rule**, on the other hub, opens the rule unsaved.
  - **Matched devices:** a device that matches one on that hub by name is filled in, and you are asked to
    check it.
  - **Unmatched devices:** anything else shows as a problem for you to choose.
  - **Nothing runs by itself:** nothing runs until you press **Put on hub**.
- **Duplicate** (under **⋯**) makes a copy of the rule on this hub, as a separate rule.

## Rule Machine names and HAI names

| Rule Machine | HAI |
|---|---|
| Trigger events | Triggers (blue) |
| Required expression | Only if (orange). Like Rule Machine, HAI judges it as things stood **just before the event**, so a trigger that is itself what makes the expression false still runs. |
| Conditional trigger | An If step using "The run was started by" |
| Actions to run | Steps |
| IF (expression) THEN ... END-IF | If, with THEN and ELSE columns |
| Delay Actions | Wait for a time |
| Repeat Actions, Repeat While, Repeat Until, END-REP | Repeat every, Repeat while, Repeat until: the steps to repeat sit inside the block |
| Stop Repeating Actions | Stop repeating |
| Delay on an action | Do steps later |
| Cancel Delayed Actions | Cancel delayed actions |
| Per-mode actions (set switches, dimmers, colours, delays per mode) | Different steps in each mode |
| Track event switch, Track event dimmer | Follow the device that started the run |
| Wait for Events, Wait for Expression | Wait for a device, Wait for events, Wait until a condition |
| Local variables | This rule's variables |
| Run Rule Actions | Run another rule |
| Pause Rule, Resume Rule | Pause, Resume; from another rule, Pause, resume or cancel a rule |
| Private Boolean | Private Boolean, in the variable list |
| Stop, Start | Stop, Start |
| Local End Point, Cloud End Point | Rule end points in HAI Engine, and the trigger "Its end point is called" |
| Display current values, logging | Current values, Read-back, Simulate, On the hub, the Run timeline |
| Copy and paste actions | Copy, and Paste after this, in a block's menu |

<!-- hai-status:begin -->
Of Rule Machine 5.1's **140 capabilities**, HAI has **implemented 140**: **140 tested** on the hub and 0 built but not yet tested. 0 are scoped but unbuilt. HAI's own 18 capabilities are counted separately.

What HAI has not built yet:

| Feature | Why not yet |
|---|---|

These figures are generated from the same file as the hub's capability feed; do not edit them by hand.
<!-- hai-status:end -->

## Appendix: Quick start

A short path through HAI for a first afternoon. Each step links to the full section.

**1. Install.** Add the three app codes, turn on OAuth for HAI Engine, upload the five files to File
Manager, and add HAI Engine as a user app. The steps are under [Installing HAI](#installing-hai).

**2. Choose what rules may use.** Open **HAI Engine** in Apps. Check **Approved devices** (a new install
approves every device; untick any HAI must leave alone), and the mode and hub variable settings below it.
See [Getting started](#getting-started).

**3. Open the editor.** On the HAI Engine page press **Open the rule editor**. Bookmark the page it opens.

**4. Make a first rule.** Press **New rule**, name it, and build it from three headings:

- **Starts when**: the trigger, for example "Front Door opens".
- **Only if**: optional conditions, for example "between sunset and sunrise".
- **Then, in order**: the steps, for example "Turn on Hall Light, wait 5 minutes, turn off Hall Light".

Or press **AI Rule Assistant** and say what the rule should do in your own words; Claude drafts it for you
to read and change. This is optional and needs your Claude API key under **AI token and cost management** on
the HAI Engine page. See
[The editor](#the-editor) and [Drafting with AI and privacy](#drafting-with-ai-and-privacy).

**5. Check it.** The **Check** panel lists anything that must be fixed, line by line, and reads the rule back
in plain English. A line tinted red needs attention before the rule can go on the hub.

**6. Put it on the hub.** **Put on hub** saves, checks and starts the rule, usually within seconds. From then
on it runs whenever its trigger happens. After a change, the same button reads **Update on hub**.

**7. Try it.** **Run now** runs the steps once, straight away, without the trigger and without the
conditions. **On the hub**, beside the rule, lists what it did recently and why each run started.

**8. Pause or stop.** **Pause** stops new runs until you press **Resume**. **Stopped** removes the rule's
triggers but keeps it on the hub; **Running** starts it again. If the editor will not open, **Deactivate**
under **Stop a rule** on the HAI Engine page stops a rule from the hub. See
[Running, pausing and stopping](#running-pausing-and-stopping).

**9. Backups and restore.** Each night, if any rule changed, HAI writes all its rules to one backup file in
File Manager and keeps the last ten. In the editor, **More > Backups...** lists them: **Back up now**,
**Download**, **Compare** with the rules on the hub, and **Restore this rule...** or **Restore all**. See
[Rule backups](#rule-backups).

**10. Move a rule from Rule Machine.** Install Automation Map 2.4.36 or later and let it scan your hub. On
the HAI Engine page press **Add HAI Migration**, then open **Rule Machine Migration Facility**. For each rule
it says whether HAI can copy it and what stands in the way. A copy starts stopped; **Switch over** pauses
the Rule Machine rule and starts the copy, and **Switch back** reverses it. See
[Moving from Rule Machine](#moving-from-rule-machine) and the step-by-step
[Moving your Rule Machine rules to HAI](migration-guide.md).

**11. Getting help.** When a rule did not do what you expected, start with
[Troubleshooting](#troubleshooting) and the [refusals you are likely to meet](#refusals-you-are-likely-to-meet-and-what-to-do).
**Turn diagnostics on for an hour**, on the HAI Engine page, records more detail for a report. Report a
problem where you got the preview, with the version from its `VERSION` file.
