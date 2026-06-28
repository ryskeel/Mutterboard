# Mutterboard Privacy Policy

_Last updated: 28 June 2026_

Mutterboard is a voice keyboard: it turns your speech into text and inserts that
text into whatever app you are using. This policy explains exactly what the app
does and does not do with your data. In short: Mutterboard has no accounts, no
analytics, no advertising, and no third-party tracking.

## What is recorded, and when

Mutterboard records audio **only while you are actively dictating** (from when you
start recording until you stop). It does not listen in the background or record at
any other time.

How that audio is handled depends on the transcription mode you choose:

- **Cloud mode (default):** your recorded audio is sent over an encrypted (HTTPS)
  connection to **Groq** (`api.groq.com`) to be transcribed by the Whisper
  speech‑to‑text model. Your Groq API key is sent with the request to authenticate
  it. Groq's handling of that audio is governed by Groq's own privacy policy:
  <https://groq.com/privacy-policy/>. Mutterboard itself does not keep a copy of
  the audio after transcription.
- **On‑device mode:** your audio is transcribed locally on your phone using the
  Parakeet model. In this mode **your audio never leaves your device.**

In both modes, the recording is written to a temporary file in the app's private
cache only for as long as it takes to transcribe, and is deleted immediately
afterward.

## Your transcribed text and what you type

The transcribed text is inserted into the app you are typing in. Mutterboard does
**not** store, log, or transmit your transcribed text anywhere. As a keyboard,
Mutterboard does **not** capture your keystrokes and does **not** read the
existing contents of the text fields you use it in.

## Your Groq API key

If you use Cloud mode, your Groq API key is stored **locally on your device** in
the app's private storage. It is only ever transmitted to Groq, and only to
authenticate your transcription requests. It is never sent anywhere else and is
never collected by the app's developer.

## Network connections

Mutterboard makes network connections only for:

1. **Cloud transcription** — sending audio to Groq (Cloud mode only).
2. **Checking for app updates** — a request to GitHub for the latest release.
3. **One‑time model download** — if you enable On‑device mode, the speech model is
   downloaded once from GitHub.

These requests carry no personal information beyond what is described above.

## Analytics, advertising, and tracking

There are none. Mutterboard contains no analytics, crash‑reporting, advertising,
or third‑party tracking SDKs.

## Permissions

- **Microphone** — to record your speech while dictating.
- **Internet** — for cloud transcription and update checks.
- **Install packages** — used by the in‑app updater (in the version distributed
  via GitHub) to install app updates you choose to download.

## Children

Mutterboard is not directed at children and does not knowingly collect data from
children.

## Changes to this policy

This policy may be updated from time to time. The "last updated" date above
reflects the most recent revision.

## Contact

Questions about privacy? Reach out by email at
[ryskelliekeel@gmail.com](mailto:ryskelliekeel@gmail.com).
