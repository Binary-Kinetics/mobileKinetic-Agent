# mobileKinetic:Agent (mK:a)

An AI agent platform for Android. Gives an AI direct access to your phone's hardware, communication, and system APIs. No root required.

<a href="https://www.paypal.com/donate/?hosted_button_id=QYLDXTFCSVKM8">
  <img src="https://img.shields.io/badge/Donate-PayPal-blue.svg?style=for-the-badge&logo=paypal" alt="Donate via PayPal" />
</a>

---

## This App Is Dangerous

Not in a fun edgy marketing way. In a "this AI can send texts, make phone calls, read your notifications, control your Bluetooth, and access your camera" way. Without root.

49 permissions. 70 device API endpoints. 55 MCP tools. Full shell access through an embedded Linux terminal. This is as deep as you can get into Android without rooting it.

It does what you tell it to do. If you tell it something stupid, it'll do that too. There are no training wheels. Understand what you're granting before you grant it. I tried to imbed things that keep your details from being sent for inference, but ultimately YOU have to be careful,

---

## What It Is

An Android app that bundles a Termux-based Linux environment (Python, Node.js, bash) with a tiered API system that exposes phone capabilities to an AI agent.

Built and tested with **Claude Code** (Anthropic's CLI agent). Works with any AI that handles tool use:

- **Claude** (CLI-based, what I use)
- **OpenAI** (GPT-4 and compatible)
- **Google Gemini**
- **Custom** (Ollama, LM Studio, any OpenAI-compatible endpoint)

The AI needs to be good enough to reason through multi-step tool use. The provider is swappable through the setup wizard.

---

## What It Does

**Device Control.** Battery, GPS, WiFi, Bluetooth (classic + BLE), all sensors, screen, volume, flashlight, vibration, NFC, camera.

**Communication.** SMS/MMS, phone calls (place, answer, DTMF), contacts, notification read/dismiss/reply.

**Automation.** Calendar events, CalDAV task sync (JTX Board), Tasker integration, alarms, geofencing, accessibility-based UI automation.

**On-Device LLM.** Little AIs helping big AIs. A locally embedded Gemma 3 1B runs on the GPU for three things: redacting sensitive information before it leaves the device, compressing conversation context to stretch the AI's context window, and building session summaries that carry context across conversations. The privacy filtering and context cleanup happen **entirely** on-device. Pan-session memory works, meh, now but has room to grow.

**Memory.** On-device RAG with ONNX embeddings (all-MiniLM-L6-v2, 384-dim). Ebbinghaus-style decay. Remembers what gets used, forgets what doesn't.

**Security.** Biometric vault backed by StrongBox Titan M2. AES-256-GCM field encryption inside SQLCipher database encryption.

**Voice.** Streaming TTS with pluggable providers (Kokoro, Android, ElevenLabs, OpenAI TTS). 3D audio visualization via Three.js.

**QuickApps.** You can ask for things that normally required an app to have access to.  This will craft applications on the fly and present them as web pages. For instance, if you need a level app, just ask. And in moments it makes the app on the fly which gets saved as a template for future use, and disassembled when done. If you have a lazy weekend you can sit and make QuickApps for the things YOU find yourself needing.

---

## Installing

If you get stuck at any step, paste the error into ChatGPT, Claude, or Gemini. They can walk you through it.

### What You Need

- An Android phone (API 26+, which is Android 8.0 or newer)
- A computer with [Android Studio](https://developer.android.com/studio) installed
- About 15 minutes

### Steps

**1. Clone the repo**
```bash
git clone https://github.com/Binary-Kinetics/mobileKinetic-Agent.git
cd mobileKinetic-Agent
```

**2. Open in Android Studio**

Open the `mobileKinetic-Agent` folder as a project. Android Studio will download dependencies automatically. This takes a few minutes the first time.

**3. Build**
```bash
./gradlew assembleDebug
```
Or just hit the green play button in Android Studio.

**4. Install on your phone**

- Enable **Developer Options** on your phone (Settings > About Phone > tap Build Number 7 times)
- Enable **USB Debugging** in Developer Options
- Connect your phone via USB
- Click "Run" in Android Studio, or:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**5. First run**

The app extracts the Linux environment, installs Python, Node.js, and the AI toolchain. One-time process. Takes a few minutes.

**6. Configure your AI**

The setup wizard walks you through choosing your AI provider and entering your API key. Pick Claude, OpenAI, Gemini, or point it at your own local model.

That's it.

---

## Providers

AI and TTS backends are swappable through the setup wizard:

| Provider | Type | Notes |
|----------|------|-------|
| Claude CLI | Local process | What I built and tested with daily |
| OpenAI | API | GPT-4 and compatible models |
| Google Gemini | API | Gemini Pro and compatible |
| Custom | API | Ollama, LM Studio, any OpenAI-compatible endpoint |

TTS:

| TTS Provider | Type | Notes |
|-------------|------|-------|
| Kokoro | WebSocket | Self-hosted, what I use on my home LAN |
| Android TTS | Built-in | Works everywhere, no setup needed |
| ElevenLabs | API | High quality, requires API key |
| OpenAI TTS | API | Good quality, requires API key |
| None | | Text only, no voice |

---

## Architecture

```
+------------------------------------------------------------------+
|                    mK:a Android App                               |
|                                                                   |
|  Compose UI (Material 3)                                          |
|  +----+----------+--------+--------+--------+--------+            |
|  |Chat|  Terminal | Tools  |Settings| Vault  | Setup  |            |
|  +----+----------+--------+--------+--------+--------+            |
|       |                                                           |
|  +------------------+  +------------------+  +----------------+   |
|  | AI Process       |  | TTS Manager      |  | Conversation   |   |
|  | Manager          |  | (Pluggable)      |  | Repository     |   |
|  +--------+---------+  +------------------+  +----------------+   |
|           |                                                       |
|  +--------v--------------------------------------------------+    |
|  |              MCP Server Ecosystem                         |    |
|  |  Tier 1: DeviceApiServer.kt  (70 Android API endpoints)  |    |
|  |  Tier 2: device_api_mcp.py   (Shell/filesystem)           |    |
|  |  stdio:  unified_device_mcp.py  (55 MCP tools)            |    |
|  +-----------------------------------------------------------+    |
|                                                                   |
|  Termux Bootstrap (bash, Python 3.12, Node.js)                    |
+-------------------------------------------------------------------+
```

### On-Device Ports (localhost only)

| Port | Service | Purpose |
|------|---------|---------|
| 5562 | RAG Server | Memory and knowledge search |
| 5563 | Device API | 70 hardware/system endpoints |
| 5564 | Python MCP | Shell and filesystem operations |
| 5565 | Vault | Encrypted credential storage |

### Security

- All servers bind to `127.0.0.1` only. Nothing exposed to the network.
- SQLCipher full-database encryption.
- Vault uses hardware-backed keys (StrongBox Titan M2 where available).
- Biometric authentication for credential access.
- Process isolation via Android app sandbox.

---

## Tech Stack

| What | Tech | Version |
|------|------|---------|
| Language | Kotlin (K2) | 2.3.0 |
| UI | Jetpack Compose + Material 3 | 2026.01.01 |
| Database | Room + SQLCipher | 2.8.4 / 4.5.4 |
| ML | ONNX Runtime (embeddings) | 1.21.0 |
| Media | Media3 ExoPlayer | 1.6.0 |
| DI | Hilt | 2.59 |
| Build | AGP + Gradle | 9.0.1 |
| Min SDK | 26 (Android 8.0) | |
| Target SDK | 36 | |

---

## A Personal Note

I'm a retired guy going through pretty big life changes. Major move coming in Spring with new choices and figuring out what's next. I got into this project after I got caught up in what AI could do. I wanted to see how far I could push an agent on a phone and it turned into something that sank its teeth in to me.

I built this with what I have: I used Claude as my AI, built my own self-hosted TTS setup (Kokoro and LuxTTS on my home network), and used my daily phone. That's it. One person, with a few late nights.

If I had more resources I'd love to dig deeper. More AI providers, more voice models, more devices, more automation. There's a lot of potential with a lot of bugs to catch, and I've only scratched the surface of what's possible.

If you find this useful, fun, or even just interesting, and you want to see it grow, please consider donating. It helps cover API costs and gives me a what you need to keep going with this.

<a href="https://www.paypal.com/donate/?hosted_button_id=QYLDXTFCSVKM8">
  <img src="https://img.shields.io/badge/Support_This_Project-PayPal-blue.svg?style=for-the-badge&logo=paypal" alt="Support via PayPal" />
</a>

If donations add up enough I'll GLADLY expand AI support, voice models, and device compatibility. This isn't cheap to build and test, but it's worth it if people are getting value from it. I'd love to have this on a rooted device to see if it could be an OS.

---

## License

GPLv3. See [LICENSE](LICENSE) for details.

Terminal emulator components derived from [Termux](https://github.com/termux/termux-app), licensed under GPLv3. See [NOTICE](NOTICE) for full attribution.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup and guidelines.

See [ARCHITECTURE.md](ARCHITECTURE.md) for technical documentation.
