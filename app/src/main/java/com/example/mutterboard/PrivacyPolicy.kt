package com.example.mutterboard

/**
 * In-app copy of the privacy policy, shown in a dialog from Settings. Kept in
 * sync with PRIVACY.md at the repo root (which doubles as the public policy URL
 * required for a Play Store listing). When you change one, change the other.
 */
const val PRIVACY_POLICY_UPDATED = "Last updated: 30 June 2026"

const val PRIVACY_POLICY_TEXT = """Mutterboard is a voice keyboard: it turns your speech into text and inserts that text into whatever app you are using. In short: Mutterboard has no accounts, no analytics, no advertising, and no third-party tracking.

WHAT IS RECORDED, AND WHEN
Mutterboard records audio only while you are actively dictating. It does not listen in the background or record at any other time. How that audio is handled depends on the transcription mode:

• Cloud mode (default): your recorded audio is sent over an encrypted (HTTPS) connection to Groq (api.groq.com) to be transcribed by the Whisper model. Your Groq API key is sent with the request to authenticate it. Groq's handling of that audio is governed by Groq's own privacy policy at groq.com/privacy-policy. Mutterboard does not keep a copy of the audio after transcription.

• On-device mode: your audio is transcribed locally on your phone using the Parakeet model. In this mode your audio never leaves your device.

In both modes, the recording is written to a temporary file in the app's private cache only for as long as it takes to transcribe, and is deleted immediately afterward.

YOUR TRANSCRIBED TEXT AND WHAT YOU TYPE
The transcribed text is inserted into the app you are typing in. Mutterboard does not store or log your transcribed text, and does not transmit it anywhere except in the one case described under "LLM Enhanced" below. As a keyboard, Mutterboard does not capture your keystrokes and does not read the existing contents of the text fields you use it in.

LLM ENHANCED (OPTIONAL)
"LLM Enhanced" is an optional setting for Cloud mode, off by default. When you turn it on, your transcribed text is sent over an encrypted (HTTPS) connection to Groq (api.groq.com) to be lightly cleaned up by an AI language model before it is typed out. Your Groq API key is sent with the request to authenticate it. Groq's handling of that text is governed by Groq's own privacy policy at groq.com/privacy-policy. Mutterboard does not keep a copy of the text after the cleanup, and when this setting is off your transcribed text is never sent anywhere.

YOUR GROQ API KEY
If you use Cloud mode, your Groq API key is stored locally on your device in the app's private storage. It is only ever transmitted to Groq, and only to authenticate your transcription requests. It is never sent anywhere else.

NETWORK CONNECTIONS
Mutterboard makes network connections only for: (1) cloud transcription — sending audio to Groq, in Cloud mode only; (2) the LLM Enhanced cleanup step — sending your transcribed text to Groq, only when you have turned that setting on; (3) checking for app updates from GitHub; and (4) a one-time speech-model download from GitHub if you enable On-device mode. These requests carry no personal information beyond what is described above.

ANALYTICS, ADVERTISING, AND TRACKING
There are none. Mutterboard contains no analytics, crash-reporting, advertising, or third-party tracking SDKs.

PERMISSIONS
• Microphone — to record your speech while dictating.
• Internet — for cloud transcription and update checks.
• Install packages — used by the in-app updater to install updates you choose to download.

CHILDREN
Mutterboard is not directed at children and does not knowingly collect data from children.

CONTACT
Questions about privacy? Reach out by email at ryskelliekeel@gmail.com."""
