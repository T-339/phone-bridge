# Press the Essential Key to talk to your AI

**A design brief from a Nothing Phone (3a) Pro owner, with a working prototype.**
Repo: https://github.com/T-339/phone-bridge (GitHub: T-339)

## The idea in one line

Press the Essential Key and talk: your phone wakes, opens your AI assistant (here, Claude), starts listening,
and gives one short buzz when it's ready. Press again and your message is sent. No unlocking, no hunting for
a mic button, no looking at the screen.

## Why the Essential Key is right for this

- **It's a dedicated, physical button that nothing else uses.** It can be found by touch, in a pocket,
  while driving or with your hands full.
- **"Press to talk, press to send" fits a button.** It's simple, it can be done by feel, and it works the same
  whatever state the phone is in.
- **It matches Nothing's direction.** Essential Space already uses the key to capture things for AI. This
  lets you talk to an assistant directly, and the conversation continues in the app.
- **It can live alongside Essential Space.** In the prototype a switch in the notification gives the key back.

## What it's like to use

| You do | What happens |
|---|---|
| Press the key (screen on or off) | Screen wakes if needed, Claude opens, voice input starts, **one buzz** = start talking |
| Press it again | Dictation stops and the message is sent |
| Start with "new chat, …" | A fresh chat opens and the rest of the sentence is sent there |
| Say "cancel that" / "never mind" | Nothing is sent (two buzzes) |
| Something goes wrong (e.g. phone securely locked) | **Two buzzes**, so you know not to talk |

From the press to the buzz takes about 0.5 s when the screen is on, and about 1.7 s from a dark screen.

We first built **hold to talk** (release to stop). It worked well with the screen on, but from a dark screen the
system uses the press to wake the phone and apps only see the release. So hold-to-talk can't be done reliably
by an app, which is a good reason for Nothing to build it in.

**Day-to-day notes:** *to be added after a few weeks of real use.*

## How the prototype works

Phone Bridge is an Android accessibility-service app (Kotlin, no root, no ADB). It was originally built so
Claude can operate the phone remotely, and the Essential Key feature is built on top of it.

1. **Seeing the key.** The Essential Key arrives as `KEYCODE_UNKNOWN` (key code 0) with **scan code 250**, so
   the app matches it on the scan code.
2. **Getting Essential Space out of the way.** Nothing's system opens Essential Space
   (`com.nothing.ntessentialrecorder`) as soon as the key goes down, *before* an accessibility service sees
   the event. That means an app can't block it. The workaround is Nothing's own setting:
   **Intelligence toolkit → Essential Key Settings → "Activate with Single Tap Before Use"**. With it on, a
   single press only arms the key, and Phone Bridge handles that press.
3. **Starting voice.** On a press, the app opens Claude and taps its "Start speech input" button. It watches
   for "Stop dictation" to appear, which shows that Claude is listening, then buzzes. On the next press it taps
   "Stop dictation", waits for the text, checks for "new chat" or "cancel", and taps Send.
4. **Screen off.** The app turns the screen on with a transparent activity (`setTurnScreenOn`,
   `requestDismissKeyguard`). With the screen off, the system often uses the key-down only to wake the
   screen, so the app treats a key-up with no key-down as a press.
5. **Locking.** To make screen-off use practical, "Power button instantly locks" is turned off and the phone
   locks after a delay. The app adds a **Lock** button in its notification and a **Lock phone**
   Quick Settings tile for an immediate secure lock.
6. **Switching back.** A switch in the app, also available as a button in the notification, gives the key
   back to normal Essential Space behaviour.

Battery: Phone Bridge used under 1% of the battery since the last full charge, with nearly two hours of
background running.

## What we found out about the Essential Key

Hopefully these findings are useful to whoever owns the key at Nothing:

- The key has no named key code, only a scan code (250). A proper key code would make it far easier for
  apps to use.
- Essential Space reacts to the key before accessibility services do, so third-party remappers can't stop
  it cleanly. Today they have to disable the Essential Space app over ADB or rely on the "single tap" setting.
- With the screen off, the key-down is sometimes used only to wake the device, and apps see just the
  key-up.

## What a built-in version could look like

- **An official Essential Key action: "Hold to talk to your assistant".** It would use Android's default
  digital assistant (the `ROLE_ASSISTANT` / voice interaction route), so it works with Claude, Gemini,
  ChatGPT or whatever the user picks, not just one app.
- **Proper hold-to-talk as well as press-to-talk:** listening starts while the key is held and stops on
  release, even from a dark screen, with a haptic "listening" cue and a different one for errors.
- **Works from a dark screen**, handling the lock screen the way the system already does for assistants.
  That would avoid the prototype's lock-delay compromise.
- **Keeps Essential Space one gesture away** (for example tap-then-press, or a double press), so the key
  gains a feature without losing one.

## Limitations of the prototype

- It finds Claude's buttons by their accessibility labels, so a Claude app update could break it.
- Dictation picks up nearby voices, so it works best in a quiet place or with the mic close.
- It needs an accessibility service, which is a powerful permission and not something to ask of everyone.
- Using it from a dark screen relies on a lock delay of up to 30 minutes. A built-in version could do this
  safely.

## Use of this work

The author is happy for Nothing to use this idea and any of this code however they like. The relevant code
is `android/app/src/main/java/dev/phonebridge/EssentialKey.kt` (key handling, voice, haptics) and
`WakeLock.kt` (screen wake and lock).
