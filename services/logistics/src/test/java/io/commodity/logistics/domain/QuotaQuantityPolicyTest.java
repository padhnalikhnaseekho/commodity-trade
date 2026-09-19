package io.commodity.logistics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.commodity.platform.error.DomainException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuotaQuantityPolicyTest {

    private static BigDecimal q(String v) { return new BigDecimal(v); }

    @Test
    void allowsExactlyFillingTheQuota() {
        assertThatCode(() -> QuotaQuantityPolicy.check("1.1", q("1000"), q("750"), q("250"))).doesNotThrowAnyException();
    }

    // PROVES: the refusal carries every number involved (production habit, not demo code).
    @Test
    void refusesToExceedTheQuotaAndReportsTheNumbers() {
        assertThatThrownBy(() -> QuotaQuantityPolicy.check("1.1", q("1000"), q("750"), q("250.0001")))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.details()).containsEntry("quotaQty", "1000")
                            .containsEntry("existingAssignedQty", "750").containsEntry("requestedQty", "250.0001");
                });
    }
}
