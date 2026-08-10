package com.dingco.triage.service.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dingco.triage.domain.type.InquiryCategory;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 계약 C 값 구조의 불변식 — {@code source} 와 {@code confidence} 의 null 조합을 팩토리·생성자가
 * 강제하는지 (D-033). 엔티티의 {@code reusedFromHuman}/{@code reusedFromAi} 와 같은 규율을 캐시
 * 값에서도 지킨다.
 */
class CachedClassificationTest {

    @Test
    @DisplayName("ofAi 는 confidence 를 원본 값으로 담고 source=AI 다")
    void ofAi_keepsConfidence() {
        CachedClassification value =
                CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.95"), 10L);

        assertThat(value.source()).isEqualTo(CacheSource.AI);
        assertThat(value.confidence()).isEqualByComparingTo("0.95");
        assertThat(value.category()).isEqualTo(InquiryCategory.DELIVERY);
        assertThat(value.sourceResultId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("ofHuman 은 confidence 를 null 로 강제한다 — 사람은 확신도를 매기지 않는다")
    void ofHuman_forcesNullConfidence() {
        CachedClassification value =
                CachedClassification.ofHuman(InquiryCategory.RETURN_REFUND, 20L);

        assertThat(value.source()).isEqualTo(CacheSource.HUMAN);
        assertThat(value.confidence()).isNull();
        assertThat(value.category()).isEqualTo(InquiryCategory.RETURN_REFUND);
    }

    @Test
    @DisplayName("source=HUMAN 인데 confidence 가 있으면 생성 자체가 막힌다")
    void human_withConfidence_rejected() {
        assertThatThrownBy(() -> new CachedClassification(
                InquiryCategory.PAYMENT, new BigDecimal("0.5"), CacheSource.HUMAN, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("source=AI 인데 confidence 가 없으면 생성 자체가 막힌다")
    void ai_withoutConfidence_rejected() {
        assertThatThrownBy(() -> new CachedClassification(
                InquiryCategory.PAYMENT, null, CacheSource.AI, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("category·source·sourceResultId 는 null 일 수 없다 — 확정 판정만 담기 때문")
    void nulls_rejected() {
        assertThatThrownBy(() -> CachedClassification.ofHuman(null, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CachedClassification.ofHuman(InquiryCategory.ETC, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachedClassification(
                InquiryCategory.ETC, null, null, 1L))
                .isInstanceOf(NullPointerException.class);
    }
}
