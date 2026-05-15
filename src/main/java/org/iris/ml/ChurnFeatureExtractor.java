package org.iris.ml;

import org.iris.customer.Customer;
import org.iris.order.Order;
import org.iris.order.OrderLine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Translates JPA Customer + Order + OrderLine entities into the 8-element
 * float32 feature vector consumed by {@link ChurnPredictor}.
 *
 * <p>The 8 features + their order MUST match the Python training pipeline
 * (see {@code iris_service.ml.feature_engineering.FEATURE_NAMES} in
 * the Python sibling). The cross-language smoke test (per shared
 * ADR-0060 §"Verification protocol") asserts that the same input
 * produces the same prediction in Java + Python — feature ordering is
 * the contract that makes that work.
 *
 * <p>Feature index → name mapping (must match Python order) :
 * <ol start="0">
 *   <li>{@code days_since_last_order}      — int days clipped ≥ 0
 *   <li>{@code total_revenue_30d}          — sum of order.totalAmount in last 30 days
 *   <li>{@code total_revenue_90d}          — same window 90 days
 *   <li>{@code total_revenue_365d}         — same window 365 days
 *   <li>{@code order_frequency}            — orders / customer_lifetime_days
 *   <li>{@code cart_diversity}             — distinct products / total order_lines
 *   <li>{@code email_domain_class}         — int 0..3 (corporate/mainstream/disposable/unknown)
 *   <li>{@code customer_lifetime_days}     — int days since customer.createdAt
 * </ol>
 */
@Service
public class ChurnFeatureExtractor {

    /** Total feature count — bound to the ONNX input tensor shape. */
    public static final int N_FEATURES = 8;

    /** Mainstream consumer email domains — bucket 1. */
    private static final Set<String> MAINSTREAM_DOMAINS = Set.of(
            "gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "icloud.com",
            "live.com", "msn.com", "aol.com", "protonmail.com", "yandex.com"
    );

    /** Known disposable / throwaway providers — bucket 2. */
    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
            "tempmail.com", "10minutemail.com", "guerrillamail.com",
            "mailinator.com", "throwaway.email"
    );

    private final Clock clock;

    public ChurnFeatureExtractor(Clock clock) {
        this.clock = clock;
    }

    /**
     * Build the 8-feature vector for one customer.
     *
     * @param customer    the JPA entity ; {@code customer.id, email, createdAt} are read.
     * @param orders      all orders belonging to this customer (any time range).
     * @param orderLines  all order_lines belonging to this customer's orders.
     * @return float[8] in the canonical order. Never {@code null}.
     */
    public float[] extract(Customer customer, Collection<Order> orders, Collection<OrderLine> orderLines) {
        Instant now = clock.instant();
        long lifetimeDays = Math.max(1, ChronoUnit.DAYS.between(customer.getCreatedAt(), now));

        Instant lastOrderAt = orders.stream()
                .map(Order::getCreatedAt)
                .max(Instant::compareTo)
                .orElse(customer.getCreatedAt());
        long daysSinceLast = Math.max(0, ChronoUnit.DAYS.between(lastOrderAt, now));

        double rev30 = sumRevenueWithin(orders, now, Duration.ofDays(30));
        double rev90 = sumRevenueWithin(orders, now, Duration.ofDays(90));
        double rev365 = sumRevenueWithin(orders, now, Duration.ofDays(365));

        double frequency = (double) orders.size() / lifetimeDays;
        double diversity = computeDiversity(orderLines);
        int domainClass = classifyEmailDomain(customer.getEmail());

        return new float[] {
                daysSinceLast,  // long → float implicit widening (Sonar S1905)
                (float) rev30,
                (float) rev90,
                (float) rev365,
                (float) frequency,
                (float) diversity,
                domainClass,    // int → float implicit widening (Sonar S1905)
                lifetimeDays,   // long → float implicit widening (Sonar S1905)
        };
    }

    /**
     * Classify the email's domain into one of the 4 buckets. Mirrors
     * {@code classify_email_domain} in the Python sibling — same buckets,
     * same priority, same lower-casing semantics.
     */
    public static int classifyEmailDomain(String email) {
        if (email == null) {
            return 3;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return 3;
        }
        String domain = email.substring(at + 1).toLowerCase().trim();
        if (domain.isEmpty()) {
            return 3;
        }
        if (DISPOSABLE_DOMAINS.contains(domain)) {
            return 2;
        }
        if (MAINSTREAM_DOMAINS.contains(domain)) {
            return 1;
        }
        return 0;
    }

    private static double sumRevenueWithin(Collection<Order> orders, Instant now, Duration window) {
        Instant threshold = now.minus(window);
        double sum = 0.0;
        for (Order o : orders) {
            if (o.getCreatedAt() != null && !o.getCreatedAt().isBefore(threshold)) {
                BigDecimal amount = o.getTotalAmount();
                if (amount != null) {
                    sum += amount.doubleValue();
                }
            }
        }
        return sum;
    }

    private static double computeDiversity(Collection<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0.0;
        }
        Set<Long> distinctProducts = new HashSet<>();
        int total = 0;
        for (OrderLine line : lines) {
            distinctProducts.add(line.getProductId());
            total++;
        }
        return total == 0 ? 0.0 : (double) distinctProducts.size() / (double) total;
    }
}
