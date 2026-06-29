package co.kuznetsov.mediapipe;

import co.kuznetsov.medialib.util.HealthStatus;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI tool for testing Google TTS integration.
 *
 * <p>Usage:
 * <pre>
 *   java -jar tts-test.jar --key &lt;api-key&gt; --out &lt;output.mp3&gt; [--text &lt;text&gt;]
 * </pre>
 *
 * <p>If {@code --text} is omitted a built-in sample narration is used,
 * which exercises both headline/body splitting and &lt;em&gt; emphasis tags.
 */
public final class TtsTestCli {

    private static final String SAMPLE_NARRATION =
        "Google TTS is working correctly\n"
        + "\n"
        + "This is a test of the text to speech pipeline. "
        + "The audio should sound natural and clear. "
        + "<em>This sentence should be spoken with emphasis.</em> "
        + "And this one should return to normal volume and pace.";

    private TtsTestCli() {
    }

    /**
     * Main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) throws Exception {
        String key = null;
        String text = null;
        String file = null;
        String out = null;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--key": key = args[++i]; break;
                case "--text": text = args[++i]; break;
                case "--file": file = args[++i]; break;
                case "--out": out = args[++i]; break;
                default: break;
            }
        }

        if (key == null || out == null) {
            System.err.println("Usage: tts-test.jar --key <api-key> --out <output.mp3> [--text <text>|--file <input.txt>]");
            System.exit(1);
        }

        String narration;
        if (text != null) {
            narration = text;
        } else if (file != null) {
            narration = Files.readString(Path.of(file));
        } else {
            narration = SAMPLE_NARRATION;
        }

        Config config = Config.builder()
            .anthropicApiKey("unused")
            .googleApiKey(key)
            .build();

        TtsGenerator generator = new TtsGenerator(config, new HealthStatus());

        System.out.println("Synthesizing...");
        generator.generate(narration, Path.of(out));
        System.out.println("Done: " + out);
    }
}
