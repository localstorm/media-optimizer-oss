package co.kuznetsov.medialib.util;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of named component health states.
 *
 * <p>Components start healthy (absent from the map). Callers mark a component
 * DOWN when an error occurs and UP again when it recovers. The overall status
 * is DOWN if any component is DOWN.
 */
public final class HealthStatus {

    public enum State { UP, DOWN }

    public record ComponentStatus(State state, String detail, Instant since) {
        public boolean isUp() {
            return state == State.UP;
        }
    }

    private final ConcurrentHashMap<String, ComponentStatus> components = new ConcurrentHashMap<>();

    public void markDown(String component, String detail) {
        components.put(component, new ComponentStatus(State.DOWN, detail, Instant.now()));
    }

    public void markUp(String component) {
        components.put(component, new ComponentStatus(State.UP, null, Instant.now()));
    }

    public boolean isHealthy() {
        return components.values().stream().allMatch(ComponentStatus::isUp);
    }

    public Map<String, ComponentStatus> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }
}
