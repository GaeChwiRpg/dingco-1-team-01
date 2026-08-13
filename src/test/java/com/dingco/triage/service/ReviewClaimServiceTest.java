package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dingco.triage.config.ReviewClaimProperties;
import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.ConflictCode;
import com.dingco.triage.domain.type.InquiryCategory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * TRI-93 · D-032 — 검토 항목 선점의 <b>사전 예방</b> 판단(누가 선점 가능한가)을 검증한다.
 * 낙관적 락으로 <b>사후 감지</b>되는 커밋 시점 경합은 {@link ReviewServiceTest#reproducesConcurrentUpdate()}
 * 와 같은 성격이라 재구현하지 않고, {@code saveAndFlush} 가 던지는
 * {@link ObjectOptimisticLockingFailureException} 을 {@code CONCURRENT_UPDATE} 로 옮기는
 * 배선만 목으로 확인한다.
 *
 * <p>DB 를 안 쓰는 순수 단위 테스트다 — {@link ReviewClaimService} 는 {@code isClaimAvailable}
 * 판단과 저장 위임 외에 다른 일을 하지 않는다.
 */
class ReviewClaimServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private static final Duration EXPIRY = Duration.ofMinutes(5);

    private final InquiryReviewQueueRepository repository = mock(InquiryReviewQueueRepository.class);
    private final ReviewClaimProperties properties = new ReviewClaimProperties(EXPIRY, Duration.ofMinutes(1));
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ReviewClaimService service = new ReviewClaimService(repository, properties, clock);

    private InquiryReviewQueueItem queueItem(Long id) {
        Inquiry inquiry = Inquiry.receive(1L, "환불해주세요", Channel.WEB, "nk-1", NOW);
        InquiryClassificationResult result = InquiryClassificationResult.autoAccepted(
                inquiry, InquiryCategory.RETURN_REFUND, new BigDecimal("0.950"), "claude-sonnet-5", "{}", 1);
        InquiryReviewQueueItem item = InquiryReviewQueueItem.from(result);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    @Test
    @DisplayName("선점 안 된 항목을 선점하면 claimedBy·claimedAt 이 채워지고 저장된다")
    void claimsUnclaimedItem() {
        InquiryReviewQueueItem item = queueItem(1L);
        given(repository.findById(1L)).willReturn(Optional.of(item));
        given(repository.saveAndFlush(item)).willReturn(item);

        InquiryReviewQueueItem result = service.claim(1L, 10L);

        assertThat(result.getClaimedBy()).isEqualTo(10L);
        assertThat(result.getClaimedAt()).isEqualTo(NOW);
        verify(repository).saveAndFlush(item);
    }

    @Test
    @DisplayName("본인이 이미 선점한 항목을 다시 선점하면 연장된다 — 충돌이 아니다")
    void reclaimingOwnItemExtends() {
        InquiryReviewQueueItem item = queueItem(1L);
        item.claim(10L, NOW.minus(Duration.ofMinutes(2)));
        given(repository.findById(1L)).willReturn(Optional.of(item));
        given(repository.saveAndFlush(item)).willReturn(item);

        InquiryReviewQueueItem result = service.claim(1L, 10L);

        assertThat(result.getClaimedAt())
                .as("연장이므로 선점 시각이 지금(NOW)으로 갱신된다")
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName("남이 아직 만료 안 된 선점을 쥐고 있으면 ALREADY_CLAIMED 409 — 저장하지 않는다")
    void rejectsClaimHeldByAnotherAgent() {
        InquiryReviewQueueItem item = queueItem(1L);
        item.claim(20L, NOW.minus(Duration.ofMinutes(1)));
        given(repository.findById(1L)).willReturn(Optional.of(item));

        assertThatThrownBy(() -> service.claim(1L, 10L))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getCode())
                .isEqualTo(ConflictCode.ALREADY_CLAIMED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("남의 선점이지만 만료됐으면 다시 선점할 수 있다")
    void claimsAfterOtherAgentsClaimExpired() {
        InquiryReviewQueueItem item = queueItem(1L);
        item.claim(20L, NOW.minus(EXPIRY).minus(Duration.ofSeconds(1)));
        given(repository.findById(1L)).willReturn(Optional.of(item));
        given(repository.saveAndFlush(item)).willReturn(item);

        InquiryReviewQueueItem result = service.claim(1L, 10L);

        assertThat(result.getClaimedBy()).isEqualTo(10L);
    }

    @Test
    @DisplayName("없는 항목을 선점하려 하면 NoSuchElementException — 404 로 이어진다")
    void throwsWhenItemMissing() {
        given(repository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim(999L, 10L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("커밋 시점에 낙관적 락 충돌이 나면 CONCURRENT_UPDATE 409 로 옮긴다 (D-021 과 같은 배선)")
    void translatesOptimisticLockFailureToConcurrentUpdate() {
        InquiryReviewQueueItem item = queueItem(1L);
        given(repository.findById(1L)).willReturn(Optional.of(item));
        given(repository.saveAndFlush(item)).willThrow(new ObjectOptimisticLockingFailureException(
                InquiryReviewQueueItem.class, 1L));

        assertThatThrownBy(() -> service.claim(1L, 10L))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getCode())
                .isEqualTo(ConflictCode.CONCURRENT_UPDATE);
    }
}
