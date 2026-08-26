# Mutterboard

Android voice-dictation keyboard (IME). Record, transcribe, commit text into
whatever field the user is in.

Two transcription engines, chosen in the app: **Default** (cloud — Groq
Whisper V3 Turbo) and **Offline** (on-device Parakeet). On the cloud path only, a
second LLM pass then cleans the transcript up before it is committed.

Two refine modes for that pass, toggled from the keyboard itself:
`GroqRefiner` (Default) and `CasualRefiner` (Casual).

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
  prompt work has to handle both. Casual mode is mostly *subtraction*.

- **Casual mode's prompt is derived from the user's real hand-TYPED messages.**
  Never add an example taken from dictated output — it has already happened once
  and produced a rule that was a transcription artifact rather than a habit. If
  you need a new example, ask the user to type how they would have written it.

- **Order matters in the casual prompt: split into sentences first, strip
  typesetting second.** Whisper's commas are often the only marker of where one
  thought ends. Rules that ADD lose to rules that DELETE unless the adding step
  is made to happen first.

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
