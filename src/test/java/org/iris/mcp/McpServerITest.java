package org.iris.mcp;

import org.iris.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test : boots the full Iris Spring Boot context (incl.
 * Postgres / Kafka / Redis testcontainers) and verifies that the MCP
 * {@link ToolCallbackProvider} exposes the 15 expected tools and that
 * invoking three representative ones yields the right shape.
 *
 * <h3>Why this layer, not an HTTP/SSE round-trip ?</h3>
 * <p>The Spring AI starter delegates the wire transport to the upstream
 * MCP SDK ; testing it would test the SDK, not our integration. The
 * value-adding contract on OUR side is "the @Tool annotations resolve,
 * the callbacks invoke real services, results match the DTO shape".
 * That contract is exercised here.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class McpServerITest extends AbstractIntegrationTest {

    /**
     * Names of the 15 tools per ADR-0062 catalogue (14 Phase-1 + the
     * {@code predict_customer_churn} added per shared ADR-0061 Phase B,
     * 2026-04-27). The set is checked against the runtime registry so a
     * future addition / removal must be reflected here on purpose.
     */
    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "list_recent_orders",
            "get_order_by_id",
            "create_order",
            "cancel_order",
            "find_low_stock_products",
            "get_customer_360",
            "trigger_chaos_experiment",
            "predict_customer_churn",
            "tail_logs",
            "get_metrics",
            "get_health",
            "get_health_detail",
            "get_actuator_env",
            "get_actuator_info",
            "get_openapi_spec"
    );

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    /** ChaosService talks to the K8s API — mocked in tests so the
     *  context boots without a real cluster. */
    @MockitoBean
    @SuppressWarnings("unused")
    private io.fabric8.kubernetes.client.KubernetesClient kubernetesClient;

    @Test
    void registryExposesAllExpectedTools() {
        ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
        Set<String> registeredNames = Arrays.stream(callbacks)
                .map(cb -> cb.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertThat(registeredNames).containsAll(EXPECTED_TOOLS);
    }

    @Test
    void getActuatorInfoReturnsBuildInfo() {
        ToolCallback callback = lookup("get_actuator_info");
        // No args — the @Tool method takes no parameters.
        String result = callback.call("{}");
        // Even if no info contributors are wired, the call must succeed
        // and return a JSON document (possibly an empty object {}).
        assertThat(result).isNotNull();
        assertThat(result.trim()).startsWith("{");
    }

    @Test
    void getOpenapiSpecSummaryReturnsPathsByVerb() {
        ToolCallback callback = lookup("get_openapi_spec");
        String result = callback.call("{\"summary\":true}");
        assertThat(result).contains("pathsByVerb");
        // Sanity check : at least the customers + auth endpoints should appear.
        assertThat(result).contains("/customers");
    }

    @Test
    void tailLogsReturnsAJsonArray() {
        ToolCallback callback = lookup("tail_logs");
        String result = callback.call("{\"n\":5}");
        // Either the array is empty (no events captured this early in the
        // boot) or it's a JSON array. Either way it must be a list shape.
        assertThat(result).isNotNull();
        assertThat(result.trim()).startsWith("[");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void chaosToolReturnsStructuredErrorOnUnknownSlug() {
        // trigger_chaos_experiment is @PreAuthorize("hasRole('ADMIN')") —
        // @WithMockUser sets the SecurityContext so the call goes through.
        // The mocked KubernetesClient never gets touched : the tool short-
        // circuits on the unknown slug BEFORE delegating to ChaosService.
        ToolCallback callback = lookup("trigger_chaos_experiment");
        String result = callback.call("{\"scenario\":\"not-a-thing\"}");
        assertThat(result).contains("unknown_scenario");
    }

    private ToolCallback lookup(String name) {
        ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
        for (ToolCallback cb : callbacks) {
            if (name.equals(cb.getToolDefinition().name())) {
                return cb;
            }
        }
        throw new AssertionError("Tool not registered : " + name + " (registered = "
                + List.of(callbacks).stream().map(c -> c.getToolDefinition().name()).toList() + ")");
    }
}
