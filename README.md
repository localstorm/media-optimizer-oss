# media-optimizer

Java application for converting YouTube videos (and Telegram channels) into narrated audio summaries delivered as a personal podcast feed.

**Pipeline:** YouTube URL → transcript → Claude narration → Google TTS MP3

## How it works

1. **Transcribe** — downloads audio via `yt-dlp` and runs local Whisper (`tiny` model). Falls back to YouTube auto-captions if Whisper fails.
2. **Narrate** — sends the transcript to Claude via the Anthropic API. Produces a headline + prose narration with `<em>` tags marking key insights for audio emphasis.
3. **Synthesize** — renders MP3 via Google Cloud TTS. Headline uses a female voice; body uses a male voice, separated by a short pause. `<em>` tags become SSML prosody emphasis.

## Project structure

This is a multi-module Maven project:

| Module | Purpose |
|---|---|
| `media-optimizer-processor` | Core pipeline library (transcriber, narrator, TTS) |
| `media-optimizer-server` | Spring Boot server — HTTP API + scheduled feed polling |
| `media-optimizer-worker` | Stub |
| `media-optimizer-dashboard` | Dashboard / CLI tooling |
| `media-optimizer-docker` | Docker build + run scripts |

## Build

### Prerequisites

- Java 21, Maven
- AWS CLI configured on the build machine with permissions to read the Secrets Manager secret named in `AWS_COMBINED_SECRET_NAME`
- Docker (for containerized deployment)

### Maven build

```bash
# Full build (all modules)
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Single module
mvn -pl media-optimizer-processor clean package

# Run Checkstyle (validate phase)
mvn validate

# Run SpotBugs (verify phase)
mvn verify
```

### Docker build

All Docker tooling lives in `media-optimizer-docker/`.

```bash
cd media-optimizer-docker

# Build image and start container
bash run.sh

# Build image only
bash build.sh
```

`build.sh` does the following:
1. Runs the Maven build
2. Fetches AWS credentials from Secrets Manager (see `build.properties`)
3. Generates `credentials.sh` and bakes it into the image
4. Checks for an active `tdl` Telegram session; prompts QR login if missing
5. Builds the Docker image as `media-optimizer:latest`

`run.sh` calls `build.sh`, then stops any existing container and starts a fresh one with the host volume mounts defined in `build.properties`.

## build.properties

`media-optimizer-docker/build.properties` controls both the build and the container runtime. Copy and fill in before running:

```properties
# Container timezone
TIMEZONE=America/Toronto

# AWS region where Secrets Manager secrets live
AWS_REGION=us-east-2

# Secrets Manager secret name that holds all credentials (AWS keypair + API keys).
# The secret must be a JSON object — see the "Secrets used" section below for the expected keys.
# These credentials are baked into the Docker image as credentials.sh and used at runtime
# to fetch subscriptions.xml from S3, write processed results back, and call external APIs.
AWS_COMBINED_SECRET_NAME=media-optimizer-aws-user/aws-keypair

# S3 bucket and key where subscriptions.xml lives
OPTIMIZER_S3_BUCKET=your-s3-bucket-name
OPTIMIZER_S3_KEY=subscriptions.xml

# Host paths mounted into the container as volumes
# /tempspace — working directory for audio downloads and intermediate files
# /models    — Whisper model cache (avoids re-downloading on container restart)
HOST_TEMPSPACE_DIR=/tmp/media-optimizer/tempspace
HOST_MODELS_DIR=/tmp/media-optimizer/models
```

### Secrets used

All secrets are stored in a single AWS Secrets Manager secret named by `AWS_COMBINED_SECRET_NAME`.

`build.sh` fetches this secret at image build time and bakes all values into the image as `credentials.sh` environment variable exports. At runtime the container sources this file before starting the application.

Expected secret JSON shape:
```json
{
  "AWS_ACCESS_KEY_ID": "AKIAIOSFODNN7EXAMPLE",
  "AWS_SECRET_ACCESS_KEY": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "ANTHROPIC_API_KEY": "sk-ant-...",
  "GOOGLE_TTS_API_KEY": "...",
  "YOUTUBE_API_KEY": "...",
  "TELEGRAM_API_CREDENTIALS": "api_id:api_hash"
}
```

| Key | Purpose |
|---|---|
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | IAM keypair — S3 read/write for `OPTIMIZER_S3_BUCKET` |
| `ANTHROPIC_API_KEY` | Claude API — narration |
| `GOOGLE_TTS_API_KEY` | Google Cloud TTS — audio synthesis |
| `YOUTUBE_API_KEY` | YouTube Data API — channel polling |
| `TELEGRAM_API_CREDENTIALS` | Telegram client credentials in `api_id:api_hash` format — obtained from https://my.telegram.org/apps |

> **Warning:** Because all credentials are baked into the image at build time, **do not push this image to any public or shared registry.** Treat the image as a private artifact — run it locally or transfer it directly to the target host.

## subscriptions.xml format

`subscriptions.xml` is fetched from S3 at startup. It configures global settings, channel subscriptions, and named prompt fragments.

### Full example

```xml
<config>
  <settings>
    <compression_factor>10</compression_factor>
    <minimum_narration_length_seconds>120</minimum_narration_length_seconds>
    <whisper_model>tiny</whisper_model>
    <whisper_cpu_pct>50</whisper_cpu_pct>
    <feed_retention_days>4</feed_retention_days>
    <fetch_period_seconds>1800</fetch_period_seconds>
    <processing_period_seconds>119</processing_period_seconds>
    <retain_processed_seconds>172800</retain_processed_seconds>
    <narrator_model>claude-sonnet-4-6</narrator_model>
    <digest_compression_factor>2</digest_compression_factor>
    <morning_digest_time>06:30</morning_digest_time>
    <evening_digest_time>17:00</evening_digest_time>
    <timezone>America/Toronto</timezone>
  </settings>

  <subscriptions>

    <!-- Standard YouTube channel — individual episodes -->
    <subscription>
      <source_type>YOUTUBE</source_type>
      <channel_id>UC9x0AN7BWHpCDHSm9NiJFJQ</channel_id>
      <name>Network Chuck</name>
      <min_video_length_seconds>300</min_video_length_seconds>
      <compression_factor>10</compression_factor>
      <prompt_ref>default</prompt_ref>
      <prompt_extra_ref>author_chuck</prompt_extra_ref>
      <digest_mode>false</digest_mode>
    </subscription>

    <!-- Channel with digest mode — summaries batched and delivered once daily -->
    <subscription>
      <source_type>YOUTUBE</source_type>
      <channel_id>UCVkSF37pPXkZbElFjBwUsEA</channel_id>
      <name>The NewAtlas</name>
      <min_video_length_seconds>480</min_video_length_seconds>
      <compression_factor>5</compression_factor>
      <prompt_ref>default</prompt_ref>
      <prompt_extra_ref>author_berletic</prompt_extra_ref>
      <digest_mode>true</digest_mode>
      <digest_prompt_ref>digest</digest_prompt_ref>
      <digest_delivery_time>MORNING</digest_delivery_time>
    </subscription>

    <!-- Telegram channel with evening digest -->
    <subscription>
      <source_type>TELEGRAM</source_type>
      <channel_id>SomeTelegramChannel</channel_id>
      <name>My Telegram Feed</name>
      <compression_factor>1</compression_factor>
      <prompt_ref>default_telegram</prompt_ref>
      <digest_mode>true</digest_mode>
      <digest_prompt_ref>digest_telegram</digest_prompt_ref>
      <digest_delivery_time>EVENING</digest_delivery_time>
    </subscription>

    <!-- Virtual feed — aggregates multiple channels into one digest -->
    <subscription>
      <source_type>VIRTUAL</source_type>
      <channel_id>virtual:my-feed</channel_id>
      <name>My Aggregated Feed</name>
      <compression_factor>10</compression_factor>
      <prompt_ref>default</prompt_ref>
      <digest_mode>true</digest_mode>
      <digest_prompt_ref>digest</digest_prompt_ref>
      <digest_delivery_time>MORNING</digest_delivery_time>
    </subscription>

    <!-- Channel that feeds into a virtual feed instead of appearing directly -->
    <subscription>
      <source_type>YOUTUBE</source_type>
      <channel_id>UCsBjURrPoezykLs9EqgamOA</channel_id>
      <name>Some Channel</name>
      <min_video_length_seconds>300</min_video_length_seconds>
      <compression_factor>1</compression_factor>
      <prompt_ref>default</prompt_ref>
      <feeds_into>virtual:my-feed</feeds_into>
    </subscription>

    <!-- INBOX — special always-present feed for manually submitted URLs -->
    <subscription>
      <source_type>ANY</source_type>
      <channel_id>INBOX</channel_id>
      <name>INBOX</name>
      <min_video_length_seconds>0</min_video_length_seconds>
      <compression_factor>5</compression_factor>
      <prompt_ref>default</prompt_ref>
      <digest_mode>false</digest_mode>
      <pinned>true</pinned>
    </subscription>

  </subscriptions>

  <prompts>
    <!-- Named prompt fragments referenced by prompt_extra_ref -->
    <prompt name="author_chuck">The author of this material is Network Chuck. If you need to refer to presenter, use this name</prompt>

    <!-- Named base prompts referenced by prompt_ref -->
    <prompt name="default">... full prompt text ...</prompt>
    <prompt name="digest">... digest assembly prompt ...</prompt>
    <prompt name="default_telegram">... telegram post prompt ...</prompt>
    <prompt name="digest_telegram">... telegram digest prompt ...</prompt>
  </prompts>
</config>
```

### Subscription fields

**Source types:**

| `source_type` | Description |
|---|---|
| `YOUTUBE` | Polls a YouTube channel for new videos via the YouTube Data API. `channel_id` is the YouTube channel ID (e.g. `UC9x0AN7BWHpCDHSm9NiJFJQ`). |
| `TELEGRAM` | Reads posts from a Telegram channel via `tdl`. `channel_id` is the channel username (e.g. `RVvoenkor`). No polling — `tdl` exports recent messages on each cycle. |
| `VIRTUAL` | An aggregation feed with no source of its own. Collects narrations from other subscriptions that have `feeds_into` pointing at this feed's `channel_id`. `channel_id` must be `virtual:<name>`. |
| `ANY` | Accepts manually submitted URLs of any type. Used for the special `INBOX` feed. No automatic polling. |

**Subscription fields:**

| Field | Required | Description |
|---|---|---|
| `source_type` | yes | See above |
| `channel_id` | yes | YouTube channel ID, Telegram handle, `virtual:<name>` for virtual feeds, or `INBOX` for the inbox feed |
| `name` | yes | Display name in the dashboard |
| `min_video_length_seconds` | no | Skip videos shorter than this (YouTube only) |
| `compression_factor` | yes | Divide transcript word count by this to get target narration length (higher = shorter) |
| `prompt_ref` | yes | Name of the base prompt in `<prompts>` |
| `prompt_extra_ref` | no | Name of an extra prompt fragment appended to the base (e.g. author context) |
| `digest_mode` | no | If `true`, summaries are batched and delivered as a single digest |
| `digest_prompt_ref` | no | Prompt used when assembling the digest |
| `digest_delivery_time` | no | `MORNING` or `EVENING` — which scheduled slot to deliver the digest |
| `feeds_into` | no | `virtual:<name>` — route episodes into a virtual feed instead of directly to the subscriber |
| `pinned` | no | If `true`, this feed is always shown first in the dashboard |

### Settings fields

| Field | Description |
|---|---|
| `compression_factor` | Default compression factor (overridden per subscription) |
| `minimum_narration_length_seconds` | Minimum narration length — prevents very short outputs for short transcripts |
| `whisper_model` | Whisper model size: `tiny`, `base`, `small`, `medium`, `large` |
| `whisper_cpu_pct` | CPU % limit for Whisper to avoid saturating the host |
| `feed_retention_days` | How many days to keep episodes in the feed |
| `fetch_period_seconds` | How often to poll YouTube/Telegram for new content |
| `processing_period_seconds` | How often to process the fetch queue |
| `retain_processed_seconds` | How long to keep processed audio files on disk |
| `narrator_model` | Claude model ID for narration |
| `digest_compression_factor` | Compression factor used when assembling digests |
| `morning_digest_time` | Time to deliver MORNING digests (24h, e.g. `06:30`) |
| `evening_digest_time` | Time to deliver EVENING digests (24h, e.g. `17:00`) |
| `timezone` | Timezone for digest scheduling |

## Prompt customization

Prompts are [Thymeleaf](https://www.thymeleaf.org/) TEXT-mode templates stored inside `subscriptions.xml` under `<prompts>`.

**Available template variables:**

| Variable | Type | Description |
|---|---|---|
| `wordTarget` | `int` | Computed target word count: `max(transcriptWords / compressionFactor, wordsForMinimumSeconds)` |
| `transcript` | `String` | The transcript or post text passed to Claude |
| `promptExtra` | `String` | Content of the `prompt_extra_ref` named prompt; `null` if not set on the subscription |
| `channelName` | `String` | Display name of the subscription (from `<name>`) |
| `date` | `String` | Today's date formatted as `MMMM d, yyyy` (e.g. `April 25, 2026`) |
| `timeOfDay` | `String` | Digest delivery slot: `MORNING`, `EVENING`, or `ALL`; `null` for non-digest subscriptions |

Thymeleaf TEXT-mode syntax uses `[(${variable})]` for inline output and `[# th:if="..."]...[/]` for conditionals — not the HTML `${}` / `th:text` syntax.

**Resolution order per job:**
1. `job.promptOverride(String)` — full replacement (API / programmatic use)
2. `config.narrationPromptFile(Path)` — external template file
3. `prompt_ref` value from the subscription → matched `<prompt name="...">` in `<prompts>`

## Code standards

See [`SPEC/java-preferences.md`](SPEC/java-preferences.md) for coding conventions. Key points:

- No Lombok, no `volatile` (use `java.util.concurrent.atomic.*`)
- SLF4J for all logging
- Package prefix: `co.kuznetsov.mediapipe`
- Checkstyle: no star imports, no unused imports, max 500 lines per file, braces required
