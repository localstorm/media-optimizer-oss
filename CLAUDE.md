# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Quality Commands

```bash
# Full build
mvn clean package

# Run Checkstyle (validate phase)
mvn validate

# Run SpotBugs (verify phase)
mvn verify

# Build a single module
mvn -pl media-optimizer-processor clean package

# Run tests for a single module
mvn -pl media-optimizer-processor test

# Run a single test class
mvn -pl media-optimizer-processor test -Dtest=YTMediaOptimizationPipelineTest
```

## Code Standards (from SPEC/java-preferences.md)

- **No Lombok**, no `volatile` (use `java.util.concurrent.atomic.*` instead)
- **SLF4J** for all logging — no `System.out`
- Package prefix: `co.kuznetsov.mediapipe`
- Checkstyle enforces: no star imports, no unused imports, max 500 lines per file, braces required
- Spring Boot for microservices (when server/worker modules are built out)

## Architecture

This is a multi-module Maven project (Java 21). The core logic lives entirely in `media-optimizer-processor`; the other modules (`server`, `worker`, `dashboard`) are stubs.

### Pipeline (media-optimizer-processor)

The main entry point is `YTMediaOptimizationPipeline.process(MediaOptimizationJob)`, which runs three sequential steps:

1. **Transcriber** — Downloads audio via `yt-dlp`, runs `whisper tiny` for transcription, falls back to YouTube auto-captions if Whisper fails
2. **Narrator** — Renders a Thymeleaf TEXT-mode template and calls the Claude API (Anthropic Java SDK) to produce a narration with `<em>...</em>` emphasis tags and a headline on the first line
3. **TtsGenerator** — Splits headline from body, calls Google Cloud TTS twice (female voice for headline, male voice for body), uses `ffmpeg` to concatenate with a 500ms pause → MP3

External CLI tools required at runtime: `yt-dlp`, `whisper`, `ffmpeg`.

### Key Domain Objects

- **`Config`** — Immutable, built once per application instance. Holds API keys, output dir, narrator model, whisper CPU %, and optional custom prompt file path.
- **`MediaOptimizationJob`** — Per-job parameters. `compressionFactor` (default 10) and `minimumNarrationLengthSeconds` (default 120) together determine target word count: `max(transcriptWords / compressionFactor, wordsForMinimumSeconds)`.
- **`MediaOptimizationResult`** — Record with `videoId`, `narrationFile` (text), `audioFile` (MP3).

### Prompt Resolution Order

1. `job.promptOverride()` — full replacement
2. `job.promptOverride()` absent → `config.narrationPromptFile()` external file
3. Fallback: `src/main/resources/prompts/narration-default.txt` (Thymeleaf template)

Template variables: `${wordTarget}`, `${transcript}`, `${promptExtra}`.

### TTS Voice Format

The narration output must have the headline on the **first line**, followed by a **blank line**, then the body. `TtsGenerator` splits on this convention. `<em>...</em>` tags in the body become SSML `<prosody>` emphasis (slow + loud).
