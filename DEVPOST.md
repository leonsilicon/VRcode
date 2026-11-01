## Inspiration

Seeing the crazy vibe coding agent harness local max model setups everyone else had, I felt like I was falling behind as a developer/vibe coder/AI cheerleader.

So, I built VRcode to outLARP them, because with VRcode, you can now LARP as the elite AI-native extra productive vibe coder that brings his AI agents along with them in their field of view on-the-go; the ultimate vibe coder setup that every vibe coder dreams of having.

## What it does

VRcode makes it possible to surround yourself with hundreds (in theory) of terminal windows, each running their own agent, whether it's Claude Code, Codex, or whatever new one just launched a week ago.

The Meta Quest uses ElevenLabs's Speech-to-Text API so you can actually control your agents by talking to them (because the built in speech-to-text on the Quest, unsurprisingly, sucks).

Without ElevenLabs, you'd have to rely on a virtual keyboard to type out commands/prompts--and that's not LARPing; that's an aura loss.

The best part about VRcode? It all runs on your computer, so your Quest says smooth and buttery for you to continue wearing even as you walk down the streets of Downtown, LARPing with the dream vibe coder setup every developer wishes they had.

(You can even wear it to sleep, though results may vary)

And because it runs on your computer, if you ever need a break from the headache from ~~wearing a giant computer on your head~~ the difficult, elite task of managing a swarm of AI agents around you, you can just open up your MacBook to continue where you left off, since it runs using cmux as the source of truth.

Your agents stay running, even if you're not.

Also, developers don't type anymore. LARP as a vibe coder from the future by coding with just gestures and your voice:

- **Close your right fist and put it up to your mouth like you're holding a mic.** A mic will appear in your hand so you can use your mighty voice to command the AI to do what you want it to do. Not loud enough? Change it to a megaphone in the settings.
- **Close your left fist and put it up to your mouth like you're reporting the news.** This is the "AI Mic" that lets you talk to an AI to control the terminal itself for spawning AIs. Meta-Mega-LARP.
- **Quick palm gestures for quick actions.** Because it looks so much cooler when you LARP like you're physically pulling a terminal out of thin air. Because you are.
- **A floating dock** beneath you for you to admire the 67 terminal windows and AI agents you've got running all at once because, again, you are the ultimate LARP elite 10x 100x vibe coder that everyone else dreams of becoming.

WARNING: VRcode is not responsible for any delays in deploying, launching, shipping, actually getting users, or making revenue because of how easy it becomes to LARP as a elite 10x vibe coder that has hundreds of terminals but not a single customer. Getting actual users is left as an exercise to the reader.

## How we built it

I built VRcode, with VRcode (well, once it worked). I literally walked to get a Chipotle while wearing my VR asking the Claude terminal by my side to optimize the code.

Otherwise, I vibe coded it, larping as an expert Meta Quest developer using all these complex and technical technologies (I've only ever built one Quest app in my life):

The Quest app is native **Kotlin** with **Meta Spatial SDK**, **Jetpack Compose**, and **OpenXR**, plus a **C++/JNI** bridge for hand tracking and per-finger pinches (the fist clench is load-bearing). Terminals stream from the Mac through **cmux** and **xmux**. A **Swift** service transcribes your voice locally with Apple's **SpeechAnalyzer** and sends your vibes to **Claude**, **OpenAI**, or **Gemini**. In "thinking mode," **Claude Code**, **Codex**, or **Gemini CLI** actually inspects your codebase, so at least something in the room understands it. A **Tauri 2** / **Rust** menu-bar companion handles pairing and API keys, and **Blender** gave us very aesthetic virtual microphones for the screenshots.

## Challenges we ran into

- **Too many terminals.** We ran out of room in an actual room. Once you become elite enough, you probably need a better way of managing terminals than just ad-hoc dragging them around.

## Accomplishments that we're proud of

- Ran more simultaneous AI coding agents than we have products.
- Built a tool that makes doing nothing look like orchestrating everything.
- On average, built a new feature every fifteen minutes. (Though we still haven't launched.)

## What we learned

Real productivity was the agents we spawned along the way.

Also, a headset is the only monitor big enough to hold every "quick side project."

This is the future of vibe coding. Download and use VRcode now to become an early LARP elite 10x vibe coder before you **really** fall behind.
