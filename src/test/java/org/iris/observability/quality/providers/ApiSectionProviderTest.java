package org.iris.observability.quality.providers;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApiSectionProvider} — walks Spring's
 * {@link RequestMappingHandlerMapping} to enumerate every registered REST
 * endpoint. We feed a hand-built handler map so the tests are independent
 * of which controllers happen to be on the classpath.
 *
 * <p>Pinned contracts:
 *   - available: true even when no endpoints are registered
 *   - methods empty → defaults to ["GET"] (Spring's all-methods default)
 *   - methods present → emitted as alphabetically sorted strings
 *   - endpoints sorted by path asc (stable dashboard order)
 *   - handler = ClassSimpleName.methodName (used as the dashboard label)
 */
@SuppressWarnings({"java:S125", "java:S5853"})  // S125: prose comments with code-like glyphs (arrows, backticks, parens) — not actual commented-out code. S5853: multi-assertion chain refactor deferred ; current shape reads better with subject + N separate assertions.
class ApiSectionProviderTest {

    private static Method sampleMethod() throws NoSuchMethodException {
        // Use any concrete method on a class for HandlerMethod construction;
        // the provider only reads getMethod().getName() + getBeanType().getSimpleName().
        return SampleController.class.getMethod("hello");
    }

    @Test
    void parse_emptyHandlerMap_returnsAvailableTrueWithZeroTotal() throws Exception {
        // Pinned: even with zero endpoints, the section is reachable —
        // a frontend that gates on `available` shouldn't hide the panel
        // just because the controller scan turned up empty (could mean
        // a feature is disabled, not that the endpoint died).
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(Map.of());

        Map<String, Object> result = new ApiSectionProvider(mapping).parse();

        assertThat(result).containsEntry("available", true);
        assertThat(result).containsEntry("total", 0);
        assertThat((List<?>) result.get("endpoints")).isEmpty();
    }

    @Test
    void parse_singleEndpoint_emitsPathMethodsAndHandler() throws Exception {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(
                RequestMappingInfo.paths("/api/customers").methods(RequestMethod.GET).build(),
                new HandlerMethod(new SampleController(), sampleMethod())
        );
        when(mapping.getHandlerMethods()).thenReturn(handlers);

        Map<String, Object> result = new ApiSectionProvider(mapping).parse();

        assertThat(result).containsEntry("total", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) result.get("endpoints");
        assertThat(endpoints).hasSize(1);
        Map<String, Object> ep = endpoints.get(0);
        assertThat(ep).containsEntry("path", "/api/customers");
        assertThat(asStringList(ep.get("methods"))).containsExactly("GET");
        // Handler = SimpleClassName.methodName — the dashboard renders this
        // as a clickable label that takes you to the controller in the IDE.
        assertThat(ep).containsEntry("handler", "SampleController.hello");
    }

    @Test
    void parse_emptyMethods_defaultsToGet() throws Exception {
        // Pinned: a @RequestMapping with no `method=` attribute matches
        // ALL HTTP verbs in Spring. The dashboard surfaces this as
        // ["GET"] — a deliberate simplification so the API table doesn't
        // show every endpoint as supporting GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS.
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(
                RequestMappingInfo.paths("/health").build(), // no .methods(...)
                new HandlerMethod(new SampleController(), sampleMethod())
        );
        when(mapping.getHandlerMethods()).thenReturn(handlers);

        Map<String, Object> result = new ApiSectionProvider(mapping).parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) result.get("endpoints");
        assertThat(asStringList(endpoints.get(0).get("methods"))).containsExactly("GET");
    }

    @Test
    void parse_multipleMethods_sortedAlphabetically() throws Exception {
        // Pinned: when an endpoint accepts multiple verbs (e.g. POST + PUT
        // for upsert), they render in alphabetic order — POST then PUT —
        // not in declaration order which depends on enum iteration and
        // would be non-deterministic across JVM runs.
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(
                RequestMappingInfo.paths("/api/upsert")
                        .methods(RequestMethod.PUT, RequestMethod.POST)
                        .build(),
                new HandlerMethod(new SampleController(), sampleMethod())
        );
        when(mapping.getHandlerMethods()).thenReturn(handlers);

        Map<String, Object> result = new ApiSectionProvider(mapping).parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) result.get("endpoints");
        assertThat(asStringList(endpoints.get(0).get("methods"))).containsExactly("POST", "PUT");
    }

    /** Helper to bridge raw {@code Object} → {@code List<String>} for AssertJ. */
    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        return (List<String>) o;
    }

    @Test
    void parse_multipleEndpoints_sortedByPathAsc() throws Exception {
        // Pinned: stable dashboard ordering — even if Spring's internal
        // map iteration changes order across versions, the rendered list
        // is deterministic. Critical for the "no diff" check the dashboard
        // does between successive snapshots.
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        // Insert in REVERSE alphabetical order to make the sort visible
        handlers.put(
                RequestMappingInfo.paths("/zebra").methods(RequestMethod.GET).build(),
                new HandlerMethod(new SampleController(), sampleMethod())
        );
        handlers.put(
                RequestMappingInfo.paths("/alpha").methods(RequestMethod.GET).build(),
                new HandlerMethod(new SampleController(), sampleMethod())
        );
        handlers.put(
                RequestMappingInfo.paths("/mike").methods(RequestMethod.GET).build(),
                new HandlerMethod(new SampleController(), sampleMethod())
        );
        when(mapping.getHandlerMethods()).thenReturn(handlers);

        Map<String, Object> result = new ApiSectionProvider(mapping).parse();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) result.get("endpoints");
        assertThat(endpoints).extracting(ep -> ep.get("path"))
                .containsExactly("/alpha", "/mike", "/zebra");
    }

    /** Sample controller used to generate a real Method for HandlerMethod construction. */
    static class SampleController {
        @SuppressWarnings("unused")
        public String hello() {
            return "ok";
        }
    }
}
