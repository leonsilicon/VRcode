# Asset sources

The two imported models are from Poly Haven and licensed
[CC0](https://polyhaven.com/license). The exports adjust scale, orientation, grip origins,
material names and triangulation for the vrcode voice-prop starter set. Source textures are
embedded at 1k. Credits are retained for provenance even though CC0 does not require attribution.

| Export | Source asset | Artist | Changes |
|---|---|---|---|
| `megaphone.glb` | [Megaphone 01](https://polyhaven.com/a/Megaphone_01) | Ethan Simon-Law | 28 cm length, grip origin, rotation, speech anchor, explicit triangles |
| `banana.glb` | [Bananas](https://polyhaven.com/a/bananas) | Alexander Shulha | One individual banana, 20 cm length, upright orientation, grip and speech anchor |
| `news-microphone-<provider>.glb` | Original vrcode asset; provider symbols below | vrcode / Anthropic, OpenAI, Google | 28 cm reporter microphone, foam texture, black flag with the Claude, OpenAI or Gemini symbol, grip and speech anchor |

`../microphone/microphone.glb` is the existing original procedural vrcode microphone and is
referenced by the catalog; it contains no third-party geometry or textures.
The news microphone geometry and foam texture are original vrcode work. Its cube shows the logo
of the AI provider chosen in Settings, to identify the AI voice-input mode. The app loads
`news-microphone-claude.glb`, `news-microphone-openai.glb` or `news-microphone-gemini.glb`; they
differ only by the flag texture. The marks belong to their owners: the Claude spark to
Anthropic, the OpenAI Blossom to OpenAI and the Gemini sparkle to Google. Their use here is an
in-app indicator of the selected provider and implies no endorsement.

| Flag | Symbol | Owner | Vector source |
|---|---|---|---|
| `claude-flag.png` | Claude spark, #D97757 on black | Anthropic | `claude.svg` from [Simple Icons](https://simpleicons.org/) 16.35.0 |
| `openai-flag.png` | OpenAI Blossom, white on black | OpenAI | `openai-blossom.svg` from the [Wikimedia copy](https://commons.wikimedia.org/wiki/File:OpenAI_logo_2025_(symbol).svg) |
| `gemini-flag.png` | Gemini sparkle, blue → purple → pink gradient on black | Google | `googlegemini.svg` from [Simple Icons](https://simpleicons.org/) 16.35.0 |

[Simple Icons](https://github.com/simple-icons/simple-icons) publishes its SVG data under CC0-1.0;
the dedication covers the path data, not the trademarks. `claude-flag.svg` and
`gemini-flag.svg` place those unaltered paths on a black 512 px square, and `resvg` rasterizes
them to the PNGs. All three vectors were sourced on 2026-11-01. See
[OpenAI's brand guidelines](https://openai.com/brand/).

Source pages and the Poly Haven license were checked on 2026-11-01. The preview PNGs in this
folder were rendered locally from the models; they are not copied Poly Haven website renders.
