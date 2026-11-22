# HAI Engine release video: brief for PLS Outcomes

Gordon has handed this video to PLS Outcomes and its local agent, to be made the way PLS makes its videos.

## What exists
- SCRIPT.md: the approved script, shot by shot (draft 2). narration.json: the final spoken lines per scene
  ("Hi" is spoken for HAI and "Hub-it-tat" for Hubitat; Gordon keeps "Hi"; on-screen text says HAI and Hubitat).
- hai-ham-diagram.html: the approved chapter 7 diagram, 1920x1080, titled with both full names; arrows light one
  at a time as the voice names them.
- HAI-Engine.mp4 and sync-report.txt: the previous cut. Sync was good; your review found it illegible on a phone
  (full-desktop shots), a weak opening, a near-black device page, blurred names, a weak end card.

## Gordon's decisions so far
- Voice: Emma (ElevenLabs 56bWURjYFHyYyVf490Dp), your usual settings.
- Keep "Hi" as spoken.
- Record on the PRODUCTION HAI install on his hub (app 'HAI Engine', no suffix), never the Dev install ('(DEV)').
- Demo content only, public names only: Test-room virtual devices relabelled Porch Door, Entry Light, Hall Motion,
  Hall Light, Bathroom Fan, Bathroom Humidity, and Garden Light; eight seeded HAI rules (Hall Light on Movement,
  Hall Light Off When Empty, Bathroom Fan on Humidity, Porch Door Left Open, Good Morning Lights, Night Light,
  Entry Light at Arrival, Lights Off at Midnight, Garden Light at Sunset); Rule Machine sample rules
  'Sample: Entry Light on Motion' (cloned live in chapter 8), 'Sample: Porch Light at Sunset',
  'Sample: Hall Light Off at Bedtime', 'Sample: Garden Light Off at 11', 'Sample: Empty rule'.
  All of this is already set up on the hub and left in place for you.
- Chapter 7 map shot: production Automation Map focused on Garden Light shows exactly four nodes (Garden Light,
  HAI Engine, [HAI] Garden Light at Sunset, Sample: Garden Light Off at 11). Close its first-run tip
  ('Got it', #hubTipGot) on every page load.
- No private names on screen, and no blur either: crop or choose shots so non-public rows are not in frame.
  The editor's rule list and device pickers show non-public items; frame the panel being described instead.
- Product facts the script depends on (checked against the code):
  - a rule activated from the editor arrives running; stop it with the editor's own switch to show "stopped";
  - Simulate shows what the rule would do now (no time picker);
  - Migration's buttons are "Promote to HAI" and "Back to Rule Machine"; green = ready to take over;
  - the editor lists 5 rules until "Show all".
- Gordon's rules (yours): no burned-in captions; nothing that looks clickable on the end card; every claim true
  to the screen; open within 1.5 s; one video, checked frame by frame before he sees it.

## Where things are on Gordon's PC
- Local addresses for the production editor, Migration page and Automation Map (with tokens):
  %USERPROFILE%\hai-video-urls.txt (written by the HAI local agent; private, not in any repository).
- Original device names to restore afterwards: %USERPROFILE%\hai-video-restore.json.
- The HAI local agent's recorder (record-v2.mjs, assemble-v3.mjs) is in its HAI checkout under tools/video if any
  part is useful; you are free to use your own pipeline instead.

## Afterwards
Put the finished MP4 somewhere the HAI cloud session can read (this branch is fine) and tell Gordon. The HAI local
agent restores the hub when Gordon says the video is final.
