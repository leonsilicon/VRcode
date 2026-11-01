# VRcode

## Elevator pitch

Turn your room into a coding workspace. Live Mac terminals, hand gestures, and voice-controlled AI on Meta Quest.

## Inspiration

Coding with AI increasingly means describing what you want, watching an agent work, and moving between several terminal sessions. All of that still competes for space on a laptop screen.

We wanted a workspace where each task could have its own place around you, and where speaking to an agent felt as immediate as reaching for a tool. A headset gives us room to spread out. Hand tracking and voice give us ways to work with that space.

VRcode brings those ideas together: your Mac runs the tools, and your Quest becomes the workspace.

## What it does

VRcode is a mixed reality coding workspace for Meta Quest. It connects to real terminal sessions on your Mac and displays them as floating windows over passthrough. You can arrange terminals around you, move them closer, resize their grids, and return to a saved layout.

The core interaction is two hands, two microphones:

- **Right fist: dictate.** Close your hand to speak, see a live transcript above a virtual microphone, then open your hand to type the text into the focused terminal.
- **Left fist: ask AI.** Speak an instruction with the terminal's visible contents and cursor as context. The selected AI returns text and key presses to that terminal, including Enter when appropriate. In thinking mode, a coding agent can inspect the project in the terminal's working directory before answering, with progress shown in the headset.

Palm menus provide quick access to new terminals, Codex, Claude Code, and common keys such as Enter, Escape, and Ctrl-C. A floating dock recalls windows, opens the keyboard, arranges the workspace, and exposes settings. Terminal actions cover panes, tabs, screens, and workspaces through the Mac's background terminal service.

The app supports Claude, OpenAI, and Gemini for the AI mic. A Mac menu-bar companion manages the services, headset pairing, provider keys, and mirrored Quest settings. The Mac–Quest connection uses an authenticated TLS relay, with Wi-Fi, Bluetooth, and USB/ADB transport options.

## How we built it

The Quest app is native **Kotlin**, built with **Meta Spatial SDK**, **Jetpack Compose**, and **OpenXR**. Compose provides the panels and settings; the spatial runtime places them in the room. A small **C++/JNI** bridge reads hand tracking and per-finger pinch signals for gestures beyond a standard pointer click.

We built on **cmux** and **xmux**. cmux owns the terminal processes, panes, tabs, and session state on the Mac. xmux carries that state to the headset and renders the terminal windows. VRcode adds the spatial workflow: gestures, voice, AI input, window management, and settings. Saved window placements live alongside the session in cmux.

The Mac service is written in **Swift**. It transcribes headset audio locally with Apple's **SpeechAnalyzer** and **SpeechTranscriber**, forwards terminal operations, and handles AI requests. Quick answers use provider APIs; thinking mode uses **Claude Code**, **Codex**, or **Gemini CLI** to inspect context and return structured terminal input.

The companion app uses **Tauri 2**, **Rust**, and **HTML/CSS/JavaScript**. Provider API keys stay in the Mac's Keychain. **Bun and TypeScript** handle repository tooling, and **Blender** supplies editable sources for the virtual microphone props.

## Challenges we ran into

- **Making gestures deliberate.** Pinch signals flicker, hands briefly lose tracking, and a fist can resemble part of a menu gesture. We added release gates, tracking grace periods, dwell feedback, and cancellation rules so those transitions do not accidentally launch actions.
- **Keeping terminal focus consistent.** Pointing, keyboard input, dictation, and delayed AI responses all need a clear target. Voice captures its destination when the fist opens, so an answer arriving later goes to the intended pane.
- **Making resizing useful for code.** A larger terminal needs more rows and columns. We connected spatial edge resizing to cmux's terminal grid sizing, while keeping corner scaling available for readability.
- **Keeping both devices in sync.** Terminal state, saved placements, reconnects, and settings cross the Mac–Quest boundary. We gave each kind of state a clear owner and built synchronization around it.

## Accomplishments that we're proud of

We brought live terminal sessions, spatial window management, hand input, speech recognition, and coding-agent context into one native Quest application.

The two-microphone interaction gives dictation and AI assistance distinct physical gestures. Palm shortcuts make existing coding tools accessible from the headset, while the dock gives users a way to recover a window that has moved out of view.

We also built a Mac companion that supervises the bridge and service, manages pairing and keys, and mirrors headset settings, bringing the setup into one place.

## What we learned

Spatial interaction needs clear feedback at every stage: a gesture being recognized, an action being selected, and an operation completing. Small interruptions in tracking become much more noticeable when your hand is the input device.

We also learned how much terminal context matters for voice. Literal dictation is useful, but an instruction becomes more useful when the AI can see the current terminal and inspect the relevant project. Returning structured text and key presses lets that assistance work with existing terminal tools.

## What's next for VRcode

- Add native Mac display streaming and pointer input so desktop apps can sit beside the terminals.
- Bring Mac system audio into the headset.
- Add persistent spatial anchors so saved windows stay tied to the room across recentering.
- Continue testing gesture comfort, discoverability, and reliability with people wearing the headset.
- Refine installation and connection setup toward the longer-term goal of working from the headset with the MacBook closed.

## Built with

Meta Quest, Meta Spatial SDK, Kotlin, Jetpack Compose, OpenXR, C++, JNI, cmux, xmux, Swift, SpeechAnalyzer, SpeechTranscriber, Tauri 2, Rust, HTML, CSS, JavaScript, TypeScript, Bun, Blender, Claude, OpenAI, Gemini, Claude Code, Codex, Gemini CLI.

## Source code

[VRcode on GitHub](https://github.com/leonsilicon/VRcode)
