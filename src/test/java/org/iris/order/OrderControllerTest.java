package org.iris.order;

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
 * Unit tests for the {@code PUT /orders/{id}/status} endpoint added
 * 2026-04-27. Covers the 4 paths an HTTP client can drive :
 *
 * <ol>
 *   <li>Happy path : valid transition → 200 + updated DTO + repo.save.</li>
 *   <li>404 : order id doesn't exist → no save attempted.</li>
 *   <li>409 : forbidden state-machine transition → ProblemDetail body.</li>
 *   <li>Self-transition (idempotency) is allowed by the state machine.</li>
 * </ol>
 *
 * <p>The state-machine itself is tested separately in {@code OrderStatusTest} ;
 * this spec only verifies the controller wiring on top.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class OrderControllerTest {

    private OrderRepository repo;
    private OrderController controller;

    @BeforeEach
    void setUp() {
        repo = mock(OrderRepository.class);
        controller = new OrderController(repo);
    }

    @Test
    void updateStatus_validTransition_returns200WithUpdatedOrder() {
        Order existing = order(1L, OrderStatus.PENDING);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.updateStatus(
                1L, new UpdateOrderStatusRequest(OrderStatus.CONFIRMED));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(OrderDto.class);
        OrderDto dto = (OrderDto) response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(repo).save(any(Order.class));
    }

    @Test
    void updateStatus_unknownId_returns404WithoutSave() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.updateStatus(
                99L, new UpdateOrderStatusRequest(OrderStatus.CONFIRMED));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(repo, never()).save(any(Order.class));
    }

    @Test
    void updateStatus_forbiddenTransition_returns409WithProblemDetail() {
        // SHIPPED is terminal — can't go back to PENDING.
        Order shipped = order(2L, OrderStatus.SHIPPED);
        when(repo.findById(2L)).thenReturn(Optional.of(shipped));

        ResponseEntity<?> response = controller.updateStatus(
                2L, new UpdateOrderStatusRequest(OrderStatus.PENDING));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("currentStatus", "SHIPPED");
        assertThat(body).containsEntry("targetStatus", "PENDING");
        assertThat((String) body.get("detail")).contains("SHIPPED").contains("PENDING");
        verify(repo, never()).save(any(Order.class));
    }

    @Test
    void updateStatus_selfTransition_allowedAndIdempotent() {
        // Re-affirming the same status (e.g. retry after a network blip)
        // must NOT 409 — the state machine treats self-transitions as
        // valid (canTransitionTo(self) = true).
        Order existing = order(3L, OrderStatus.CONFIRMED);
        when(repo.findById(3L)).thenReturn(Optional.of(existing));
        when(repo.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.updateStatus(
                3L, new UpdateOrderStatusRequest(OrderStatus.CONFIRMED));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(repo).save(any(Order.class));
    }

    private static Order order(Long id, OrderStatus status) {
        Order o = new Order();
        o.setId(id);
        o.setCustomerId(42L);
        o.setStatus(status);
        o.setTotalAmount(new BigDecimal("19.99"));
        o.setCreatedAt(Instant.parse("2026-04-27T10:00:00Z"));
        return o;
    }
}
