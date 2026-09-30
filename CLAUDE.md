# Mutterboard

Android voice dictation. Record, transcribe, commit the text. Two entry points:
a keyboard (IME) that commits into the field you are in, and an overlay you can
launch from a side button that dictates over any app and puts the result on the
clipboard.

Two transcription engines, chosen in the app: **Default** (cloud — Groq
Whisper V3 Turbo) and **Offline** (on-device Parakeet). On the cloud path only, a
second LLM pass then cleans the transcript up before it is committed.

Two refine modes for that pass, toggled from the keyboard itself:
`GroqRefiner` (Default) and `CasualRefiner` (Casual). The toggle hides itself
whenever no refiner exists - Offline engine, the automatic offline fallback, or
no API key - because a toggle naming a mode that isn't running would be lying.

Both prompts also carry **rule 3**: when the user spells a word out letter by
letter mid-sentence ("Las Fuentas, spelled L-A-S-F-U-E-N-T-A-S"), those letters
are an instruction addressed to the model. It respells the word, deletes the
instruction, and the letters outrank the transcript.

## Two ways in: the keyboard and the overlay

`DictationSession` owns a dictation end to end - engines, refiners, recording,
the transcribe-then-refine pipeline, the UI. It deliberately does not know where
the text goes or how the UI disappears, because those are the only two things
the two entry points disagree about. They are the `DictationSession.Host`
interface.

- **`MutterboardInputMethodService`** (the keyboard). Commits through the
  InputConnection, dismisses by switching back to the previous IME.
- **`OverlayDictationService`** (the overlay). Floats over any app, started
  either from a launcher activity you can map to a side button or from the Quick
  Settings tile. Dismisses by removing its own window.

The two draw themselves differently and that is deliberate. The keyboard keeps
`keyboard_view.xml`, because it has to look like a keyboard. The overlay is
Compose (`OverlayDictationUi.kt`), because it must not. Both read the same
`DictationSession.Snapshot`, so captions and button states cannot drift apart.

### Rules that are not obvious from the code

- **The overlay writes the clipboard every time, not just when pasting fails.**
  Dictation there often has no destination yet: you start talking, move between
  apps, and only then go find a field. A transcript that landed nowhere is the
  failure worth engineering against, so the clipboard is the destination and the
  paste is the bonus.

- **Paste, not `ACTION_SET_TEXT`.** SET_TEXT replaces the entire field, so
  matching the keyboard's insert-at-cursor behavior would mean reading the node,
  splicing at the selection and restoring the cursor - the exact sequence that
  breaks in Compose and WebView fields. `ACTION_PASTE` already has commitText's
  semantics.

- **The foreground service is not bureaucracy.** An overlay window does not make
  the app foreground, and Android cuts the microphone to apps that aren't. It
  must also come up *untyped* when RECORD_AUDIO is missing: declaring the
  microphone type without the grant is a hard error on Android 14+, and the
  overlay is reachable before the user has finished setup.

- **`FLAG_NOT_FOCUSABLE` is load-bearing in two places** - on the overlay window
  and on the launcher activity. Either one taking focus closes the keyboard and
  drops the cursor in the field being pasted into, which is the entire point of
  the design.

- **The launcher activity ships disabled.** It carries a LAUNCHER filter so OEM
  side-button mappers can see it, which would otherwise mean a second app icon
  for everyone. The settings toggle enables the component; there is no separate
  preference, so nothing can drift out of sync with it. `MutterboardTileService`
  is disabled and enabled by the same toggle, for the same reason.

- **There is only ever one app icon, and the two entry points take turns
  holding it.** Both need a LAUNCHER activity - the mappers only list launchable
  apps - so leaving both enabled put two Mutterboard icons in the drawer, which
  is not a thing apps do. The overlay toggle enables `OverlayLauncherActivity`
  and disables the `SettingsLauncher` alias, and back again. With the overlay on,
  tapping the icon starts talking; settings is on the icon's long-press shortcut
  (`res/xml/shortcuts.xml`) and on the band's own settings button.
  `syncLauncherIcons` repairs installs that predate this, because component
  states survive an update and both icons would otherwise stay enabled forever.

- **Never change a component's enabled state while an activity is running on
  it.** Android destroys that activity, which from the user's side is the app
  vanishing to the home screen - it reads exactly like a crash, and there is no
  stack trace to find afterwards. The settings screen runs on the
  `SettingsLauncher` alias it disables, so the swap happens in `onStop`.
  `DONT_KILL_APP` does not help: the process survives, the activity does not.

- **Nothing may reach settings through `getLaunchIntentForPackage`.** With the
  overlay on, the package's launch intent *is* the overlay launcher, so asking
  for it to answer "take me to settings" starts another dictation instead.
  `DictationSession.openSetupActivity` names `MainActivity` explicitly.

- **The Quick Settings tile routes through the launcher activity, not straight
  to the service.** A tile click does not make the app foreground, and a
  microphone foreground service cannot be started from the background. The
  activity is the one path already allowed to start it, so both entry points go
  through it.

- **The band is a band, not a screen.** The look is ported from Checkr's
  `VoiceOverlay`, which fills the screen and treats a tap outside its band as
  cancel. That is wrong here: the point of the overlay is that a dictation
  survives you moving around while you talk, and a full-screen window would
  swallow every touch. The window is sized to the band so everything above it
  reaches the app underneath. The translucency is the same argument made
  visually - the band admits it is sitting on top of something.

- **The puck's tap finishes the dictation; the hold brings the band back.** The
  puck is the whole interface for someone working that way, so the common need
  (the text) gets the cheap gesture and the rare one (the band) gets the
  deliberate one. Drag and hold share the press without competing: the hold is a
  timer armed on touch-down and cancelled the instant the finger crosses the slop
  the drag already measures, so a press that moves can never also be a hold. The
  haptic tick at the timeout is load-bearing, not polish - it is the only way to
  feel a hesitant press crossing from tap to hold.

- **Puck or band is remembered, and only a deliberate choice writes it.**
  Collapsing or expanding by hand sets the shape the next dictation opens in
  (`overlay_puck_mode`). The automatic expand that surfaces an error passes
  `remember = false`, because a missing mic permission must not quietly end puck
  mode on the user's behalf. The auto-expand also skips IDLE: that is the state a
  dictation that worked passes through, and expanding on it popped the band open
  at the end of every recording.

- **The puck snaps to three columns, and the columns are measured off a
  constant.** Left, centre and right with 16dp of edge padding, chosen live as the
  finger crosses each halfway mark; vertical stays free, because height is where
  the thing you are covering actually varies. The column is what gets saved, not a
  coordinate. `PUCK_WIDTH_DP` in the service has to match `PUCK_WIDTH` in the UI:
  the window's first position is decided before the view has ever been laid out,
  so a centre computed from the measured width is half a puck off.

- **Thinking is a shape change, not a quieter wave.** The squiggle rolls up into a
  spinning ring, one interpolated path rather than two drawings swapped over.
  Inside the puck a tap ends the dictation, and the seconds of transcribing that
  follow used to look exactly like the seconds before the tap, so the tap read as a
  press that missed.

- **The overlay outlives its own session by one animation.** `dismiss()` starts
  the mist burst and posts the teardown behind it, because the window vanishing on
  the frame it committed gave the one event worth confirming - your words landing
  in the field - no acknowledgement at all. `POOF_MS` is both the animation's
  length and the teardown delay: shorter cuts the mist off mid-air, longer leaves
  an invisible window over the app the user has already gone back to. The puck's
  touch handling switches off for the duration, or a press would land on a
  dictation that is over.

- **The accessibility service is optional and must stay optional.** Without it
  the overlay still works, it just stops pasting for you. That is what keeps the
  "Allow restricted settings" unlock off the critical path for a new user.

- **Android's floating button is inert, and setup treats it as housekeeping.**
  Turning the accessibility service on makes Android attach its shortcut; the app
  cannot detach it (WRITE_SECURE_SETTINGS) and cannot use it either. Pressing it
  does nothing at all - tested on a Titan II with the button assigned - so there
  is nothing to warn about. The row that offers to remove it appears only when
  the button is actually attached, below both options rather than inside either.
  It was briefly a numbered step, which told people who did not have the button
  to go and turn it off. The step that *causes* it warns first, because a button
  that appears on your screen unannounced and does nothing is a new user's first
  impression of the app, and the app has no way to stop it appearing. Both phones this was
  built against attached it on their own, and the button was the first thing the
  user noticed about the accessibility step on each of them.

- **Never ask for the accessibility button.** `flagRequestAccessibilityButton`
  looks like free real estate - Android attaches its shortcut to any service the
  user enables anyway, so claiming the press turns litter into an entry point,
  which is worth having on a Pixel with no side button to map. It is not free:
  Settings then treats the service as one you invoke *with* the button and drops
  the "Use Mutterboard Dictate" switch from the service's own page, leaving the
  shortcut toggle as the only way to turn on the permission that makes pasting
  work. Shipped in v1.19.0 and pulled in v1.19.1, confirmed by installing both
  ways on one Titan II and watching the switch come and go. A phone with nothing
  remappable has the Quick Settings tile; that is what it is for.

- **Touching `accessibility_service_config.xml` switches the service off on
  every phone that has it.** Android treats a service that redeclares itself as
  one the user has not consented to, drops it from the enabled list, and says
  nothing; the overlay carries on and quietly stops pasting, which is what a user
  notices. Verified either way on a Titan II: an update carrying only a version
  bump left the grant alone. So a release that edits that file has to tell people
  to turn the service back on, and pulling a mistake out of that file costs them
  the same grant a second time.

- **The overlay says so when it could not paste.** The clipboard fallback is
  indistinguishable from working, which is how a revoked service reads as "the
  app stopped pasting" with nothing on screen to explain it. The band stays up
  and names Android as the one that turned the permission off - but only for
  someone who had the service running before (`KEY_ACCESSIBILITY_SEEN`, written
  from the service and from the settings screen, because the update that revokes
  the grant is also the one that stops the service ever connecting again).
  Someone who never enabled it is not owed a warning: the clipboard is the design
  for them. It waits to be answered rather than timing out - a timeout took the
  band away mid-reach, before the press that fixes it landed.

- **"Message pasted from your clipboard" is Android's, not ours.** It fires
  because ACTION_PASTE makes the *target* app read a clip it did not write, and
  the only way to stop it is to stop pasting through the clipboard - which is the
  design. Do not go hunting for a Toast in this codebase.

## Where this is going (picked up 2026-09-08)

**The overlay is meant to become the default way to use Mutterboard.** Not a
second entry point bolted onto a keyboard: the way you are expected to use it is
to map it to a button or a Quick Settings tile and run it from anywhere. Ry's
call on 2026-09-07, after living with it for an afternoon.

The keyboard is **not** being removed. It stays a real IME, and everything under
"The refiners are the heart of this app" still applies to it unchanged. What
changes is which one is the front door, which is a question about setup copy and
ordering far more than about code: today the app opens on "Device setup ->
Enable keyboard" and treats the overlay as an extra further down the page.

### What is unfinished

All of this lives on `feature/dictation-overlay`, unmerged.

- **The settings experience was walked end to end on 2026-09-08** from a real
  fresh install, and rebuilt around the walk. `scripts/fresh-setup.sh` is how
  that is done again: save, reset, restore. Reset uninstalls rather than
  `pm clear`, because clearing leaves the component states the overlay choice
  sets and the shell user is not allowed to put those back.
- **Setup is now a choice, not a checklist.** Device setup asks for the
  microphone, Transcription asks for the key, and "How you dictate" offers
  Overlay or Keyboard as radio options with each one's setup nested under it.
  They are alternatives: nothing in the app arbitrates between an overlay and a
  keyboard both live at once, so it never offers both. The overlay's component
  state IS the choice - no second preference to drift - and a fresh install is
  written to Overlay once, on first launch. **A fresh install only**: an update
  keeps the keyboard, because someone arriving through an update has never been
  asked the question and switching how their phone works behind an update is not
  an answer they gave. `firstInstallTime == lastUpdateTime` is the test; the
  preference cannot be, since its absence describes both kinds of user.
- **The radio group cannot enforce itself alone.** Choosing Overlay does not turn
  the keyboard off - no app may disable an IME on the user's behalf - so the
  overlay's steps carry a "Turn off the Mutterboard keyboard" row whenever the IME
  is still enabled. Without it the Pixel sat with both live at once, which is the
  state the card exists to prevent.
- **Band height is a fraction of the screen with the content as a floor.** It was
  measured on the Titan II, where the controls happen to come out about as tall as
  a keyboard; the same content on a Pixel is a fifth of the screen and the
  keyboard goes on showing underneath. `BAND_SCREEN_FRACTION` is the knob.
  The window also opts out of being fitted above the navigation bar
  (`fitInsetsTypes = 0`), which is what removes the hard edge and the strip of
  keyboard below the band, and pays the inset back as padding inside the band.
- **The silence trim constants want tuning against real recordings.**
  `SILENCE_PEAK_PERCENT` and `SILENCE_FLOOR` were picked from one measured
  failure; the debug log prints `peak=` and `threshold=` on every stop.
- **Niagara's search box (`bitpit.launcher`) refuses both ACTION_PASTE and
  ACTION_SET_TEXT.** Falls back to the clipboard, which is the designed
  behaviour, but it is the one field seen doing this.
- **Pastiera's bar went missing once right after closing the overlay** and came
  back on its own. Never reproduced, and the IME config was verified intact at
  the time. If it recurs, suspect `OverlayLauncherActivity` coming up
  FLAG_NOT_FOCUSABLE and the field never re-requesting the keyboard.

## The Pastiera keyboard experiment (started 2026-09-25)

On `feature/pastiera-keyboard`: turn Mutterboard into a full keyboard, so that
on a phone with a physical keyboard (Ry's Titan II Elite) it replaces the
on-screen keyboard entirely, and on any other phone it is still just
dictation. The stock Kika keyboard is bad, Gboard misbehaves on hardware
keyboards (phantom Alt shortcuts, double-space-period then backspace wiping all
the text), and Pastiera nightly has quirks like staying up over Niagara after
going home. The goal is a keyboard Ry owns: Pastiera with Mutterboard's
dictation and the quirks fixed.

**The base is Pastiera stable v0.86, since 2026-09-27.** It moved stable
v0.85 -> nightly `474fa10` (2026-09-25, to get nightly's features) -> v0.86,
which is that nightly plus four commits: upstream shipped the nightly features
as the final stable release. v0.86 is upstream's last release; its successor
is **Plektra** (`pkb-rocks/plektra`). Stay as close to stock as possible: ours
is the dictation mic, the dictation look, the Niagara home-screen fix, the
Material You theme and the English autocorrect. Everything else is Pastiera's,
including its bar and its Titan 2 Elite corners.

- **Pastiera is GPL-3.0, so Mutterboard is too.** Ry agreed; it is a hobby
  project and stays open source.
- **The "Material You" keyboard theme is resolved at runtime**
  (`mutterboard/MaterialYouTheme`), not stored: a `materialYou` flag on the
  theme makes `getEffectiveKeyboardTheme` repaint it from the system palette
  for the current light/dark mode. Its stored colours are only a fallback.
- **Pastiera's settings screens stay as they are**, colours included, apart
  from what Ry has cut as too heavy (2026-09-29): Keyboards & Devices is
  hidden, modifier indicators are the bottom strip LEDs only, and the bar is
  always Pastierina (`getStatusBarPresentationMode` and
  `getModifierIndicators` are pinned in `SettingsManager`, so no stored
  preference can bring the others back). **To cut a setting, add its link id
  to `HiddenSettings` and wrap the row in `if (!HiddenSettings.hides(id))`;
  never delete it.** Pastiera's link tests read the source and fail on a
  registered row that is gone, a wrap is a smaller diff across upstream
  merges, and search reads the same list. Setting rows grow with their text instead of
  clipping it: upstream gave them fixed heights, now minimums. The peach
  canvas we once painted on them went with the move to v0.86.
- **The visibility rewrite (`d0ea564`) is back in, as stock.** We reverted it
  on nightly as the suspect for Messages and Gmail not lifting their text
  field above the bar, and Niagara's home screen typing one letter then
  stopping. Never confirmed; v0.86 ships it, so it is being tried again. If
  those bugs come back, `git revert 4f738c6` takes it out again.
- **Pastiera's `PastieraApplication` does not run.** An app has one
  Application, so `MutterboardApplication` repeats its startup calls; check it
  after moving the pin. Its software-keyboard-mode launcher shortcut is left
  out, and the Clicks launcher accessibility service is removed in the app
  manifest - a second Mutterboard accessibility service beside the paste one
  would leave people guessing which to enable.
- **The bar is Pastiera's own.** A Gboard-style one-row pill
  (`mutterboard/PillBar`) was dropped on 2026-09-27 once v0.86 made the stock
  bar look right on the Titan 2 Elite. Two fixes found while the
  pill existed stay: the add-word plus uses the theme accent, and
  `HamburgerMenuView`/`StatusBarButtonHost` skip reassigning unchanged layout
  params (a relayout loop).
- **Mutterboard is the host, Pastiera is the guest.** Its source sits under
  `keyboard/` (the name is ours to pick; GPL only asks that the copyright
  notices stay and Pastiera is credited in the app), subtree-merged from the `v0.85` tag, then nightly `474fa10`, then `v0.86`,
  with full history. Remotes: `pastiera` is Ry's fork,
  `pastiera-upstream` is palsoftware. Nightly is not a separate repo, it is
  upstream `main`. Pick a nightly change across with
  `git cherry-pick -Xsubtree=keyboard <sha>`.
- **Upstream tags are fetched under `pastiera/*`** and both remotes are
  `--no-tags`, so they never mix with Mutterboard's own release tags.
- **It builds as the `:keyboard` library** through
  `keyboard/app/mutterboard.gradle.kts`, never Pastiera's own build file, which
  stays upstream-identical (it is AGP 8; we are AGP 9). Its GitHub updater is
  off and its launcher icon is stripped in the app manifest.
- **Settings: a Keyboard radio (Touchscreen / Physical keyboard) and a
  separate Overlay switch.** They are independent: the overlay is an extra you
  map to a button and it runs alongside either keyboard. Ry went through
  "separate sections" and "nested under Keyboard" before landing here; the only
  real either/or is which keyboard fits the phone. The radio is which of the two
  IME services is enabled; the physical one ships disabled so updates keep the
  dictation keyboard. Touchscreen is the dictation keyboard.
- **The mic button runs `DictationSession`** through
  `keyboard/.../inputmethod/voice/` (Mutterboard's own package inside the
  library) and `KeyboardDictation`, installed by `MutterboardApplication`. While
  dictating, the suggestion row becomes the overlay's squiggle plus cancel, the
  mic button becomes stop, and the whole bar sits in the overlay's mist.
  It ends instantly - a poof was tried and Ry had it removed (`DictationBar`). Wave and mist are View ports of the
  app's Compose `DictationWave`/`DictationAura`; keep the two in step.
- **Its strings are rebranded from the app**, generated per locale by
  `scripts/rebrand-keyboard-strings.py`; rerun it after picking nightly changes.
  The About line keeps the Pastiera credit on purpose.
  Where Pastiera put its name on a feature, the feature gets no name at all
  ("QuickLauncher", not "Mutterboard QuickLauncher"): Ry finds stamping our
  brand on Pastiera's work sleazy. The app names itself only where a sentence
  is about what the app does, and strings about Pastiera's releases and
  Plektra keep Pastiera's name, because "Mutterboard continues as Plektra" is
  false. The script's GENERIC and ABOUT_PASTIERA rules carry this; extend them
  rather than hand-editing the output.
- **Fn+Space opens the QuickLauncher** (a switch under Launcher Shortcuts,
  off by default). The Titan 2 Elite's Fn key reaches the keyboard as Left
  Ctrl, so this is Ctrl plus the QuickLauncher key, caught ahead of Ctrl's own
  shortcuts and of nav mode. It takes Ctrl+Space from Android's keyboard
  layout switch, which is why it is a switch. Upstream only ever offered SYM
  or Alt, because it expects Fn to be set up as Ctrl.

## Autocorrect (2026-09-25 to 2026-09-26, done)

Made the physical keyboard's autocorrect and prediction as good as Gboard's.
Shipped in v1.24.0 and v1.25.0; Ry tested it and called it done on
2026-09-26. What follows is why it works the way it does.

- **Measure it, don't eyeball it.** `AutocorrectScorecardTest` loads the real
  English `.dict` the phone loads, misspells 400 common words the way a thumb
  does, and scores fixed / wrong word / left alone, plus how many names and
  slang it wrongly changes. `SCORECARD_SWEEP=1` runs a grid over the
  `TypoModel.Tuning` knobs. Report is `keyboard/app/build/autocorrect-scorecard.txt`.
- **Pastiera never fixed an extra key or a dropped letter.** Fix-on-space
  shipped off, and even on, a safety rule vetoed any correction whose length
  differed from the typed word ("quyick" stayed). `TypoModel` replaces that
  decision for English: word frequency minus keyboard-aware slip cost. Other
  languages keep Pastiera's path until they are measured.
- **The English dictionary is written prose, not texting.** It has no "lol",
  "app", "oops" or "huh", so the corrector "fixed" them. `en_extra.json` patches
  the worst of it; a conversational frequency list is the real fix.
- **The previous word settles close calls.** `BigramModel` is a word-pair
  table counted from Tatoeba's everyday sentences
  (`scripts/build-english-bigrams.py`, which also writes the held-out test
  sentences). `ContextScorecardTest` measures on sentences the table never saw.
  Context may reorder candidates and add confidence but never vetoes a fix: a
  pair the table pruned reads as a poor fit, and letting that veto cost a fifth
  of all fixes. Pruning to 16 continuations per word had the same effect
  ("didn't let" read as rare); it keeps 256, stored as int arrays for memory.
- **Tatoeba overuses a few names** ("Tom" is in a large share of it). The
  build drops capitalised words other than "I" from predictions.
- **The bar's next-word order:** pairs you typed, then the bundled table, then
  Pastiera's most-common-words filler. English only.
- **Undoing a correction offers the word; it does not learn it.** v1.24.0
  learned on every undo, Gboard-style; one stray backspace put "impor" in Ry's
  dictionary, where it outranked "import". Pastiera's add-word offer is the
  behaviour Ry wants. A word he does add still beats the fixed contraction rules
  (`isUserWord`).
- **Apostrophe words are decided by context, not dropped.** "ill", "its",
  "lets", "cant", "wed", "shed", "shell", "id" are words and contractions both.
  Removing their rules (2026-09-25) made things worse: on held-out text the
  fixed rule was right 82-100% of the time and Ry noticed "Ill" at once. The
  rules are back and the previous word overrides them (`contextPrefersTypedWord`),
  which takes "its" from 82% to 93%.
- **"were"/"we're" and "well"/"we'll" are decided twice** (2026-09-27): by
  the word before at the space bar, then again once the next word is typed
  (`reconsiderContraction`), which only ever takes back an apostrophe the
  keyboard added. Before a comma or full stop the bare word stands ("Well,").
  `ContractionScorecardTest` measures it: 88.4% -> 97.7% on held-out text,
  plus Ry's own sentences from the phone. When the contraction's next-word
  pair was too rare to keep ("we'll want"), the late check borrows it from
  the family ("will want", `BigramModel.contractionFamily`), for 'll and 's
  only: pooling 're with "are" measured worse. "hell" -> "he'll" is left out on purpose: Tatoeba is tame
  and would turn "hell yeah" into "he'll yeah".
- **Two slips in one word are measured too** (2026-09-27): the scorecard's
  "first key" and "two keys" kinds, plus Ry's own misses ("jsve", "opsge").
  A wrong first letter costs 1.0 instead of 2.0 on typed words of four
  letters or more, an extra key before the first is not charged twice, and
  the threshold is 3.0: first-key slips 68% -> 87%, two-key 35% -> 46%, no
  change on single slips. Short words keep the old costs (two-letter words
  got worse), and so does `RealWordFixer` (`SLIP_TUNING`), which otherwise
  changed more clean words.
- **A dropped first letter is cheap, two-letter words are allowed** (stricter
  bar). Ry types "ight", "imes", "cn"; neither kind was ever fixed before. Both
  are too ambiguous to fix well without the previous word, so judge them on the
  context scorecard, not the plain one.
- **The bar while typing is `WordBarRanker`** (English): completions ranked by
  the previous word, with the space bar's correction in the first (centre)
  slot so the bar shows what space will do. Pastiera's list only fills gaps.
  Held-out: the word is in the bar after one letter 52% of the time (Pastiera
  16%). The typo fixer also blends in the table's everyday word counts
  (`everydayWeight`), because the prose dictionary thinks "wired" beats "weird".
- **Next-word prediction backs off from two words to one** ("I want" -> to):
  33% in the top three on held-out text, from 26%. The table holds up to
  40,000 two-word contexts; it is 4 MB.
- **Real-word slips are fixed one word late** (`RealWordFixer`: "we where
  going" -> were), in place, and an immediate backspace puts the word back.
  The bar is set by clean text, not by catches: 0.6 correct words changed per
  thousand for 42% of slips. Loosening it catches more and changes up to
  fifteen per thousand; do not.
- **The everyday table vetoes the typo fixer** (2026-09-27): a lowercase word
  of three letters or more seen 3+ times in it is a word, whatever the prose
  dictionary says. The dictionary lacked ~6,800 of them ("poop" became "pop";
  "grandma", "faucet", "jam"). `everydayKnownCount`; 3 cost a point of fixes
  on held-out text and saved a point of wrong ones.
- **Words with a digit are never corrected or looked up** ("4th", "4oz").
  Normalizing strips digits, so "4th" was judged as "th" and became "the",
  and the bar offered "this"/"that". Guarded in `TypoModel`, `WordBarRanker`,
  Pastiera's `SuggestionEngine` and the add-word offer.
- **A contraction's ending can be a slip** ("there'd gonna" -> there's),
  fixed one word late by `RealWordFixer.fixEnding`. Contractions' own pairs
  are thin, so the next word is judged against the family's pooled pairs
  ('d gets its own family there). Clean words changed stayed 0.6/1000; the
  catch rate is not measured, only Ry's sentence.
- **All of these numbers are measured on Tatoeba**, the same kind of text the
  table is built from, so Ry's own typing will score lower.
- **Ry's own typing is logged, so it can be the scorecard** (2026-09-27).
  `CorrectionAudit` appends every correction and every immediate-backspace
  undo to the keyboard's private files, debug builds only (releases are debug
  builds). `scripts/autocorrect-audit.sh` pulls it and sums undo rates per
  kind. Start any autocorrect work from its undone list, not from a
  screenshot, and turn what it shows into scorecard cases. Only a backspace
  straight after counts; a word fixed by hand later is invisible to it.

## The refiners are the heart of this app

The dictation quality is the product, and it comes from the system prompts in
`GroqRefiner.kt` and `CasualRefiner.kt`. Both files carry long comments
explaining *why* each rule is worded the way it is, usually because some earlier
wording failed in the wild. **Read those comments before touching either
prompt.** They are the design document.

### Rules that are not obvious from the code

- **`GroqRefiner.kt` is load-bearing and stable. Do not "improve" it.** The user
  relies on Default mode daily. Casual mode was built as a separate class
  specifically so the default path keeps a zero-line diff. Deleting
  `CasualRefiner.kt`, `RefineMode.kt`, `ModeToggleView.kt` and the toggle wiring
  must restore the previous behavior exactly. Preserve that property.

- **The guard helpers are duplicated on purpose.** `isInvented`, `isCleanEdit`,
  `contentTokens` and friends exist in both refiners. That is ~60 lines of
  deliberate duplication bought to keep the two paths independent. Do not
  refactor them into a shared base class or util.

- **`isInvented` is a hard gate and must stay one.** It catches the model
  ANSWERING a dictated question instead of editing it — which reached a real
  message once. Any prompt change that makes output diverge further from the
  input must be checked against it.

- **The Groq model id expires, and the app hides it.** Both refiners fall back
  to the raw transcript on any failure, so a decommissioned model id reads as
  "the cleanup stopped happening" with nothing on screen and no crash. It has
  happened twice now (llama-3.3-70b in August 2026, qwen3.6-27b in September).
  When refined output looks like bare Whisper, read the debug HTTP log before
  touching the prompt, and keep the id in both refiners in step.

- **Whisper's output is already punctuated and capitalized.** It usually returns
  document-style prose; on fast speech it returns bare unpunctuated text. Any
  prompt work has to handle both. Casual mode is mostly *subtraction*. Note the
  bare path is hard to test on demand - Whisper punctuated every attempt across
  a session of deliberately fast speech.

- **Casual mode's prompt is derived from the user's real hand-TYPED messages.**
  Never add an example taken from dictated output — it has already happened once
  and produced a rule that was a transcription artifact rather than a habit. If
  you need a new example, ask the user to type how they would have written it.

- **`CasualRefiner`'s prompt IS `GroqRefiner`'s prompt plus a short delta.** The
  opening paragraph, the numbered rules and the whole hard-rules block are
  carried over; the additions are the five CASUAL HAND bullets and the examples.
  Keep it that way, and when you edit the default prompt, edit the carried-over
  copy to match. An earlier version diverged into a two-and-a-half-times-longer
  two-pass typesetting procedure. Every rule in it was justified by a real
  failure and the result was still worse: it read like 2012-era voice dictation,
  and rule 1 stopped deleting filler even though its wording never changed.
  **Length in this prompt is spent out of rule 1's budget.**

- **A rule that ADDS or CHANGES must be carved out of every hard rule that
  forbids it, not just the nearest one.** Rule 3 (the spelling instruction)
  respells a word, which both "every word you KEEP must stay exactly as the user
  said it" and "you may fix casing and add a missing apostrophe" prohibit.
  Carving it out of one of them left the feature silently half-working across
  four live tests: it deleted the instruction every time and never once applied
  the letters.

- **An example only teaches when its Input and Output disagree about the thing
  being tested.** Rule 3 shipped with examples whose spelled letters matched what
  the model would have written anyway, so none of them established that the
  letters outrank the transcript. Two "passing" live tests were coincidences
  where Whisper had already guessed right.

## Verifying a prompt change

Text-level unit tests only cover the guards, not the prompt. To actually check a
change:

```
./gradlew test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s GroqRefiner:I CasualRefiner:I
```

Debug builds log `raw:` and `refined:` with a verdict (`clean-edit`, `rewrite`,
`invented`, `empty`). **Always read the raw transcript before blaming the
refiner** — several apparent refiner bugs turned out to be Whisper mishearing,
truncating, or already having made the "mistake". Logging is debug-only because
the text is the user's private messages.

To judge whether casual output sounds like the user, measure it rather than
eyeball it: sentence-length distribution and commas-per-100-words against their
own typed messages. Averages matched long before the output stopped reading
like a machine; the defect was in the tail.

## Conventions

- Feature branches (`feature/...`, `fix/...`), merged to `main`.
- No em dashes in user-facing copy.
- Comments explain *why*, not what. Match the density already in the file.
