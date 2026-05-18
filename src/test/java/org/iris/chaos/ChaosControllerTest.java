package org.iris.chaos;

import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChaosController} — verifies the HTTP-status-code
 * mapping for every failure shape the service can surface.
 *
 * <p>Does NOT boot a Spring context. The {@code @PreAuthorize("hasRole('ADMIN')")}
 * annotation is enforced by Spring AOP at runtime; exercising that path
 * requires a full {@code @SpringBootTest} which is covered elsewhere via
 * the existing integration-test infrastructure (e.g. CustomerApiITest,
 * Auth0JwtValidationITest). This test focuses on the controller's
 * exception-to-HTTP translation logic which pure unit tests cover
 * cheaply and reliably.
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class ChaosControllerTest {

    @Mock
    private ChaosService chaosService;

    private ChaosController controller;

    @BeforeEach
    void setUp() {
        controller = new ChaosController(chaosService);
    }

    @Test
    void catalog_returnsAllExperimentsWithSlugKindDuration() {
        ResponseEntity<Map<String, Object>> response = controller.catalog();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, String>> experiments = (List<Map<String, String>>)
                response.getBody().get("experiments");

        // Catalogue must expose the 3 slug/kind/duration triples that the
        // UI relies on to build the infra-chaos action buttons.
        assertThat(experiments).hasSize(3);
        assertThat(experiments).extracting("slug")
                .containsExactlyInAnyOrder("pod-kill", "network-delay", "cpu-stress");
        assertThat(experiments).allSatisfy(e -> {
            assertThat(e).containsKeys("slug", "kind", "duration");
        });
    }

    @Test
    void trigger_validExperiment_returns200WithCrName() {
        when(chaosService.trigger(ChaosExperiment.POD_KILL))
                .thenReturn("iris-pod-kill-1776780000000");

        ResponseEntity<Map<String, Object>> response = controller.trigger("pod-kill");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body)
                .containsEntry("experiment", "pod-kill")
                .containsEntry("kind", "PodChaos")
                .containsEntry("customResourceName", "iris-pod-kill-1776780000000")
                .containsEntry("duration", "30s")
                .containsEntry("status", "triggered");
    }

    @Test
    void trigger_unknownSlug_returns400WithActionableMessage() {
        // ChaosExperiment.fromSlug() throws IllegalArgumentException for
        // bogus slugs — the controller catches + maps to 400 with the
        // message so the caller sees the list of valid slugs.
        ResponseEntity<Map<String, Object>> response = controller.trigger("reboot-cluster");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        String errorMsg = (String) response.getBody().get("error");
        assertThat(errorMsg)
                .contains("reboot-cluster")
                .contains("pod-kill")
                .contains("network-delay")
                .contains("cpu-stress");
    }

    @Test
    void trigger_chaosMeshCrdMissing_returns503WithActionableMessage() {
        // Service translates 404-on-create to IllegalStateException — the
        // controller maps that to 503 Service Unavailable (not 500)
        // because the fix is environmental, not a code bug.
        doThrow(new IllegalStateException(
                "Chaos Mesh CRDs not installed. Run `bin/cluster/demo/up.sh` (full mode) or install Chaos Mesh manually."))
                .when(chaosService).trigger(any());

        ResponseEntity<Map<String, Object>> response = controller.trigger("pod-kill");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        String errorMsg = (String) response.getBody().get("error");
        assertThat(errorMsg)
                .contains("Chaos Mesh CRDs not installed")
                .contains("bin/cluster/demo/up.sh");
    }

    @Test
    void trigger_kubernetesApiGenericFailure_returns500() {
        // Any non-404 Kubernetes error (RBAC denied, conflict, auth missing,
        // etc.) surfaces as 500 — the caller can read the API message in
        // the body + the numeric HTTP code the server returned.
        KubernetesClientException kubeEx = new KubernetesClientException(
                "forbidden: User system:serviceaccount:app:iris-backend cannot create chaos-mesh.org podchaos",
                HttpStatus.FORBIDDEN.value(), null);
        doThrow(kubeEx).when(chaosService).trigger(any());

        ResponseEntity<Map<String, Object>> response = controller.trigger("pod-kill");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((String) body.get("error")).contains("Kubernetes API error");
        assertThat(body).containsEntry("code", HttpStatus.FORBIDDEN.value());
    }
}
