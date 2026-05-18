package org.iris.customer;

import org.iris.observability.AuditEventDto;
import org.iris.observability.AuditService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerController} — direct invocation with
 * mocked dependencies, no Spring MVC test context. Faster + simpler than
 * MockMvc and adequate for the delegation + helper logic that lives
 * in this controller.
 *
 * <p>NOT covered here (deferred to integration tests / WebMvc):
 *   - @PreAuthorize role enforcement (needs Spring Security context)
 *   - Bean Validation on @Valid (needs MockMvc to trigger the resolver)
 *   - SSE / Kafka enrich endpoints (moved to sibling controllers)
 *
 * <p>Pinned contracts:
 *   - getById/update/delete/patch/getAudit/getRecent/getSummary/cursor/
 *     aggregate/batchCreate/create — all delegate to the right collaborator
 *   - getAll routes search vs findAll based on null search param
 *   - getAllV2 routes searchV2 vs findAllV2
 *   - getById throws NoSuchElementException with message including the id
 *   - create increments customer.created.count metric exactly once
 *   - capPageSize caps oversize pageables to MAX_PAGE_SIZE=100
 *   - withLinkHeaders RFC 8288 — first + last always present, next/prev
 *     conditional on hasNext / hasPrevious
 */
// eslint-disable-next-line max-lines-per-function
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class CustomerControllerTest {

    private CustomerService service;
    private RecentCustomerBuffer recentBuffer;
    private AggregationService aggregationService;
    private AuditService auditService;
    private MeterRegistry meterRegistry;
    private CustomerController controller;

    @BeforeEach
    void setUp() {
        service = mock(CustomerService.class);
        recentBuffer = mock(RecentCustomerBuffer.class);
        aggregationService = mock(AggregationService.class);
        auditService = mock(AuditService.class);
        meterRegistry = new SimpleMeterRegistry();
        controller = new CustomerController(
                service, recentBuffer, aggregationService, auditService,
                ObservationRegistry.NOOP, meterRegistry);
    }

    // ─── Delegation ─────────────────────────────────────────────────────────

    @Test
    void getById_happyPath_returnsDtoFromService() {
        // Pinned: getById delegates to service.findById and unwraps the
        // Optional. A regression that returned the Optional itself would
        // serialize as {present: true, value: {...}} — wrong contract.
        CustomerDto dto = new CustomerDto(42L, "Alice", "alice@example.com");
        when(service.findById(42L)).thenReturn(Optional.of(dto));

        CustomerDto result = controller.getById(42L);

        assertThat(result).isSameAs(dto);
    }

    @Test
    void getById_missing_throwsNoSuchElementWithIdInMessage() {
        // Pinned: 404 path. The message MUST include the missing id —
        // ApiExceptionHandler builds the Problem Detail body from this
        // string, and a generic "Not found" without the id would fail
        // the API consumer's ability to debug.
        when(service.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getById(999L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("999");
    }

    @Test
    void update_delegatesToServiceWithIdAndRequest() {
        CreateCustomerRequest req = new CreateCustomerRequest("Bob", "bob@example.com");
        CustomerDto updated = new CustomerDto(7L, "Bob", "bob@example.com");
        when(service.update(7L, req)).thenReturn(updated);

        CustomerDto result = controller.update(7L, req);

        assertThat(result).isSameAs(updated);
        verify(service).update(7L, req);
    }

    @Test
    void delete_delegatesToService() {
        // Pinned: delete is fire-and-forget (void). Service handles the
        // 404 case by throwing — the controller doesn't catch. A
        // regression that swallowed the exception would silently 200
        // instead of 404 on missing customer.
        controller.delete(15L);

        verify(service).delete(15L);
    }

    @Test
    void patch_delegatesToServiceWithIdAndPatchRequest() {
        PatchCustomerRequest req = new PatchCustomerRequest("New name", null);
        CustomerDto patched = new CustomerDto(3L, "New name", "old@example.com");
        when(service.patch(3L, req)).thenReturn(patched);

        CustomerDto result = controller.patch(3L, req);

        assertThat(result).isSameAs(patched);
    }

    @Test
    void getAudit_delegatesToAuditService_notCustomerService() {
        // Pinned: audit trail is fetched from AuditService (the audit_event
        // table), NOT from CustomerService. The wires are easy to swap
        // by accident — this test catches it.
        List<AuditEventDto> trail = List.of(
                new AuditEventDto(1L, "admin", "CUSTOMER_CREATED", "id=42", "127.0.0.1", Instant.now())
        );
        when(auditService.findByCustomerId(42L)).thenReturn(trail);

        List<AuditEventDto> result = controller.getAudit(42L);

        assertThat(result).isSameAs(trail);
        verify(service, never()).findById(any()); // explicitly NOT called
    }

    @Test
    void getRecent_delegatesToRecentCustomerBuffer_notDb() {
        // Pinned: /recent reads the in-memory buffer, NEVER hits the DB.
        // A regression that fell back to service.findAll(...) would
        // change the latency story (in-process µs vs DB ms) and break
        // the demo's "metric gauge vs query-backed endpoint" point.
        List<CustomerDto> recent = List.of(new CustomerDto(1L, "X", "x@x.com"));
        when(recentBuffer.getRecent()).thenReturn(recent);

        List<CustomerDto> result = controller.getRecent();

        assertThat(result).isSameAs(recent);
        verify(service, never()).findAll(any());
    }

    @Test
    void aggregate_delegatesToAggregationService_andRecordsTimer() {
        AggregationService.AggregatedResponse resp =
                new AggregationService.AggregatedResponse("data-payload", "stats-payload");
        when(aggregationService.aggregate()).thenReturn(resp);

        AggregationService.AggregatedResponse result = controller.aggregate();

        assertThat(result).isSameAs(resp);
        // Timer was registered AND incremented (count > 0)
        assertThat(meterRegistry.find("customer.aggregate.duration").timer()).isNotNull();
        assertThat(meterRegistry.find("customer.aggregate.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void getSummary_delegatesToServiceFindAllSummaries() {
        @SuppressWarnings("unchecked")
        Page<CustomerSummary> page = (Page<CustomerSummary>) mock(Page.class);
        when(service.findAllSummaries(any(Pageable.class))).thenReturn(page);

        Page<CustomerSummary> result = controller.getSummary(PageRequest.of(0, 20));

        assertThat(result).isSameAs(page);
    }

    @Test
    void getAllCursor_delegatesToServiceFindAllCursor() {
        CursorPage<CustomerDto> cursorPage =
                new CursorPage<>(List.of(), null, false, 20);
        when(service.findAllCursor(0L, 20)).thenReturn(cursorPage);

        CursorPage<CustomerDto> result = controller.getAllCursor(0L, 20);

        assertThat(result).isSameAs(cursorPage);
    }

    @Test
    void batchCreate_delegatesToServiceBatchCreate() {
        List<CreateCustomerRequest> reqs = List.of(
                new CreateCustomerRequest("A", "a@a.com"),
                new CreateCustomerRequest("B", "b@b.com")
        );
        BatchImportResult batchResult = new BatchImportResult(2, 2, 0, List.of(), List.of());
        when(service.batchCreate(reqs)).thenReturn(batchResult);

        BatchImportResult result = controller.batchCreate(reqs);

        assertThat(result).isSameAs(batchResult);
    }

    // ─── create + counter ───────────────────────────────────────────────────

    @Test
    void create_delegatesToServiceAndIncrementsCustomerCreatedCounter() {
        // Pinned: the customer.created.count metric is the headline
        // throughput indicator on the dashboard. A regression that
        // forgot to .increment() would flatline the chart silently.
        CreateCustomerRequest req = new CreateCustomerRequest("Eve", "eve@example.com");
        CustomerDto created = new CustomerDto(100L, "Eve", "eve@example.com");
        when(service.create(req)).thenReturn(created);

        CustomerDto result = controller.create(req);

        assertThat(result).isSameAs(created);
        // Counter incremented exactly once
        assertThat(meterRegistry.find("customer.created.count").counter()).isNotNull();
        assertThat(meterRegistry.find("customer.created.count").counter().count()).isEqualTo(1.0);
        // Timer also recorded
        assertThat(meterRegistry.find("customer.create.duration").timer().count()).isEqualTo(1);
    }

    // ─── getAll routing — search vs findAll ─────────────────────────────────

    @Test
    void getAll_v1_withNullSearch_callsFindAll() {
        // Pinned: when search query param is absent, controller calls
        // service.findAll, NOT service.search(null, ...) which would
        // be a different SQL plan and could surprise the DB.
        Page<CustomerDto> page = new PageImpl<>(List.of());
        when(service.findAll(any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<CustomerDto>> result = controller.getAll(PageRequest.of(0, 20), null);

        verify(service).findAll(any(Pageable.class));
        verify(service, never()).search(any(), any(Pageable.class));
        assertThat(result.getBody()).isSameAs(page);
    }

    @Test
    void getAll_v1_withSearchTerm_callsSearch() {
        Page<CustomerDto> page = new PageImpl<>(List.of());
        when(service.search(eq("alice"), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<CustomerDto>> result = controller.getAll(PageRequest.of(0, 20), "alice");

        verify(service).search(eq("alice"), any(Pageable.class));
        verify(service, never()).findAll(any(Pageable.class));
        assertThat(result.getBody()).isSameAs(page);
    }

    @Test
    void getAll_v2_withSearchTerm_callsSearchV2_notV1() {
        // Pinned: v2 endpoint must use the V2 service methods (which
        // populate createdAt). Routing to v1 methods would silently
        // omit the new field from every v2 response.
        Page<CustomerDtoV2> page = new PageImpl<>(List.of());
        when(service.searchV2(eq("bob"), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<CustomerDtoV2>> result = controller.getAllV2(PageRequest.of(0, 20), "bob");

        verify(service).searchV2(eq("bob"), any(Pageable.class));
        verify(service, never()).search(any(), any(Pageable.class));
        assertThat(result.getBody()).isSameAs(page);
    }

    // ─── capPageSize — defensive against ?size=999999 ───────────────────────

    @Test
    void getAll_capsPageSizeAt100_evenWhenClientRequestsMore() {
        // Pinned: MAX_PAGE_SIZE=100 prevents unbounded queries that
        // would lock the DB. A regression that removed the cap would
        // let `?size=999999` ask the DB for 999999 customers in one
        // go — easy DoS surface.
        Page<CustomerDto> page = new PageImpl<>(List.of());
        when(service.findAll(any(Pageable.class))).thenReturn(page);

        controller.getAll(PageRequest.of(0, 5_000), null); // way above cap

        verify(service).findAll(org.mockito.ArgumentMatchers.argThat(p ->
                p != null && p.getPageSize() == 100));
    }

    @Test
    void getAll_doesNotCapWhenSizeUnderLimit() {
        // Symmetric: under-cap requests pass through unchanged.
        Page<CustomerDto> page = new PageImpl<>(List.of());
        when(service.findAll(any(Pageable.class))).thenReturn(page);

        controller.getAll(PageRequest.of(0, 30), null);

        verify(service).findAll(org.mockito.ArgumentMatchers.argThat(p ->
                p != null && p.getPageSize() == 30));
    }

    // ─── withLinkHeaders — RFC 8288 ─────────────────────────────────────────

    @Test
    void getAll_includesFirstAndLastLinks_always() {
        // Pinned: first + last are unconditional per RFC 8288. Even on
        // a single-page response, these MUST appear.
        Page<CustomerDto> page = new PageImpl<>(
                List.of(new CustomerDto(1L, "X", "x@x.com")),
                PageRequest.of(0, 20), 1
        );
        when(service.findAll(any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<CustomerDto>> result = controller.getAll(PageRequest.of(0, 20), null);

        String linkHeader = result.getHeaders().getFirst("Link");
        assertThat(linkHeader).isNotNull();
        assertThat(linkHeader).contains("rel=\"first\"");
        assertThat(linkHeader).contains("rel=\"last\"");
    }

    @Test
    void getAll_includesNextLink_whenPageHasNext() {
        // Pinned: next link conditional on hasNext. On a multi-page
        // response with current=0, next MUST appear; prev MUST NOT.
        List<CustomerDto> items = List.of(new CustomerDto(1L, "X", "x@x.com"));
        // 100 total / 20 per page = 5 pages, currently on page 0
        Page<CustomerDto> page = new PageImpl<>(items, PageRequest.of(0, 20), 100);
        when(service.findAll(any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<CustomerDto>> result = controller.getAll(PageRequest.of(0, 20), null);

        String linkHeader = result.getHeaders().getFirst("Link");
        assertThat(linkHeader).contains("rel=\"next\"");
        assertThat(linkHeader).doesNotContain("rel=\"prev\""); // first page → no prev
    }

    @Test
    void getAll_v1_includesDeprecationAndSunsetHeaders() {
        // Pinned: v1 endpoint marked Deprecated + Sunset 2027-01-01.
        // Deprecating an API needs a date — clients query the Sunset
        // header to know when the endpoint dies. Removing either header
        // would break the API contract for clients monitoring deprecation.
        Page<CustomerDto> page = new PageImpl<>(List.of());
        when(service.findAll(any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<CustomerDto>> result = controller.getAll(PageRequest.of(0, 20), null);

        assertThat(result.getHeaders().getFirst("Deprecation")).isEqualTo("true");
        assertThat(result.getHeaders().getFirst("Sunset")).isEqualTo("2027-01-01T00:00:00Z");
    }

    @Test
    void getAll_v2_doesNotIncludeDeprecationHeader() {
        // Pinned: v2 is the current version, NOT deprecated. The header
        // appears only on v1.
        Page<CustomerDtoV2> page = new PageImpl<>(List.of());
        when(service.findAllV2(any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<CustomerDtoV2>> result = controller.getAllV2(PageRequest.of(0, 20), null);

        assertThat(result.getHeaders().get("Deprecation")).isNull();
        assertThat(result.getHeaders().get("Sunset")).isNull();
    }
}
