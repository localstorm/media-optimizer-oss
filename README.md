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

# Secrets Manager secret name that holds ALL credentials (not just AWS keys — also
# Anthropic, Google TTS, YouTube, and Telegram). The secret must be a JSON object
# whose keys match the table in the "Secrets used" section below.
# These credentials are baked into the Docker image as credentials.sh and sourced at runtime.
AWS_COMBINED_SECRET_NAME=media-optimizer/all-credentials

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

See [`subscriptions.sample.xml`](subscriptions.sample.xml) for a complete working example. Condensed structure:

```xml
<?xml version="1.0"?>
<config>
  <settings>
    <compression_factor>10</compression_factor>
    <minimum_narration_length_seconds>120</minimum_narration_length_seconds>
    <whisper_model>tiny</whisper_model>
    <whisper_cpu_pct>50</whisper_cpu_pct>
    <feed_retention_days>2</feed_retention_days>
    <fetch_period_seconds>1800</fetch_period_seconds>
    <processing_period_seconds>119</processing_period_seconds>
    <retain_processed_seconds>172800</retain_processed_seconds>
    <narrator_model>claude-sonnet-4-6</narrator_model>
    <digest_compression_factor>2</digest_compression_factor>
    <morning_digest_time>07:00</morning_digest_time>
    <evening_digest_time>18:00</evening_digest_time>
    <timezone>America/New_York</timezone>
    <title_prompt_ref>default_title</title_prompt_ref>
    <default_video_submission_channel>INBOX</default_video_submission_channel>
  </settings>

  <subscriptions>

    <!-- RSS/Atom blog subscription — individual episodes -->
    <subscription>
      <source_type>RSS_ATOM</source_type>
      <channel_id>EXAMPLE_AUTHOR_1</channel_id>
      <name>Example Author 1</name>
      <compression_factor>2</compression_factor>
      <prompt_ref>default_publications</prompt_ref>
      <prompt_extra_ref>author_example1</prompt_extra_ref>
      <digest_mode>false</digest_mode>
    </subscription>

    <!-- Standard YouTube channel — individual episodes -->
    <subscription>
      <source_type>YOUTUBE</source_type>
      <channel_id>UCxxxxxxxxxxxxxxxxxxxxxxx</channel_id>
      <name>Example YouTube Channel</name>
      <min_video_length_seconds>300</min_video_length_seconds>
      <compression_factor>10</compression_factor>
      <prompt_ref>default</prompt_ref>
      <digest_mode>false</digest_mode>
    </subscription>

    <!-- Manual submission channel -->
    <subscription>
      <source_type>SUBMISSIONS</source_type>
      <channel_id>ExampleBlog</channel_id>
      <name>Example Blog</name>
      <compression_factor>2</compression_factor>
      <prompt_ref>default_publications</prompt_ref>
      <digest_mode>false</digest_mode>
    </subscription>

    <!-- Telegram channel with evening digest -->
    <subscription>
      <source_type>TELEGRAM</source_type>
      <channel_id>example_telegram_channel</channel_id>
      <name>Example Telegram Channel</name>
      <compression_factor>1</compression_factor>
      <prompt_ref>default_telegram</prompt_ref>
      <digest_mode>true</digest_mode>
      <digest_prompt_ref>digest_telegram</digest_prompt_ref>
      <digest_delivery_time>EVENING</digest_delivery_time>
    </subscription>

    <!-- Virtual feed — aggregates multiple channels into a morning digest -->
    <subscription>
      <source_type>VIRTUAL</source_type>
      <channel_id>virtual:example-digest</channel_id>
      <name>Example Digest</name>
      <compression_factor>20</compression_factor>
      <prompt_ref>default</prompt_ref>
      <digest_mode>true</digest_mode>
      <digest_prompt_ref>digest</digest_prompt_ref>
      <digest_delivery_time>MORNING</digest_delivery_time>
    </subscription>

    <!-- Channels that feed into the virtual digest above -->
    <subscription>
      <source_type>YOUTUBE</source_type>
      <channel_id>UCyyyyyyyyyyyyyyyyyyyyyyyy</channel_id>
      <name>Example Feed Channel 1</name>
      <min_video_length_seconds>300</min_video_length_seconds>
      <feeds_into>virtual:example-digest</feeds_into>
    </subscription>

    <!-- Built-in: pinned text inbox for pasting raw text -->
    <subscription>
      <source_type>SUBMISSIONS</source_type>
      <channel_id>TEXT_INBOX</channel_id>
      <name>TEXT INBOX</name>
      <compression_factor>5</compression_factor>
      <prompt_ref>default_publications</prompt_ref>
      <digest_mode>false</digest_mode>
      <pinned>true</pinned>
    </subscription>

    <!-- Built-in: general inbox for ad-hoc YouTube URLs or any source -->
    <subscription>
      <source_type>ANY</source_type>
      <channel_id>INBOX</channel_id>
      <name>INBOX</name>
      <min_video_length_seconds>0</min_video_length_seconds>
      <compression_factor>5</compression_factor>
      <prompt_ref>universal</prompt_ref>
      <digest_mode>false</digest_mode>
      <pinned>true</pinned>
    </subscription>

  </subscriptions>

  <!-- Maps RSS_ATOM channel_id values to their feed URLs -->
  <channelSources>
    <channelSource channelId="EXAMPLE_AUTHOR_1">https://example.com/author1/atom.xml</channelSource>
  </channelSources>

  <prompts>
    <!-- Named prompt fragments referenced by prompt_extra_ref -->
    <prompt name="author_example1">The author of this material is Example Author 1, a software engineer and blogger.</prompt>

    <!-- Named base prompts referenced by prompt_ref -->
    <prompt name="default_title">... title generation prompt ...</prompt>
    <prompt name="default">... YouTube narration prompt ...</prompt>
    <prompt name="universal">... universal narration prompt ...</prompt>
    <prompt name="default_publications">... newsletter/blog narration prompt ...</prompt>
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
| `YOUTUBE` | Polls a YouTube channel for new videos via the YouTube Data API. `channel_id` is the YouTube channel ID (e.g. `UCxxxxxxxxxxxxxxxxxxxxxxx`). |
| `RSS_ATOM` | Polls an RSS or Atom feed. `channel_id` is a logical name; the actual feed URL is declared in `<channelSources>`. |
| `TELEGRAM` | Reads posts from a Telegram channel via `tdl`. `channel_id` is the channel username. No polling — `tdl` exports recent messages on each cycle. |
| `SUBMISSIONS` | A manually-curated feed. No automatic polling; items are submitted via the API or dashboard. `channel_id` is a logical name (e.g. `ExampleBlog`). The reserved id `TEXT_INBOX` accepts raw pasted text. |
| `VIRTUAL` | An aggregation feed with no source of its own. Collects narrations from other subscriptions that have `feeds_into` pointing at this feed's `channel_id`. `channel_id` must be `virtual:<name>`. |
| `ANY` | Accepts manually submitted URLs of any type. Used for the special `INBOX` feed. No automatic polling. |

**Subscription fields:**

| Field | Required | Description |
|---|---|---|
| `source_type` | yes | See above |
| `channel_id` | yes | YouTube channel ID, logical name for RSS/SUBMISSIONS, Telegram handle, `virtual:<name>` for virtual feeds, `INBOX` / `TEXT_INBOX` for built-in inboxes |
| `name` | yes | Display name in the dashboard |
| `min_video_length_seconds` | no | Skip videos shorter than this (YouTube only) |
| `compression_factor` | no | Divide transcript word count by this to get target narration length (higher = shorter). Falls back to the global `<settings>` value if omitted. |
| `prompt_ref` | yes | Name of the base prompt in `<prompts>` |
| `prompt_extra_ref` | no | Name of an extra prompt fragment appended to the base (e.g. author context) |
| `digest_mode` | no | If `true`, summaries are batched and delivered as a single digest |
| `digest_prompt_ref` | no | Prompt used when assembling the digest |
| `digest_delivery_time` | no | `MORNING` or `EVENING` — which scheduled slot to deliver the digest |
| `feeds_into` | no | `virtual:<name>` — route episodes into a virtual feed instead of appearing as a standalone feed |
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
| `morning_digest_time` | Time to deliver MORNING digests (24h, e.g. `07:00`) |
| `evening_digest_time` | Time to deliver EVENING digests (24h, e.g. `18:00`) |
| `timezone` | Timezone for digest scheduling |
| `title_prompt_ref` | Name of the prompt in `<prompts>` used to generate episode titles |
| `default_video_submission_channel` | `channel_id` of the subscription that receives ad-hoc video URL submissions (defaults to `INBOX`) |

### channelSources

`<channelSources>` maps the logical `channel_id` values used by `RSS_ATOM` subscriptions to their actual feed URLs:

```xml
<channelSources>
  <channelSource channelId="EXAMPLE_AUTHOR_1">https://example.com/author1/atom.xml</channelSource>
</channelSources>
```

Each `channelSource` entry pairs a `channelId` attribute (matching the subscription's `<channel_id>`) with the feed URL as its text content.

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
