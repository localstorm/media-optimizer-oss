package co.kuznetsov.mediapipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YTMediaOptimizationPipelineTest {

    @Test
    void extractVideoId_watchUrl() {
        String id = YTMediaOptimizationPipeline.extractVideoId("https://www.youtube.com/watch?v=eGSsoSEppNU");
        assertEquals("eGSsoSEppNU", id);
    }

    @Test
    void extractVideoId_watchUrlWithExtraParams() {
        String id = YTMediaOptimizationPipeline.extractVideoId("https://www.youtube.com/watch?v=eGSsoSEppNU&t=42s");
        assertEquals("eGSsoSEppNU", id);
    }

    @Test
    void extractVideoId_shortUrl() {
        String id = YTMediaOptimizationPipeline.extractVideoId("https://youtu.be/eGSsoSEppNU");
        assertEquals("eGSsoSEppNU", id);
    }

    @Test
    void extractVideoId_shortUrlWithParams() {
        String id = YTMediaOptimizationPipeline.extractVideoId("https://youtu.be/eGSsoSEppNU?t=42");
        assertEquals("eGSsoSEppNU", id);
    }

    @Test
    void extractVideoId_invalidUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> YTMediaOptimizationPipeline.extractVideoId("https://example.com/not-a-video"));
    }
}
