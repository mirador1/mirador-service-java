package org.iris.product;

import org.iris.order.Order;
import org.iris.order.OrderDto;
import org.iris.order.OrderRepository;
import org.iris.order.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductController} — pure Mockito, no Spring MVC
 * test context.
 *
 * <p>Two concerns are exercised here :
 * <ol>
 *   <li><b>Search-vs-list dispatch</b> on {@code GET /products} — the
 *       null / blank / trimmed branches added 2026-04-27.</li>
 *   <li><b>{@code GET /products/{id}/orders}</b> — server-side
 *       "orders containing this product" endpoint added per shared
 *       ADR-0059. The 404-on-missing-product contract distinguishes
 *       "wrong product id" from "existing product with no orders".</li>
 * </ol>
 *
 * <p>The remaining CRUD paths (get / create / update / delete) are
 * exercised by integration tests in follow-up MRs.
 */
class ProductControllerTest {

    private ProductRepository repo;
    private OrderRepository orderRepo;
    private ProductController controller;

    @BeforeEach
    void setUp() {
        repo = mock(ProductRepository.class);
        orderRepo = mock(OrderRepository.class);
        controller = new ProductController(repo, orderRepo);
    }

    // ─── GET /products (search-vs-list dispatch) ──────────────────────────────

    @Test
    void list_withNullSearch_callsFindAllNotSearch() {
        when(repo.findAll(any(Pageable.class))).thenReturn(emptyPage());

        Page<ProductDto> result = controller.list(null, Pageable.unpaged());

        assertThat(result).isEmpty();
        verify(repo).findAll(any(Pageable.class));
        verify(repo, never()).search(any(), any());
    }

    @Test
    void list_withBlankSearch_callsFindAllNotSearch() {
        // Blank string is the typical "user cleared the search box" case.
        // Falling through to findAll() avoids issuing SQL like
        // `LIKE '%%'` which would match everything but with a wasted index
        // scan.
        when(repo.findAll(any(Pageable.class))).thenReturn(emptyPage());

        controller.list("", Pageable.unpaged());
        controller.list("   ", Pageable.unpaged());

        verify(repo, times(2)).findAll(any(Pageable.class));
        verify(repo, never()).search(any(), any());
    }

    @Test
    void list_withSearch_callsSearchWithTrimmedValue() {
        // The UI sends "  laptop " from a sloppy paste — repository
        // contract is "search expects a trimmed value", so the controller
        // owns the trim. Repository tests should NOT have to handle
        // leading/trailing whitespace.
        when(repo.search(eq("laptop"), any(Pageable.class))).thenReturn(emptyPage());

        controller.list("  laptop ", Pageable.unpaged());

        verify(repo).search(eq("laptop"), any(Pageable.class));
        verify(repo, never()).findAll(any(Pageable.class));
    }

    @Test
    void list_returnsDtoPage() {
        Product alpha = product(1L, "Alpha", "the first letter");
        Product beta = product(2L, "Beta", "the second letter");
        when(repo.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(alpha, beta)));

        Page<ProductDto> result = controller.list(null, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).name()).isEqualTo("Alpha");
        assertThat(result.getContent().get(1).name()).isEqualTo("Beta");
    }

    // ─── GET /products/{id}/orders ────────────────────────────────────────────

    @Test
    void ordersForProduct_returns404WhenProductMissing() {
        // The path-level 404 protects against silent typos returning empty
        // pages. The orderRepo MUST NOT be hit when the product itself
        // doesn't exist (saves a join + count round-trip).
        when(repo.existsById(42L)).thenReturn(false);

        ResponseEntity<Page<OrderDto>> response = controller.ordersForProduct(42L, PageRequest.of(0, 20));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        verifyNoInteractions(orderRepo);
    }

    @Test
    void ordersForProduct_returnsEmptyPageForExistingProductWithNoOrders() {
        // An existing-but-unsold product is a legitimate state — it MUST
        // surface as an empty page (totalElements = 0), not a 404.
        // Distinct from the missing-product case so the UI consumer can
        // tell "no orders yet" from "wrong product id".
        when(repo.existsById(7L)).thenReturn(true);
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepo.findByProductId(7L, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        ResponseEntity<Page<OrderDto>> response = controller.ordersForProduct(7L, pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).isEmpty();
        assertThat(response.getBody().getTotalElements()).isZero();
    }

    @Test
    void ordersForProduct_mapsRepoPageToDtoPage() {
        // Verify the entity → DTO mapping path so a refactor that returns
        // raw Order entities (and leaks lazy proxies / @Setter mutability)
        // is caught by the test.
        when(repo.existsById(3L)).thenReturn(true);
        Order o1 = orderFixture(101L, 1L, OrderStatus.SHIPPED, new BigDecimal("199.98"));
        Order o2 = orderFixture(102L, 2L, OrderStatus.PENDING, new BigDecimal("49.99"));
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepo.findByProductId(3L, pageable))
                .thenReturn(new PageImpl<>(List.of(o1, o2), pageable, 2));

        ResponseEntity<Page<OrderDto>> response = controller.ordersForProduct(3L, pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(OrderDto::id, OrderDto::customerId, OrderDto::status, OrderDto::totalAmount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, 1L, OrderStatus.SHIPPED, new BigDecimal("199.98")),
                        org.assertj.core.groups.Tuple.tuple(102L, 2L, OrderStatus.PENDING, new BigDecimal("49.99")));
    }

    @Test
    void ordersForProduct_passesPageableThroughUnchanged() {
        // Pinned : the controller does NOT cap or rewrite the Pageable
        // (unlike CustomerController which has a MAX_PAGE_SIZE guard).
        // The repo receives whatever the caller asked for ; the global
        // @PageableDefault on the @GetMapping handles the no-param case.
        when(repo.existsById(5L)).thenReturn(true);
        Pageable pageable = PageRequest.of(2, 50);
        when(orderRepo.findByProductId(5L, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        controller.ordersForProduct(5L, pageable);

        verify(orderRepo).findByProductId(5L, pageable);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static Page<Product> emptyPage() {
        return new PageImpl<>(List.of());
    }

    private static Product product(Long id, String name, String description) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setDescription(description);
        p.setUnitPrice(new BigDecimal("9.99"));
        p.setStockQuantity(10);
        p.setCreatedAt(Instant.parse("2026-04-27T12:00:00Z"));
        return p;
    }

    private static Order orderFixture(Long id, Long customerId, OrderStatus status, BigDecimal total) {
        Order o = new Order();
        o.setId(id);
        o.setCustomerId(customerId);
        o.setStatus(status);
        o.setTotalAmount(total);
        o.setCreatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        o.setUpdatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        return o;
    }
}
