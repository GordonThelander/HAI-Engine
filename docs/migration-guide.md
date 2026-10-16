# Moving your Rule Machine rules to HAI

This guide is for moving the rules you already have in Rule Machine over to HAI, one rule at a time, with a
way back at every step. You do not need to know how HAI works inside. The [user guide](user-guide.md) covers
HAI itself; its section [Moving from Rule Machine](user-guide.md#moving-from-rule-machine) covers installing
the HAI Migration app.

## Quick start

1. Open the **HAI Migration** page and press **Assess all**.
2. Press **Conversion report**. It says how many of your rules can move today, and gives every rule one
   **next step**.
3. For a rule marked ready, press **Create a disabled HAI copy** on its row (or **Copy the first 6 ready**
   in the report). The copy is made stopped. **Copying never switches anything over**: your Rule Machine rule
   keeps running.
4. Open the copy in the HAI editor and read what it says it will do.
5. Press **Switch over to HAI**. Rule Machine's rule is paused and the HAI copy starts.
6. Watch the row for a few days. If anything is wrong, press **Switch back to Rule Machine**.
7. When you are sure, delete the Rule Machine rule in Rule Machine.

The rest of this guide explains each step, what the report's next steps mean, the order to move rules in, and
the two places where you choose how a copy behaves.

## Before you start

- **Automation Map must be installed and must have scanned.** HAI reads what each rule does from Automation
  Map's reading of your rules. The page says how old that scan is. If you change a rule in Rule Machine,
  scan again before you Assess it.
- **Never leave both engines driving the same devices.** If the Rule Machine rule and its HAI copy both run,
  both act, and you get a flickering light or a rule that "sometimes does not work". Switch over and Switch
  back always pause one side before starting the other, so use them rather than starting things by hand.
- **Move one rule, or a small group, at a time.** It is much easier to tell which rule is misbehaving.

## The steps in full

### 1. Assess

**Assess all** reads every Rule Machine rule and works out what an HAI copy would look like. **Assess
selected** does the same for the rules you have ticked. Nothing on your hub changes. Each row then shows
whether the rule is ready, which side is running (**Rule Machine**, **HAI**, **Both at once** or **Nothing
running**), and anything that reaches beyond the rule, such as other rules that control it.

### 2. Read the report

**Conversion report** starts with the good news: how many of your rules can move to HAI today. Below that,
each rule has a traffic light and one short next step:

- **Green, ready.** It can be copied now.
- **Amber, after a fix.** Something HAI has to sort out first. There is nothing for you to do.
- **Red, needs you.** One change from you, and it can move.
- **Grey, not read yet.** Automation Map has not read it, or it has not been assessed.

Rules that look like tests (names starting with `__` or `_HAI `, names containing "(throwaway)" or "(probe",
or rules with no actions yet) are left out of the counts. **Show test rules** brings them back.

The report also has a **Worth knowing** part: where a copy behaves differently from Rule Machine, what is left
out of a copy (actions you turned off in Rule Machine), and the order to move rules in. **Copy as text** copies
the whole report, for example to paste into a forum post or a note.

The next steps are explained in [What each next step means](#what-each-next-step-means) below.

### 3. Copy

**Create a disabled HAI copy** builds an HAI rule from the Rule Machine rule as it is right now and puts it on
the hub stopped. In the report, **Copy the first 6 ready** does the same for up to six ready rules at once,
asks you first, and leaves out test rules and rules already copied. Press it again for the next six.

Copying never switches anything over. The Rule Machine rule is not touched and keeps running; the copy waits.

If HAI cannot set the copy up on the hub (a device it uses has not been selected for HAI, for example), the
copy is held, the row says why, and **Switch over** starts it once the cause is fixed.

### 4. Check

Open the copy in the HAI editor. HAI reads every rule back in plain English. If that sentence is not what the
Rule Machine rule does, do not switch over. A copy that converts is not proof that it behaves the same.

Look at the row's behaviour lines as well. Each says "As in Rule Machine, ..." or says how the copy differs,
and some offer an **Upgrade** checkbox. See [Two choices about how a copy behaves](#two-choices-about-how-a-copy-behaves).
Tick an upgrade before you copy. To change it afterwards, either change the same setting in the copy's
**Rule settings** in the editor, or delete the copy and copy again.

### 5. Switch over

**Switch over to HAI** pauses the Rule Machine rule, checks that it really has paused, and only then starts
the HAI copy. If the pause cannot be confirmed, HAI is not started and the row says so. The Rule Machine rule
is paused, not deleted: it keeps everything you set up in it.

### 6. Watch

After a switch over, the row keeps an eye on the HAI copy for you:

- **"last ran" and a time**, or **"has not run since it was switched over"**. A motion light that has not run
  for a day is worth a look; a rule that runs once a year is not.
- **A warning when the last run went wrong**, for example a command a device did not accept. The report's next
  step is then **Check its last run; switch back if it repeats**.
- **A warning when the Rule Machine rule has changed since it was copied.** The copy still does what the rule
  used to do. Delete the HAI copy and copy again.

The row cannot see a trigger that should have started the rule but did not. So also watch the house itself:
did the light come on, did the notification arrive.

### 7. Switch back, if you need to

**Switch back to Rule Machine** stops the HAI copy, then resumes the Rule Machine rule. You are back where you
started in seconds. That is why switching over pauses Rule Machine's rule instead of deleting it.

### 8. Delete, when you are sure

Deleting is always a separate step you take yourself, and never on the migration page:

- **HAI never deletes a Rule Machine rule.** Once you trust the HAI copy, open the rule in Rule Machine and
  delete it there.
- **To delete an HAI copy**, open it in the HAI editor and delete it there. Do this when you want to copy a
  rule again, or have given up on moving it.

## What each next step means

The report gives each rule one next step, and each section one for all its rules. Where a step names a device,
a rule or a setting, the report fills in the real name; the examples here are invented.

| Next step | What it means | What to do |
|---|---|---|
| **Copy, then switch over** | Ready, and the copy behaves as in Rule Machine. | Copy it, read the copy in the editor, then switch over. |
| **Copy, check its options, then switch over** (or **Check its options, then switch over** once copied) | Ready, but the copy differs from Rule Machine somewhere, or offers an upgrade. | Read the row's behaviour lines and decide on any upgrade before you copy. |
| **Copy now, switch over after Hall Motion Rule has moved** (or **Switch over last**) | Other rules control this one. Switch it over before them and they keep controlling its paused Rule Machine rule, so the link quietly stops working. | Copy it now; switch it over once the rules that control it have moved. See [Move order](#move-order). |
| **Switch over** | Already copied and ready. | Switch over when you are ready. |
| **Select Front Door in HAI Engine, then copy** | The rule uses a device HAI has not been allowed to use. | In HAI Engine, add the device under **Devices rules may use**, then Assess again and copy. |
| **Approve Away Count in HAI Engine, then copy** | The rule changes a hub variable that HAI rules may not change yet. | Allow that hub variable in HAI Engine, then copy. |
| **Allow mode changes in HAI Engine, then copy** | The rule sets the hub's mode, which HAI rules may not do yet. | Allow it in HAI Engine, then copy. |
| **Copy now; enable Porch Light before switching over** (or **Enable Porch Light before switching over** once copied) | The rule uses a device that is disabled on the hub. | You can copy it, but enable the device in Hubitat before you switch over. |
| **Delete the copy and copy again** | The Rule Machine rule changed after the copy was made, so the copy is out of date. | Delete the HAI copy in the editor, then copy again. |
| **Check its last run; switch back if it repeats** | The HAI copy is running and its last run went wrong. | Open the copy's run timeline in the editor. If it goes wrong again, press **Switch back to Rule Machine**. |
| **Fix what held the copy, then switch over** | The copy is held, not on the hub, and the row says why. | Fix that (usually a device to select), then press **Switch over**. |
| **Nothing to do, wait for the fix** | HAI cannot copy part of this rule yet, and the fix is HAI's to make: either already under way, or waiting for Rule Machine's behaviour to be measured. | Leave it running in Rule Machine. It will turn green after an update. |
| **Rebuild by hand in the HAI editor, or keep it in Rule Machine** | The rule uses something HAI leaves out on purpose, such as rebooting the hub. | Build it yourself in HAI if you still want it, or keep it in Rule Machine. |
| **Edit it in Rule Machine (what), then Assess again** | The rule has a setting HAI cannot copy as it stands. The brackets name the part, for example a time HAI cannot read. | Change that part in Rule Machine, let Automation Map scan, then Assess the rule again. |
| **Keep it in Rule Machine for now** | Something stands in the way that has no change you can make yet. | Leave it in Rule Machine. |
| **Assess it** (or **Assess them** for a section) | HAI has not read this rule yet. | Press **Assess selected** or **Assess all**. If it stays grey, scan in Automation Map first. |
| **Nothing to do, it runs on HAI** | Already moved. | Nothing. Delete the Rule Machine rule when you are sure. |
| **One change each** | The heading of the "Needs you" section. | Each rule in it has its own step from this table. |

## Move order

Most rules move on their own. A few are tied to other rules, and those need an order.

### Rules that control other rules

If Rule A runs Rule B's actions, pauses Rule B or cancels Rule B's timers, **move Rule A before Rule B**. The
report gives Rule B the step **switch over after Rule A has moved** (or **Switch over last** when several
rules control it). Follow it: switch Rule B over once the rules that control it are in HAI.

The reason: a rule still in Rule Machine can only reach Rule B's Rule Machine rule. Switch Rule B over first,
and Rule A goes on controlling the paused Rule Machine rule. Nothing shows an error; the link just stops
working. HAI is being updated so that a step copied into HAI follows Rule B to its HAI copy when Rule B
switches over. Until that update reaches you, when both have moved, open Rule A's HAI copy and check that the
step points at Rule B's HAI copy.

### A restart rule that resets Private Booleans

Many hubs have a rule like **System Start**: when the hub restarts, it sets other rules' Private Boolean
back to true. It exists because a Rule Machine rule that waits (say "turn off Private Boolean, delay 30
minutes, turn it back on") loses its delay in a restart and can be left stuck at false.

**An HAI copy does not need that repair.** HAI keeps a rule's delays and its Private Boolean through a
restart. After the hub comes back, a delay that is still due carries on, and one that fell due while the hub
was down runs once. So the copy of a rule like that sorts itself out.

So a rule that only resets Private Booleans **holds nothing up**:

- Move the rules it resets whenever they are ready. You do not need to wait for System Start.
- Move System Start whenever you like, or leave it in Rule Machine. While it is there it keeps protecting the
  rules still in Rule Machine. Its reset of a rule that has already moved reaches only the paused Rule
  Machine rule, which does nothing.

The report row of a rule whose Private Boolean another rule sets says so, and names the rules that set it.
If one of them sets it for some other reason (not a restart repair), check that step points at the HAI copy
once both rules are in HAI.

### Rules that share a hub variable

Both engines read and write the same hub variables, so a rule in Rule Machine and one in HAI can fight over
one. Move rules that share a variable together.

## Two choices about how a copy behaves

A copy keeps Rule Machine's behaviour by default, so that switching over changes as little as possible. In
two places HAI's own way is often better, and the row offers it as an **Upgrade** checkbox. Each has a tip on
the page saying when it is the better choice.

### When the rule is triggered again while it is running

Take a rule: when Hall Motion becomes active, turn on Hall Light, wait 5 minutes, turn off Hall Light.

- **Rule Machine runs the rule again for every trigger**, and each run keeps its own delay. Someone walks past
  at 8:00 and again at 8:03: the light goes off at 8:05 while they may still be there, and is told to go off
  again at 8:08.
- **A new HAI rule restarts.** The new trigger cancels the run in progress, delays included, and starts
  again. The light stays on and goes off at 8:08, 5 minutes after the last movement.

A copy keeps Rule Machine's way. Tick **Restart on a new trigger, so only the latest one counts** to get
HAI's. That is usually what a motion or door rule means. Leave it off if every trigger must count, for example
a rule that counts door openings or sends a notification each time.

(A rule that waits for an event, rather than a fixed delay, already restarts in Rule Machine, so its copy
restarts too and there is nothing to choose.)

A third, smaller choice sits beside these: Rule Machine starts a rule on every report from a device, even a
repeat of the same reading. **Only on a real change, ignoring a device that repeats itself** makes the copy
start only when the reading changes.

### When a device in an IF cannot be read

Sometimes an IF cannot be answered: the device is offline, was removed, or has never reported. Rule Machine
then treats the condition as not met and runs the ELSE. A copy does the same, and the log says the reading was
missing.

Whether that is right depends on what the ELSE does:

- **An ELSE that is a safety catch should run.** "IF Bathroom Heater's temperature is below 22 THEN keep
  Bathroom Heater on ELSE turn Bathroom Heater off." If the thermometer stops reporting, turning the heater
  off is the safe thing. Leave the copy as it is.
- **An ELSE that claims something is true should not run.** "IF Front Door is open THEN announce it ELSE
  announce all clear." If Front Door's sensor cannot be read, nobody knows it is all clear. Tick **Stop
  instead of running the ELSE when a reading is missing**: the run then stops at the IF and records why.

You can change this later in the copy's **Rule settings**, under **If a device in an IF can't be read**:
**Stop the run** or **Run the ELSE**. A rule you write yourself in HAI stops the run unless you choose
otherwise.

## Questions

**Does copying change anything in my house?** No. A copy is made stopped and Rule Machine keeps running.
Only **Switch over** changes which rule acts.

**What if I edit the Rule Machine rule after copying it?** The row warns that the rule has changed since it
was copied. Delete the HAI copy and copy again.

**What happens to my Rule Machine rule when I switch over?** It is paused, not deleted. **Switch back**
resumes it.

**Can I move everything at once?** You can copy six ready rules per press, but switch over one rule or a
small group at a time, and watch them.

**Some rules are amber. Should I do anything?** No. Amber means the fix is on HAI's side. Leave them in Rule
Machine; they turn green after an update.
