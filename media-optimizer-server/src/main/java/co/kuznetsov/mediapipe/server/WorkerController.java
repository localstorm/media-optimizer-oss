package co.kuznetsov.mediapipe.server;

import co.kuznetsov.medialib.index.MediaIndex;
import co.kuznetsov.medialib.util.HealthStatus;
import co.kuznetsov.mediapipe.model.OptimizerConfig;
import co.kuznetsov.mediapipe.worker.ConfigHolder;
import co.kuznetsov.mediapipe.worker.WorkerEngine;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WorkerController {

    private final ConfigHolder configHolder;
    private final MediaIndex mediaIndex;
    private final WorkerEngine workerEngine;

    public WorkerController(ConfigHolder configHolder, MediaIndex mediaIndex, WorkerEngine workerEngine) {
        this.configHolder = configHolder;
        this.mediaIndex = mediaIndex;
        this.workerEngine = workerEngine;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean mediaFeedHealthy = mediaIndex.isMediaFeedHealthy();
        HealthStatus healthStatus = workerEngine.healthStatus();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("MediaFeed", mediaFeedHealthy ? "UP" : "DOWN");
        for (Map.Entry<String, HealthStatus.ComponentStatus> entry : healthStatus.snapshot().entrySet()) {
            HealthStatus.ComponentStatus cs = entry.getValue();
            String value = cs.isUp() ? "UP" : "DOWN: " + cs.detail();
            body.put(entry.getKey(), value);
        }
        boolean allHealthy = mediaFeedHealthy && healthStatus.isHealthy();
        body.put("status", allHealthy ? "UP" : "DOWN");
        HttpStatus status = allHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/config")
    public OptimizerConfig config() {
        return configHolder.get();
    }

    @GetMapping("/config/refreshed-at")
    public Map<String, Instant> configRefreshedAt() {
        return Map.of("refreshedAt", configHolder.getLastRefreshedAt());
    }
}
