package org.iris.mcp.actuator;

import org.iris.mcp.dto.EnvSnapshot;
import org.iris.mcp.dto.HealthSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ActuatorService} — focus on the env-redaction
 * contract + the info passthrough. Health-tree mapping is covered with
 * a minimal fake descriptor so the test does not need the full SB4
 * actuator wiring.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class ActuatorServiceTest {

    private HealthEndpoint healthEndpoint;
    private InfoEndpoint infoEndpoint;
    private EnvironmentSnapshotProvider envProvider;
    private ActuatorService service;

    @BeforeEach
    void setUp() {
        healthEndpoint = mock(HealthEndpoint.class);
        infoEndpoint = mock(InfoEndpoint.class);
        envProvider = mock(EnvironmentSnapshotProvider.class);
        service = new ActuatorService(healthEndpoint, infoEndpoint, envProvider);
    }

    @Test
    void envRedactsSecretsByPropertyName() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("spring.datasource.url", "jdbc:postgresql://localhost/demo");
        raw.put("spring.datasource.password", "shouldNotLeak");
        raw.put("auth0.client.secret", "topSecret");
        raw.put("custom.api.token", "abc123");
        raw.put("private.key.pem", "-----BEGIN-----");
        raw.put("any.credential.expiry", "1d");
        when(envProvider.snapshot(null)).thenReturn(raw);

        EnvSnapshot snap = service.getEnv(null);
        assertThat(snap.properties()).containsEntry("spring.datasource.url", "jdbc:postgresql://localhost/demo");
        assertThat(snap.properties()).containsEntry("spring.datasource.password", "***");
        assertThat(snap.properties()).containsEntry("auth0.client.secret", "***");
        assertThat(snap.properties()).containsEntry("custom.api.token", "***");
        assertThat(snap.properties()).containsEntry("private.key.pem", "***");
        assertThat(snap.properties()).containsEntry("any.credential.expiry", "***");
    }

    @Test
    void envCapsAt200Entries() {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < 250; i++) {
            raw.put("prop." + i, "v" + i);
        }
        when(envProvider.snapshot("prop.")).thenReturn(raw);

        EnvSnapshot snap = service.getEnv("prop.");
        assertThat(snap.properties()).hasSize(ActuatorService.MAX_ENV_PROPERTIES);
    }

    @Test
    void infoReturnsImmutableCopyOfContributors() {
        Map<String, Object> raw = Map.of("git", Map.of("branch", "main"), "build", Map.of("version", "0.1.0"));
        when(infoEndpoint.info()).thenReturn(raw);

        Map<String, Object> info = service.getInfo();
        assertThat(info).hasSize(2);
        assertThat(info).containsKeys("git", "build");
    }

    // Removed `infoNullReturnsEmptyMap` 2026-05-14 along with the dead
    // null-check in ActuatorService#getInfo (Sonar S2583). Spring Boot's
    // InfoEndpoint#info() never returns null per its API contract ; the
    // test was exercising unreachable defensive code.

    @Test
    void caseInsensitiveSecretMatch() {
        // Verify the regex pattern is genuinely case-insensitive.
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("MY.PASSWORD").matches()).isTrue();
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("My.Token").matches()).isTrue();
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("API_KEY_FOO").matches()).isTrue();
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("any.credential").matches()).isTrue();
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("benign.url").matches()).isFalse();
    }

    // ─── production constructor (ObjectProvider path) ─────────────────────────

    @Test
    void productionConstructor_withAvailableEndpoints_resolvesToBeans() {
        @SuppressWarnings("unchecked")
        ObjectProvider<HealthEndpoint> healthProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<InfoEndpoint> infoProvider = mock(ObjectProvider.class);
        when(healthProvider.getIfAvailable()).thenReturn(healthEndpoint);
        when(infoProvider.getIfAvailable()).thenReturn(infoEndpoint);

        // No NPE on construction → the wiring works.
        ActuatorService prod = new ActuatorService(healthProvider, infoProvider, envProvider);

        // getInfo flows through the resolved infoEndpoint
        when(infoEndpoint.info()).thenReturn(Map.of("git", Map.of("sha", "abc123")));
        assertThat(prod.getInfo()).containsKey("git");
    }

    @Test
    void productionConstructor_withMissingEndpoints_resolvesToNullsAndDegradesGracefully() {
        // Pinned : when management.endpoints.web.exposure.include excludes
        // health/info, ObjectProvider.getIfAvailable() returns null. The
        // service must still load — calls return UNKNOWN / empty map.
        @SuppressWarnings("unchecked")
        ObjectProvider<HealthEndpoint> healthProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<InfoEndpoint> infoProvider = mock(ObjectProvider.class);
        when(healthProvider.getIfAvailable()).thenReturn(null);
        when(infoProvider.getIfAvailable()).thenReturn(null);

        ActuatorService prod = new ActuatorService(healthProvider, infoProvider, envProvider);

        assertThat(prod.getHealth().status()).isEqualTo("UNKNOWN");
        assertThat(prod.getHealth().components()).isEmpty();
        assertThat(prod.getHealthDetail().status()).isEqualTo("UNKNOWN");
        assertThat(prod.getInfo()).isEmpty();
    }

    // ─── getHealth() / getHealthDetail() ─────────────────────────────────────

    @Test
    void getHealth_compositeDescriptor_walksTreeWithoutDetails() {
        // Build a composite UP with two leaf children, one with details
        // (db) and one without (kafka). Since withDetails=false, leaves
        // expose status only.
        IndicatedHealthDescriptor dbLeaf = mock(IndicatedHealthDescriptor.class);
        when(dbLeaf.getStatus()).thenReturn(Status.UP);
        when(dbLeaf.getDetails()).thenReturn(Map.of("validationQuery", "SELECT 1"));

        IndicatedHealthDescriptor kafkaLeaf = mock(IndicatedHealthDescriptor.class);
        when(kafkaLeaf.getStatus()).thenReturn(Status.DOWN);

        CompositeHealthDescriptor root = mock(CompositeHealthDescriptor.class);
        when(root.getStatus()).thenReturn(Status.DOWN);
        Map<String, HealthDescriptor> components = new LinkedHashMap<>();
        components.put("db", dbLeaf);
        components.put("kafka", kafkaLeaf);
        when(root.getComponents()).thenReturn(components);

        when(healthEndpoint.health()).thenReturn(root);

        HealthSnapshot snap = service.getHealth();

        assertThat(snap.status()).isEqualTo("DOWN");
        assertThat(snap.components()).containsKeys("db", "kafka");
        // No details when withDetails=false
        assertThat(snap.components().get("db").details()).isEmpty();
        assertThat(snap.components().get("db").status()).isEqualTo("UP");
        assertThat(snap.components().get("kafka").status()).isEqualTo("DOWN");
    }

    @Test
    void getHealth_leafDescriptor_returnsStatusOnly() {
        // Edge case : the root of the descriptor tree IS a leaf (no
        // children). The components map should be empty and the top-level
        // status carries the answer.
        IndicatedHealthDescriptor leaf = mock(IndicatedHealthDescriptor.class);
        when(leaf.getStatus()).thenReturn(Status.UP);
        when(healthEndpoint.health()).thenReturn(leaf);

        HealthSnapshot snap = service.getHealth();

        assertThat(snap.status()).isEqualTo("UP");
        assertThat(snap.components()).isEmpty();
    }

    @Test
    void getHealthDetail_indicatedLeaves_carryOverDetails() {
        // withDetails=true → IndicatedHealthDescriptor leaves expose
        // their getDetails() map. Mirror the production path that surfaces
        // db validation queries / kafka broker info.
        IndicatedHealthDescriptor dbLeaf = mock(IndicatedHealthDescriptor.class);
        when(dbLeaf.getStatus()).thenReturn(Status.UP);
        when(dbLeaf.getDetails()).thenReturn(Map.of(
                "database", "PostgreSQL",
                "validationQuery", "SELECT 1"));

        CompositeHealthDescriptor root = mock(CompositeHealthDescriptor.class);
        when(root.getStatus()).thenReturn(Status.UP);
        when(root.getComponents()).thenReturn(Map.of("db", dbLeaf));

        when(healthEndpoint.health()).thenReturn(root);

        HealthSnapshot snap = service.getHealthDetail();

        assertThat(snap.components().get("db").details())
                .containsEntry("database", "PostgreSQL")
                .containsEntry("validationQuery", "SELECT 1");
    }

    @Test
    void getHealthDetail_indicatedLeafWithNullDetails_yieldsEmptyDetails() {
        // Pinned : a leaf indicator may publish null details (most do
        // when show-details=never propagates). The adapter must NOT NPE
        // — it falls through to the empty map.
        IndicatedHealthDescriptor leaf = mock(IndicatedHealthDescriptor.class);
        when(leaf.getStatus()).thenReturn(Status.UP);
        when(leaf.getDetails()).thenReturn(null);

        CompositeHealthDescriptor root = mock(CompositeHealthDescriptor.class);
        when(root.getStatus()).thenReturn(Status.UP);
        when(root.getComponents()).thenReturn(Map.of("redis", leaf));

        when(healthEndpoint.health()).thenReturn(root);

        HealthSnapshot snap = service.getHealthDetail();

        assertThat(snap.components().get("redis").details()).isEmpty();
    }

    @Test
    void getHealth_endpointNullViaTestCtor_returnsUnknownAndEmptyComponents() {
        // The "test-only" constructor branch where healthEndpoint is null
        // (e.g. legacy unit tests passing nulls). Pinned because it's a
        // direct guard not covered by the production path.
        ActuatorService nullSvc = new ActuatorService((HealthEndpoint) null, infoEndpoint, envProvider);

        HealthSnapshot snap = nullSvc.getHealth();
        assertThat(snap.status()).isEqualTo("UNKNOWN");
        assertThat(snap.components()).isEmpty();

        HealthSnapshot detail = nullSvc.getHealthDetail();
        assertThat(detail.status()).isEqualTo("UNKNOWN");
    }
}
