package org.iris.order;

import org.iris.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code PATCH /orders/{orderId}/lines/{lineId}/status}
 * endpoint added 2026-04-27 for shared ADR-0063 (per-line refund state
 * machine). Covers the 5 paths an HTTP client can drive :
 *
 * <ol>
 *   <li>Happy path : valid transition → 200 + updated DTO + repo.save.</li>
 *   <li>404 : line id doesn't exist.</li>
 *   <li>404 : line exists but belongs to a different order (URL spoofing
 *       safety).</li>
 *   <li>409 : forbidden state-machine transition (e.g. PENDING → REFUNDED
 *       skips the SHIPPED gate ; rejected per ADR-0063 §"Decision").</li>
 *   <li>Self-transition (idempotency) is allowed.</li>
 * </ol>
 *
 * <p>The state-machine itself lives on
 * {@link OrderLineStatus#canTransitionTo} ; this spec only verifies the
 * controller wiring on top.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class OrderLineControllerStatusTest {

    private OrderLineRepository lineRepo;
    private OrderRepository orderRepo;
    private OrderLineController controller;

    @BeforeEach
    void setUp() {
        lineRepo = mock(OrderLineRepository.class);
        orderRepo = mock(OrderRepository.class);
        ProductRepository productRepo = mock(ProductRepository.class);
        controller = new OrderLineController(lineRepo, orderRepo, productRepo);
    }

    @Test
    void updateLineStatus_validForwardTransition_returns200() {
        OrderLine line = orderLine(7L, 100L, OrderLineStatus.PENDING);
        when(lineRepo.findById(7L)).thenReturn(Optional.of(line));
        when(lineRepo.save(any(OrderLine.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.updateLineStatus(
                100L, 7L, new UpdateOrderLineStatusRequest(OrderLineStatus.SHIPPED, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(OrderLineDto.class);
        OrderLineDto dto = (OrderLineDto) response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.status()).isEqualTo(OrderLineStatus.SHIPPED);
        verify(lineRepo).save(any(OrderLine.class));
    }

    @Test
    void updateLineStatus_unknownLineId_returns404() {
        when(lineRepo.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.updateLineStatus(
                100L, 99L, new UpdateOrderLineStatusRequest(OrderLineStatus.SHIPPED, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(lineRepo, never()).save(any(OrderLine.class));
    }

    @Test
    void updateLineStatus_lineBelongsToDifferentOrder_returns404() {
        // URL-spoofing safety : if the {orderId} in the URL doesn't
        // match line.orderId we treat it as 404 (don't leak existence).
        OrderLine line = orderLine(7L, 200L, OrderLineStatus.PENDING);
        when(lineRepo.findById(7L)).thenReturn(Optional.of(line));

        ResponseEntity<?> response = controller.updateLineStatus(
                100L, 7L, new UpdateOrderLineStatusRequest(OrderLineStatus.SHIPPED, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(lineRepo, never()).save(any(OrderLine.class));
    }

    @Test
    void updateLineStatus_skipPendingToRefunded_returns409() {
        // PENDING → REFUNDED skips the SHIPPED gate ; rejected by the
        // audit requirement in ADR-0063 §"Decision".
        OrderLine line = orderLine(7L, 100L, OrderLineStatus.PENDING);
        when(lineRepo.findById(7L)).thenReturn(Optional.of(line));

        ResponseEntity<?> response = controller.updateLineStatus(
                100L, 7L, new UpdateOrderLineStatusRequest(OrderLineStatus.REFUNDED, "customer disputed charge"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("currentStatus", "PENDING");
        assertThat(body).containsEntry("targetStatus", "REFUNDED");
        assertThat(body).containsEntry("reason", "customer disputed charge");
        verify(lineRepo, never()).save(any(OrderLine.class));
    }

    @Test
    void updateLineStatus_refundedToAnything_returns409() {
        // REFUNDED is terminal — no transitions out (except self).
        OrderLine line = orderLine(7L, 100L, OrderLineStatus.REFUNDED);
        when(lineRepo.findById(7L)).thenReturn(Optional.of(line));

        ResponseEntity<?> response = controller.updateLineStatus(
                100L, 7L, new UpdateOrderLineStatusRequest(OrderLineStatus.SHIPPED, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateLineStatus_selfTransition_allowedAndIdempotent() {
        // Re-affirming the same status (e.g. retry after a network blip)
        // must NOT 409 — canTransitionTo(self) == true.
        OrderLine line = orderLine(7L, 100L, OrderLineStatus.SHIPPED);
        when(lineRepo.findById(7L)).thenReturn(Optional.of(line));
        when(lineRepo.save(any(OrderLine.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.updateLineStatus(
                100L, 7L, new UpdateOrderLineStatusRequest(OrderLineStatus.SHIPPED, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(lineRepo).save(any(OrderLine.class));
    }

    @Test
    void updateLineStatus_doesNotRecomputeOrderTotal() {
        // Refunding does NOT mutate the snapshot price per ADR-0063,
        // so Order.totalAmount stays as-is. The orderRepo should NOT
        // be touched.
        OrderLine line = orderLine(7L, 100L, OrderLineStatus.SHIPPED);
        when(lineRepo.findById(7L)).thenReturn(Optional.of(line));
        when(lineRepo.save(any(OrderLine.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.updateLineStatus(
                100L, 7L, new UpdateOrderLineStatusRequest(OrderLineStatus.REFUNDED, "defective"));

        verify(orderRepo, never()).save(any());
    }

    private static OrderLine orderLine(Long lineId, Long orderId, OrderLineStatus status) {
        OrderLine line = new OrderLine();
        line.setId(lineId);
        line.setOrderId(orderId);
        line.setProductId(42L);
        line.setQuantity(2);
        line.setUnitPriceAtOrder(new BigDecimal("9.99"));
        line.setStatus(status);
        line.setCreatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        return line;
    }
}
