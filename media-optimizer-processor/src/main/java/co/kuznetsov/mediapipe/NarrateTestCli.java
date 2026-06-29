package co.kuznetsov.mediapipe;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI tool for testing Claude narration integration.
 *
 * <p>Usage:
 * <pre>
 *   java -cp tts-test.jar co.kuznetsov.mediapipe.NarrateTestCli \
 *     --anthropic-key &lt;key&gt; --file &lt;transcript.txt&gt; [--out &lt;output.txt&gt;]
 * </pre>
 */
public final class NarrateTestCli {

    private NarrateTestCli() {
    }

    /**
     * Main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) throws Exception {
        String anthropicKey = null;
        String file = null;
        String out = null;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--anthropic-key": anthropicKey = args[++i]; break;
                case "--file": file = args[++i]; break;
                case "--out": out = args[++i]; break;
                default: break;
            }
        }

        if (anthropicKey == null || file == null) {
            System.err.println(
                "Usage: NarrateTestCli --anthropic-key <key> --file <transcript.txt> [--out <output.txt>]");
            System.exit(1);
        }

        String transcript = Files.readString(Path.of(file));

        Config config = Config.builder()
            .anthropicApiKey(anthropicKey)
            .googleApiKey("unused")
            .build();

        MediaOptimizationJob job = MediaOptimizationJob.builder("https://youtube.com/test")
            .promptOverride(DEFAULT_PROMPT)
            .compressionFactor(10)
            .minimumNarrationLengthSeconds(60)
            .build();

        Narrator narrator = new Narrator(config);
        System.out.println("Sending to Claude...");
        String narration = narrator.narrate(transcript, job);
        System.out.println("--- NARRATION ---");
        System.out.println(narration);
        System.out.println("--- END ---");

        if (out != null) {
            Files.writeString(Path.of(out), narration);
            System.out.println("Written to: " + out);
        }
    }

    private static final String DEFAULT_PROMPT =
        "You are summarizing a YouTube video transcript into a narration for a personal podcast feed.\n"
        + "\n"
        + "Start with a single headline on its own line — punchy, specific, no more than 10 words."
        + " Then a blank line, then the narration. No other headers.\n"
        + "\n"
        + "Write the narration in plain prose, no bullet points."
        + " Write it like you are telling a friend what you just watched — casual but accurate.\n"
        + "\n"
        + "Assume the listener has a software engineering background."
        + " Do not explain basic concepts."
        + " Focus on what is novel, clever, or alarming about this specific story.\n"
        + "\n"
        + "This is being converted to audio."
        + " Remove any references to the source video itself.\n"
        + "\n"
        + "Wrap key lessons and actionable ideas in <em>...</em> tags — one per paragraph maximum."
        + " These will receive audio emphasis.\n"
        + "\n"
        + "Target length: [(${wordTarget})] words.\n"
        + "\n"
        + "Transcript:\n"
        + "[(${transcript})]";
}
