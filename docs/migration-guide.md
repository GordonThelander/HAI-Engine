# Moving your Rule Machine rules to HAI

This guide is for moving the rules you already have in Rule Machine over to HAI, one rule at a time, with a
way back at every step. You do not need to know how HAI works inside. The [user guide](user-guide.md) covers
HAI itself; its section [Moving from Rule Machine](user-guide.md#moving-from-rule-machine) covers installing
the HAI Migration app.

## Quick start

1. On the HAI Engine page, open **Rule Machine Migration Facility**. The page, **Move your Rule Machine rules
   to HAI**, reads your rules and sorts each one into a panel.
2. In **Available to clone**, press **Clone** on a rule's row (or **Clone all** in the card at the top). HAI
   makes a clone that stays **switched off**. Rule Machine keeps running the rule, so nothing in your house
   changes.
3. The clone moves to **Currently being shadowed**. Each time the rule is triggered, HAI works out what its
   clone would have done and checks that against what Rule Machine actually did. Its light shows how that is
   going.
4. When the light is green, press **Promote to HAI**. Rule Machine's rule is paused and the HAI clone starts.
5. If anything is wrong, press **Back to Rule Machine**. One click, at any time.
6. When you are sure, delete the Rule Machine rule in Rule Machine yourself. HAI never deletes it.

The rest of this guide explains each step, what each panel and button means, the order to move rules in, and
the places where you choose how a clone behaves.

## Before you start

- **Automation Map must be installed and must have scanned.** HAI reads what each rule does from Automation
  Map's reading of your rules. The page says how old that scan is. If you change a rule in Rule Machine,
  press **Scan again** at the top of the page.
- **Never leave both engines driving the same devices.** If the Rule Machine rule and its HAI clone both run,
  both act, and you get a flickering light or a rule that "sometimes does not work". **Promote to HAI** and
  **Back to Rule Machine** always pause one side before starting the other, so use them rather than starting
  things by hand.
- **Move one rule, or a small group, at a time.** It is much easier to tell which rule is misbehaving.

## The page

At the top, always in view, are **Open the editor**, **Scan again** and a **?** button for **How this works**.
Under them, one line repeats the promise the whole page keeps: your Rule Machine rules are never deleted,
Rule Machine keeps running each rule until you promote its clone, and going back is one click.

**Your move to HAI** shows how far you have got, as a percentage and a bar, and a picture of three stations:

- **In Rule Machine**: rules not cloned yet, running as today.
- **Being shadowed**: clones that are switched off and being checked against Rule Machine.
- **Running on HAI**: promoted rules. The Rule Machine rule is paused.

Under the three, a curved arrow runs back from Running on HAI to In Rule Machine: **Revert back to Rule
Machine with one click, at any time**. A red badge on a station, such as "2 need you", counts rules there
that need something from you. Click a station to open its panel.

Below the picture, one card says what to do next, with one button, for example "61 rules are able to be
cloned into HAI" with **Clone all 61**, or "3 clones are being shadowed" with **Show the clones**.

Then come the panels, in this order. Each shows how many rules it holds and one line on what they are. A
panel's header buttons act on every rule in it.

| Panel | What is in it | The row's button |
|---|---|---|
| **Available to clone** | Rules HAI can clone as they are. | **Clone** |
| **Requires some manual intervention** | Rules that need something from you first: a clone the hub would not accept, a rule not cloned last time for a reason a retry would repeat, a device, variable or mode setting to approve in HAI Engine, a device to enable, a rule changed in Rule Machine since it was cloned, or a rule Automation Map has not read yet. The row says what. | Takes you there: **Fix in the editor**, **Clone again**, **Open HAI Engine**, **Open Devices**, **Delete the clone** or **Scan again** |
| **Risky rules that require your permission** | Rules that reboot or shut down the hub. Their clones would too. | **Clone anyway…** |
| **Currently being shadowed** | Clones that are switched off and being checked against Rule Machine. | **Promote to HAI** |
| **Running on HAI** | Promoted rules. | **Back to Rule Machine** |
| **Staying in Rule Machine** | Empty rules, with nothing to clone, and rules that use something HAI cannot clone yet. | **Tell HAI** |

**More** on a row shows the rest: the full reasons, **Options for the clone** (see [Two choices about how a
copy behaves](#two-choices-about-how-a-copy-behaves)), and links: **Open in Rule Machine**, **Open the HAI
clone in the editor**, **Open the draft in the editor**, **See every run**, **Run for real and check** and **Delete the HAI
clone**, as each applies.

**Choosing several rules.** Tick rows in any panel and a bar appears at the bottom of the screen, for example
"3 selected · Clone 3". It offers each action that fits the ticked rules, with its count (**Clone**,
**Promote**, **back to Rule Machine**, **Delete … clones**, **Tell HAI about**), and **Clear**. If a risky rule
is ticked, the bar says so, and cloning asks once, naming it. **Select all** at the top of an open panel ticks
every rule in it.

**Search your rules** finds a rule by name. **Open all** and **Fold all** open or fold every panel.

## The steps in full

### 1. Read your rules

The page reads your rules from Automation Map's latest scan when it opens. Nothing on your hub changes.
**Scan again** asks Automation Map to read your Rule Machine rules again, for example after you have changed
one; the page updates when the scan finishes.

### 2. Clone

**Clone** builds an HAI rule from the Rule Machine rule as it is right now and puts it on the hub switched
off. The page asks first, naming the rule. **Clone all** in a panel's header, or the bar at the bottom, does
the same for many rules. It runs on the hub as a job, about seven seconds a rule; a box in the middle of the
page shows the progress and **Stop after this rule**, and you can close the page and come back. When it ends,
the same box lists what was cloned, any **Drafts to fix**, and anything not cloned, grouped by reason.

Cloning never switches anything over. The Rule Machine rule is not touched and keeps running; the clone
waits, switched off.

If the hub will not accept a clone as it is (a device lacking a command, a missing variable), the clone goes
to the editor's **Drafts**, marked **Needs fixing**, with what to fix. Its row moves to **Requires some manual
intervention** with **Fix in the editor**.

Rules in **Risky rules that require your permission** reboot or shut down the hub. **Clone anyway…** asks
first, in red. The clone is made switched off, so nothing happens until you promote it.

### 3. Shadowing

A clone being shadowed stays switched off and changes nothing. Each time the rule is triggered, HAI works out
what its clone would have done and checks that against what Rule Machine actually did. Each clone has a light:

- **Green**: at least 3 runs, all matched, and every trigger seen at least once. It is ready to promote.
- **Amber**: still watching. The row says how far it has got, for example "1 run in 6 days, 2 more matches
  to go green" or "1 trigger not seen yet".
- **Red**: a run differed. The row says what differed.
- **Grey**: it cannot be checked, because nothing the rule does can be watched (notifications, speech, web
  requests).

Some triggers give no event HAI can hear, such as a run started by another rule or app, or a call to the
rule's own web address. The row names them as **can't be checked**. If a row says **Not watching yet**, open
the HAI Engine page and press **Done** to start it; Rule Machine still runs the rule meanwhile.

The row cannot see a trigger that should have happened but did not. A motion light that has had no runs for
a day is worth a look; a rule that runs once a year is not.

**See every run**, under **More**, lists the clone's runs, newest first. Each is marked **Matched**, **Did
not match** or **Could not be checked**, with the time. For the last five runs, and any that did not match, it
shows each step: what HAI worked out it would have done, against what Rule Machine did. The last 20 runs are
kept. From the editor, the coloured ball beside a shadowed clone in the **On the hub** list opens the same
runs here.

**Run for real and check**, under **More** or in **See every run**, saves waiting for the rule's trigger. **It runs the Rule
Machine rule once, for real: its devices change as if it had been triggered.** HAI watches and checks what
its clone would have done, and the result appears in **See every run** a few seconds later. A test counts for
the rule's actions only; its trigger still needs one real run before the light can go green.

### 4. Promote to HAI

**Promote to HAI** pauses the Rule Machine rule, checks that it really has paused, and only then starts the
HAI clone, with the Rule Machine rule's Private Boolean. If the pause cannot be confirmed, the clone is not
started and Rule Machine stays in charge. The Rule Machine rule is paused, not deleted: it keeps everything
you set up in it.

Promote the green ones. You can promote a clone whose light is not green, but the page asks first and says
why: on amber or grey that it has not been checked enough to be sure, on red what differed. It also warns
when a rule still in Rule Machine sets this rule's Private Boolean ("Switch that one too"). **Promote all**
in the panel header, or **Promote** in the bottom bar, does several at once.

After promoting, watch the house itself for a while: did the light come on, did the notification arrive. If
a promoted rule needs you, for example both rules are running at once, its row moves to **Requires some
manual intervention** and says what to do.

### 5. Back to Rule Machine, at any time

**Back to Rule Machine** pauses the HAI clone and starts the Rule Machine rule again. You are back where you
started in seconds. The clone is kept, so you can promote it again later. That is why promoting pauses Rule
Machine's rule instead of deleting it.

### 6. Delete, when you are sure

Deleting is always a step you take yourself:

- **HAI never deletes a Rule Machine rule.** Once you trust the HAI clone, open the rule in Rule Machine and
  delete it there.
- **To delete a clone that is being shadowed**, open **More** on its row and press **Delete the HAI clone**,
  or use **Delete all … clones** in the panel header or the bottom bar. The page asks first, in red. Any draft
  made for the clone goes with it. Your Rule Machine rule is not changed and keeps running.
- **A promoted clone** cannot be deleted while it is running on HAI. Press **Back to Rule Machine** first;
  it is then shadowed again and can be deleted as above.

Delete a clone when you want to clone the rule again (for example after changing it in Rule Machine), or
when you have given up on moving it.

### Rules HAI cannot clone yet

Rules in **Staying in Rule Machine** keep running in Rule Machine. For a rule HAI cannot clone yet, **Tell
HAI** opens a short report of why, with names, numbers and addresses taken out. You can change anything in
it, then press **Open GitHub** and submit it there.

## Move order

Most rules move on their own. A few are tied to other rules, and those need an order.

### Rules that control other rules

A step that acts on another rule (its Private Boolean, running its actions, pausing, resuming or cancelling
its delayed actions) follows that rule: it acts on the rule's HAI clone once that clone is promoted, and on
the Rule Machine rule until then. So once Rule A is on HAI, it keeps reaching Rule B whether Rule B is still in
Rule Machine or has been promoted.

The one order that matters: if Rule A controls Rule B, **promote Rule A before Rule B, or both together**.
While Rule A is still in Rule Machine, it can only reach Rule B's Rule Machine rule. Promote Rule B first and
Rule A goes on controlling the paused Rule Machine rule. Nothing shows an error; the link just stops working.
When you promote a rule whose Private Boolean is set by a rule still in Rule Machine, the page warns you and
names that rule.

### A restart rule that resets Private Booleans

Many hubs have a rule like **System Start**: when the hub restarts, it sets other rules' Private Boolean
back to true. It exists because a Rule Machine rule that waits (say "turn off Private Boolean, delay 30
minutes, turn it back on") loses its delay in a restart and can be left stuck at false.

**An HAI clone does not need that repair.** HAI keeps a rule's delays and its Private Boolean through a
restart. After the hub comes back, a delay that is still due carries on, and one that fell due while the hub
was down runs once. So the clone of a rule like that sorts itself out.

So a rule that only resets Private Booleans **holds nothing up**:

- Move the rules it resets whenever they are ready. You do not need to wait for System Start.
- Move System Start whenever you like, or leave it in Rule Machine. While it is there it keeps protecting the
  rules still in Rule Machine. Its reset of a rule that has already been promoted reaches only the paused
  Rule Machine rule, which does nothing.

The page's promote warning names such a rule. If it sets the Private Boolean only as a restart repair, you can
ignore the warning.

### Rules that share a hub variable

Both engines read and write the same hub variables, so a rule in Rule Machine and one in HAI can fight over
one. Move rules that share a variable together.

## Two choices about how a copy behaves

A clone keeps Rule Machine's behaviour by default, so that promoting it changes as little as possible. In
two places HAI's own way is often better. **More** on the row, under **Options for the clone**, offers each as
a tick box. Tick it before you clone. To change it afterwards, change the same setting in the clone's **Rule
settings** in the editor, or delete the clone and clone again.

### When the rule is triggered again while it is running

Take a rule: when Hall Motion becomes active, turn on Hall Light, wait 5 minutes, turn off Hall Light.

- **Rule Machine runs the rule again for every trigger**, and each run keeps its own delay. Someone walks past
  at 8:00 and again at 8:03: the light goes off at 8:05 while they may still be there, and is told to go off
  again at 8:08.
- **A new HAI rule restarts.** The new trigger cancels the run in progress, delays included, and starts
  again. The light stays on and goes off at 8:08, 5 minutes after the last movement.

A clone keeps Rule Machine's way. Tick **Restart on a new trigger, so only the latest one counts** to get
HAI's. That is usually what a motion or door rule means. Leave it off if every trigger must count, for example
a rule that counts door openings or sends a notification each time.

(A rule that waits for an event, rather than a fixed delay, already restarts in Rule Machine, so its clone
restarts too and there is nothing to choose.)

A third, smaller choice sits beside these: Rule Machine starts a rule on every report from a device, even a
repeat of the same reading. **Only on a real change, ignoring a device that repeats itself** makes the clone
start only when the reading changes.

### When a device in an IF cannot be read

Sometimes an IF cannot be answered: the device is offline, was removed, or has never reported. Rule Machine
then treats the condition as not met and runs the ELSE. A clone does the same, and the log says the reading
was missing.

Whether that is right depends on what the ELSE does:

- **An ELSE that is a safety catch should run.** "IF Bathroom Heater's temperature is below 22 THEN keep
  Bathroom Heater on ELSE turn Bathroom Heater off." If the thermometer stops reporting, turning the heater
  off is the safe thing. Leave the clone as it is.
- **An ELSE that claims something is true should not run.** "IF Front Door is open THEN announce it ELSE
  announce all clear." If Front Door's sensor cannot be read, nobody knows it is all clear. Tick **Stop
  instead of running the ELSE when a reading is missing**: the run then stops at the IF and records why.

You can change this later in the clone's **Rule settings**, under **If a device in an IF can't be read**:
**Stop the run** or **Run the ELSE**. A rule you write yourself in HAI stops the run unless you choose
otherwise.

## Questions

**Does cloning change anything in my house?** No. A clone is made switched off and Rule Machine keeps
running. Only **Promote to HAI** changes which rule acts.

**Does shadowing change anything?** No. A clone being shadowed only listens and compares. **Run for real and check** is
the exception: it runs the Rule Machine rule once, for real, and the page warns you before it does.

**What if I edit the Rule Machine rule after cloning it?** The row moves to **Requires some manual
intervention** and says the rule has changed since it was cloned. Press **Delete the clone**, then clone it
again.

**What happens to my Rule Machine rule when I promote?** It is paused, not deleted. **Back to Rule Machine**
starts it again.

**Can I move everything at once?** You can clone everything at once, safely: nothing changes in your house.
Promote one rule, or a small group, at a time, and watch them.

**My light is amber after several days. What now?** The rule may not have been triggered enough yet, or one
of its triggers has not happened. **See every run** shows what has been checked. **Run for real and check** covers the
actions; the trigger still needs one real run. You can also promote it anyway, after the page's warning.

**Some rules are staying in Rule Machine. Should I do anything?** No. They keep running as today. **Tell
HAI** helps get them supported in a later update.
