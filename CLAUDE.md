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

- **The accessibility service is optional and must stay optional.** Without it
  the overlay still works, it just stops pasting for you. That is what keeps the
  "Allow restricted settings" unlock off the critical path for a new user.

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
