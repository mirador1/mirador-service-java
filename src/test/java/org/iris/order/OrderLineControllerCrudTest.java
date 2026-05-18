package org.iris.order;

import org.iris.product.Product;
import org.iris.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
 * Unit tests for {@link OrderLineController} CRUD paths
 * (list / add / delete) — complements
 * {@link OrderLineControllerStatusTest} (PATCH status state machine).
 *
 * <p>Two invariants pinned by the {@code add} flow that JaCoCo line
 * coverage alone would miss :
 * <ul>
 *   <li>{@code unitPriceAtOrder} is snapshotted from {@link Product#getUnitPrice()}
 *       at insert time — a later price update on the product MUST NOT
 *       drift the historical line's amount (ADR-0059 invariant 5).</li>
 *   <li>{@code Order.totalAmount} is recomputed from line data after
 *       every add/delete, so the denormalised total stays consistent.</li>
 * </ul>
 */
class OrderLineControllerCrudTest {

    private OrderLineRepository lineRepo;
    private OrderRepository orderRepo;
    private ProductRepository productRepo;
    private OrderLineController controller;

    @BeforeEach
    void setUp() {
        lineRepo = mock(OrderLineRepository.class);
        orderRepo = mock(OrderRepository.class);
        productRepo = mock(ProductRepository.class);
        controller = new OrderLineController(lineRepo, orderRepo, productRepo);
    }

    // ─── GET /orders/{orderId}/lines ─────────────────────────────────────────

    @Test
    void list_returnsLinesInOrder_mappedToDto() {
        OrderLine l1 = orderLine(1L, 100L, 21L, 2, "9.99", OrderLineStatus.PENDING);
        OrderLine l2 = orderLine(2L, 100L, 22L, 1, "19.99", OrderLineStatus.SHIPPED);
        when(lineRepo.findByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(l1, l2));

        List<OrderLineDto> result = controller.list(100L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(1).status()).isEqualTo(OrderLineStatus.SHIPPED);
    }

    @Test
    void list_returnsEmptyForOrderWithNoLines() {
        when(lineRepo.findByOrderIdOrderByIdAsc(100L)).thenReturn(List.of());

        assertThat(controller.list(100L)).isEmpty();
    }

    // ─── POST /orders/{orderId}/lines ────────────────────────────────────────

    @Test
    void add_snapshotsProductUnitPriceAndDefaultsStatusToPending() {
        // Pinned : the line MUST snapshot Product.unitPrice (9.99) at
        // insert. A later product price bump to 19.99 MUST NOT drift this
        // line's amount (ADR-0059 invariant 5 — historical orders stay
        // auditable). Status defaults to PENDING per ADR-0063.
        Order order = order(100L);
        Product product = productAt(21L, "9.99");
        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));
        when(productRepo.findById(21L)).thenReturn(Optional.of(product));
        when(lineRepo.save(any(OrderLine.class))).thenAnswer(inv -> {
            OrderLine l = inv.getArgument(0);
            l.setId(1L);
            l.setCreatedAt(Instant.now());
            return l;
        });
        // recomputeOrderTotal stub : returns 1 line with quantity=2 ×
        // 9.99 = 19.98 so the orderRepo.save total is verifiable.
        when(lineRepo.findByOrderIdOrderByIdAsc(100L))
                .thenReturn(List.of(orderLine(1L, 100L, 21L, 2, "9.99", OrderLineStatus.PENDING)));

        OrderLineDto dto = controller.add(100L, new CreateOrderLineRequest(21L, 2));

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.unitPriceAtOrder()).isEqualByComparingTo("9.99");
        assertThat(dto.status()).isEqualTo(OrderLineStatus.PENDING);
        // Order.totalAmount must have been refreshed to 2 × 9.99 = 19.98
        verify(orderRepo).save(any(Order.class));
        assertThat(order.getTotalAmount()).isEqualByComparingTo("19.98");
    }

    @Test
    void add_unknownOrder_throws() {
        // The controller currently raises IllegalArgumentException — the
        // global exception handler maps it to 400. Pinned : MUST NOT
        // create a line if the order doesn't exist.
        when(orderRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.add(999L, new CreateOrderLineRequest(21L, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Order 999");
        verify(lineRepo, never()).save(any(OrderLine.class));
    }

    @Test
    void add_unknownProduct_throws() {
        Order order = order(100L);
        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));
        when(productRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.add(100L, new CreateOrderLineRequest(999L, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Product 999");
        verify(lineRepo, never()).save(any(OrderLine.class));
    }

    // ─── DELETE /orders/{orderId}/lines/{lineId} ─────────────────────────────

    @Test
    void delete_existingLine_recomputesOrderTotalAndReturns204() {
        // After deleting a line the remaining lines' running sum should
        // become the new Order.totalAmount.
        OrderLine line = orderLine(1L, 100L, 21L, 2, "9.99", OrderLineStatus.PENDING);
        OrderLine remaining = orderLine(2L, 100L, 22L, 3, "19.99", OrderLineStatus.PENDING);
        Order order = order(100L);
        when(lineRepo.findById(1L)).thenReturn(Optional.of(line));
        when(orderRepo.findById(100L)).thenReturn(Optional.of(order));
        // After deletion, list returns only the remaining line ; the
        // total recompute should yield 3 × 19.99 = 59.97.
        when(lineRepo.findByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(remaining));

        ResponseEntity<Void> response = controller.delete(100L, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(lineRepo).deleteById(1L);
        verify(orderRepo).save(any(Order.class));
        assertThat(order.getTotalAmount()).isEqualByComparingTo("59.97");
    }

    @Test
    void delete_unknownLine_returns404() {
        when(lineRepo.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.delete(100L, 999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(lineRepo, never()).deleteById(999L);
        verify(orderRepo, never()).save(any(Order.class));
    }

    @Test
    void delete_lineBelongsToOtherOrder_returns404() {
        // URL-spoofing safety : if {orderId} doesn't match line.orderId we
        // return 404 (don't leak existence + don't accidentally recompute
        // a foreign order's total).
        OrderLine line = orderLine(1L, 200L, 21L, 2, "9.99", OrderLineStatus.PENDING);
        when(lineRepo.findById(1L)).thenReturn(Optional.of(line));

        ResponseEntity<Void> response = controller.delete(100L, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(lineRepo, never()).deleteById(any());
        verify(orderRepo, never()).save(any(Order.class));
    }

    @Test
    void delete_orderMissing_skipsTotalRecompute() {
        // Edge case : line exists but its parent order was deleted
        // concurrently. The line delete still succeeds (returns 204) but
        // no orderRepo.save is attempted.
        OrderLine line = orderLine(1L, 100L, 21L, 2, "9.99", OrderLineStatus.PENDING);
        when(lineRepo.findById(1L)).thenReturn(Optional.of(line));
        when(orderRepo.findById(100L)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.delete(100L, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(lineRepo).deleteById(1L);
        verify(orderRepo, never()).save(any(Order.class));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static Order order(Long id) {
        Order o = new Order();
        o.setId(id);
        o.setCustomerId(42L);
        o.setStatus(OrderStatus.PENDING);
        o.setTotalAmount(BigDecimal.ZERO);
        o.setCreatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        return o;
    }

    private static Product productAt(Long id, String price) {
        Product p = new Product();
        p.setId(id);
        p.setName("P-" + id);
        p.setUnitPrice(new BigDecimal(price));
        p.setStockQuantity(100);
        return p;
    }

    private static OrderLine orderLine(Long id, Long orderId, Long productId, int qty, String price, OrderLineStatus status) {
        OrderLine l = new OrderLine();
        l.setId(id);
        l.setOrderId(orderId);
        l.setProductId(productId);
        l.setQuantity(qty);
        l.setUnitPriceAtOrder(new BigDecimal(price));
        l.setStatus(status);
        l.setCreatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        return l;
    }
}
