# HAI Engine: release video script, draft 2 (22 Nov 2026)

Draft 2: chapters 2c, 4, 8b and 8c reworded to what the product shows, and the demo rule's condition is now "Hall
Motion is inactive" so it can be recorded in daylight (approved by Gordon, 22 Nov).

About 5 minutes 15 seconds, 9 chapters. Every line is written to what is on screen in that shot. The voice is
fitted to the picture, never the other way round (see "How it is made" at the end).

Devices are the Test-room virtual devices, renamed for the video to public names: Porch Door, Entry Light,
Hall Motion, Hall Light, Bathroom Fan, Bathroom Humidity. No real rule or device names from the house appear.
In the spoken text "HAI" is spelled "Hi" and Hubitat "Hub-it-tat". No burned-in captions: YouTube makes them.

---

## 1. What it is (0:00, about 15 s)

**Shot:** HAI editor home, rule list with a few rules, slow push-in on the editor title.

> This is Hi, Hub-it-tat Automation Intelligence: a rule engine for Hub-it-tat, with a rule editor in your browser. You can write rules
> yourself, start from a ready-made pattern, or move your Rule Machine rules across safely. Here's how.

---

## 2. Write a rule yourself, no AI (0:15, about 1 minute 45 s)

**Shot 2a:** Press New rule. Empty rule: three sections, Triggers, Conditions, Actions.

> Let's write one by hand. Every rule has three parts: what starts it, what must be true, and what it does.

**Shot 2b:** Add trigger, pick "A device reports a value", choose Porch Door, contact, opens.
The block now reads "When Porch Door opens".

> Add a trigger. Pick the device, pick what it reports. The block reads back as a plain sentence:
> when Porch Door opens.

**Shot 2c:** Add condition, Hall Motion is inactive. The block reads "Hall Motion is inactive".

> Add a condition: only if nobody's in the hall.

**Shot 2d:** Add actions: Entry Light on, wait 5 minutes, Entry Light off. Three lines appear.

> Then the actions. Turn Entry Light on, wait five minutes, turn it off. Each line reads as a sentence, so you
> can see what the rule does without opening anything.

**Shot 2e:** Clear the device from the "off" line. A plain-word warning appears under it straight away. Put it back;
the warning clears.

> Hi checks the rule as you build it. Leave a line incomplete, and it tells you at once, in plain words.

---

**Shot 2f:** Drag the line "Turn Entry Light off" by its handle above "Wait 5 minutes", drop it, then drag it back
below. The rule's sentence updates with each drop.

> Lines can be rearranged by dragging. Pick one up, drop it where you want it, and the rule reads back the new
> order straight away.

**Shot 2g:** Add action, choose "If, else if, otherwise". Set the condition: Hall Motion is active. In the If branch,
Entry Light level 100%; in the Otherwise branch, Entry Light level 30%. The block shows the two branches, indented,
each reading as a sentence.

> Actions can make their own decisions, too. Add an If: if Hall Motion is active, set the light to full;
> otherwise, thirty percent. Each branch sits indented under its condition, so you can see both paths at a glance.

**Shot 2h:** Drag the existing "Wait 5 minutes" line into the If branch, then drag it back out.

> And you can drag existing lines into a branch, or out of one, the same way.

---

## 3. Run one line on its own (2:00, about 25 s)

**Shot 3a:** Open the menu on the line "Turn Entry Light on", choose Run this step. Cut to the device tile, or the
editor's live value, flipping to on.

> You don't have to run the whole rule to try it. Open any line and choose Run this step. That one line
> runs on the hub, now, and you see the light come on.

**Shot 3b:** Same menu on "Turn Entry Light off", Run this step, light goes off. Briefly show that a Wait line has
no Run this step.

> It's offered only on lines that do something on their own. Waits, and risky commands like locks or sirens,
> are never run this way.

---

## 4. See it before it runs (2:25, about 20 s)

**Shot:** Simulate. The scenario for Porch Door opening, simulated now: light on, then light off five minutes later,
with the time of each command.

> Before the rule touches anything, simulate it. Hi shows what it would do right now: the light comes on, and five minutes later it goes off.

---

## 5. Start from a pattern (2:45, about 35 s)

**Shot 5a:** New rule, Start from a pattern. The list opens, grouped: Lights, Comfort, Security and alerts,
Presence, Buttons, Advanced. Scroll slowly through the groups.

> Or don't start from a blank page. Patterns are working rules you adapt: lights on movement, a bathroom fan,
> a door left open, a leak alert, and more.

**Shot 5b:** Type "fan" in the search box, pick Bathroom fan. The rule opens with its lines filled, devices still to
choose, the checker pointing at them.

> Search, pick one, and it opens as a complete rule. The checker shows exactly what's left for you: here,
> just the fan and the humidity sensor.

**Shot 5c:** Choose Bathroom Fan and Bathroom Humidity. Checker goes quiet. Put on hub, stopped.

> Choose them, and it's ready to go on the hub.

---

## 6. Runs on the hub, recorded (3:20, about 20 s)

**Shot:** Rule goes on stopped, press Start. Trigger Porch Door. Open the rule's run history: one run, each step
with its time and result.

> Each rule runs as its own small app on the hub. It goes on stopped, and starts when you say. Every run is
> recorded, so if a rule didn't do what you expected, it tells you why.

---

## 7. How it works with Automation Map (3:40, about 45 s)

**Shot 7a:** The HAI and HAM diagram (four boxes, titled with both full names). Boxes first, then each numbered
arrow lights as it is named, with a slow push-in.

> Hi works hand in hand with Ham, Hub-it-tat Automation Map. Here's how they fit together.

**Shot 7b:** Highlight "AM scans the hub" and "AM writes files".

> Ham scans your hub and decodes every Rule Machine rule into a file.

**Shot 7c:** Highlight "Migration reads the decode".

> Hi Migration reads that file. That's how it knows what each Rule Machine rule really does, before it copies it.

**Shot 7d:** Highlight "AM reads HAI's feed". Cut to the Automation Map page showing an HAI rule and a Rule Machine
rule side by side, linked to the same light.

> And Hi tells Ham about its own rules, so the map shows both kinds together: what they watch,
> what they control, and where they overlap.

---

## 8. Move your Rule Machine rules safely (4:25, about 40 s)

**Shot 8a:** Migration page. Rules sorted into can clone and can't clone. Press Clone on one rule.

> Already on Rule Machine? Migration sorts your rules, and copies the ones it can rebuild exactly. Anything it
> can't rebuild, it names, instead of guessing.

**Shot 8b:** The trial list. The copy shows amber, "Just cloned". Cut to the same row later, green (ready to promote),
with its matched runs counted beside it.

> Each copy starts in a trial. It changes nothing. Every time the rule fires, Hi works out what its copy would have done, and compares it with what Rule Machine actually did. Green means the copy is ready to take over, and beside it, how many of its runs have matched Rule Machine's.

**Shot 8c:** Press Promote to HAI. Rule Machine rule shows paused, HAI copy running. Press Back to Rule Machine; reversed.

> Promote it, and Rule Machine pauses while Hi takes over. Changed your mind? Back to Rule Machine, in one click.

---

## 9. Install (5:05, about 12 s)

**Shot:** HPM, Install, search "HAI Engine". End card with the forum link.

> Hi is free, and local: nothing leaves your hub unless you turn it on. Install it from Hub-it-tat Package
> Manager. The link is below.

---

## How it is made (agreed with the PLS Outcomes method)

Confirmed by the PLS Outcomes session itself (22 Nov), and in its repository's handover and lessons.

**Voice.** PLS uses **Emma** (ElevenLabs `56bWURjYFHyYyVf490Dp`), model `eleven_multilingual_v2`, stability 0.45,
similarity 0.8, style 0.15, speaker boost on. Output mp3 44.1 kHz 192k, word timings from the with-timestamps call, each on-screen action anchored to the word
that names it, silence trimmed. PLS recommends Emma for one voice across your channel; the last HAI video used Daryl. Either way the voice is made once, scene by scene; a sync fix never re-voices,
and a wording change re-voices only that scene.

**Method (PLS method v2, the one you accepted on video 8 and every video after it):**
- The screen leads and the voice is fitted to it. Each scene's line is spoken over that scene's shot only.
- Constant 30 fps capture with ffmpeg on your PC (Chrome's own screencast drops frames and made video 8 jump).
- The pointer is drawn in the edit from logged click positions and times, so it can never lag the words.
- Pauses only at sentence breaks, never mid-phrase. No frozen frames or speed-ups to cover gaps; silence goes in
  the audio. Slow app steps (saving, loading) are cut out inside the scene.
- A continue-on-fault rehearsal first, then one real take. Only the finished mp4 leaves your PC, never raw takes.
- Chapters from the scene marks. No burned-in captions and no .srt (your rule: YouTube makes them).
- 1920x1080, 30 fps, sample data only. The pointer moves only to act, one straight eased move. A slow push-in so no
  shot is ever still. First words within 1.5 s, no logo intro. End card is plain text, nothing that looks clickable.
- Highlights on the diagram use each element's measured box, never a guessed offset.
- Check: a frame from each scene against its line, then send. No full watch-throughs or repeat encodes.

**Chapter 7 diagram:** approved 22 Nov, titled "Hubitat Automation Intelligence (HAI) and Hubitat Automation Map
(HAM)" so both names become familiar. Voice: Emma (approved 22 Nov).
