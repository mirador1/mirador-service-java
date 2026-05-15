package org.iris.mcp.actuator;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EnvironmentSnapshotProvider}. Pure JVM ; uses
 * a real {@link StandardEnvironment} populated with {@link MapPropertySource}s
 * so we exercise the same hierarchy traversal Spring would do, without
 * bootstrapping any beans.
 *
 * <p>Pinned invariants :
 * <ul>
 *   <li>null / blank prefix → no filter (all keys returned).</li>
 *   <li>prefix filter is a {@code startsWith} match (substring would
 *       leak {@code spring.} keys when the user asked for {@code iris.}).</li>
 *   <li>Earlier property sources win on duplicate keys (matches Spring's
 *       resolution order — {@code putIfAbsent}).</li>
 *   <li>Non-enumerable sources are skipped silently.</li>
 * </ul>
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class EnvironmentSnapshotProviderTest {

    private static StandardEnvironment env(PropertySource<?>... sources) {
        StandardEnvironment env = new StandardEnvironment();
        // Drop the auto-attached systemProperties / systemEnvironment to
        // keep tests deterministic — those vary per host.
        MutablePropertySources msps = env.getPropertySources();
        for (var name : msps.stream().map(PropertySource::getName).toList()) {
            msps.remove(name);
        }
        for (PropertySource<?> s : sources) {
            msps.addLast(s);
        }
        return env;
    }

    @Test
    void snapshot_nullPrefix_returnsAllKeys() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("spring.application.name", "iris");
        data.put("iris.feature.enabled", "true");
        EnvironmentSnapshotProvider provider = new EnvironmentSnapshotProvider(
                env(new MapPropertySource("test", data)));

        Map<String, Object> result = provider.snapshot(null);

        assertThat(result).containsEntry("spring.application.name", "iris");
        assertThat(result).containsEntry("iris.feature.enabled", "true");
        assertThat(result).hasSize(2);
    }

    @Test
    void snapshot_blankPrefix_returnsAllKeys() {
        Map<String, Object> data = Map.of("foo", "bar");
        EnvironmentSnapshotProvider provider = new EnvironmentSnapshotProvider(
                env(new MapPropertySource("test", data)));

        assertThat(provider.snapshot("")).containsEntry("foo", "bar");
        assertThat(provider.snapshot("   ")).containsEntry("foo", "bar");
    }

    @Test
    void snapshot_withPrefix_filtersKeys() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("spring.application.name", "iris");
        data.put("iris.feature.enabled", "true");
        data.put("server.port", 8080);
        EnvironmentSnapshotProvider provider = new EnvironmentSnapshotProvider(
                env(new MapPropertySource("test", data)));

        Map<String, Object> result = provider.snapshot("spring.");

        assertThat(result).containsOnlyKeys("spring.application.name");
    }

    @Test
    void snapshot_emptyEnvironment_returnsEmptyMap() {
        EnvironmentSnapshotProvider provider = new EnvironmentSnapshotProvider(env());
        assertThat(provider.snapshot(null)).isEmpty();
        assertThat(provider.snapshot("anything.")).isEmpty();
    }

    @Test
    void snapshot_duplicateKeyAcrossSources_firstSourceWins() {
        // Pinned : MutablePropertySources iterates in declaration order, and
        // putIfAbsent means the FIRST source's value persists. This mirrors
        // Spring's resolution order so the snapshot agrees with what
        // env.getProperty(name) returns at runtime.
        Map<String, Object> highPriority = Map.of("shared.key", "from-high");
        Map<String, Object> lowPriority = Map.of("shared.key", "from-low");
        EnvironmentSnapshotProvider provider = new EnvironmentSnapshotProvider(
                env(new MapPropertySource("high", highPriority),
                    new MapPropertySource("low", lowPriority)));

        Map<String, Object> result = provider.snapshot(null);

        assertThat(result).containsEntry("shared.key", "from-high");
    }

    @Test
    void snapshot_skipsNonEnumerablePropertySources() {
        // A custom non-enumerable source (e.g. a JNDI lookup wrapper) should
        // be silently ignored — we can't iterate its keys without potentially
        // triggering side-effects per key, so the contract is "skip".
        PropertySource<Object> opaque = new PropertySource<>("opaque") {
            @Override public Object getProperty(String name) { return null; }
        };
        EnvironmentSnapshotProvider provider = new EnvironmentSnapshotProvider(
                env(opaque,
                    new MapPropertySource("real", Map.of("real.key", "value"))));

        Map<String, Object> result = provider.snapshot(null);

        assertThat(result).containsOnlyKeys("real.key");
    }
}
