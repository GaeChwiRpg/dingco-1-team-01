package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.QueueStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * TRI-56 — {@link ReviewQueryService} 는 정렬 규칙(오래된 순)을 강제하는 것 외에 아무 판단도
 * 하지 않는다. DB 는 안 쓰므로 repository 를 목으로 대체한 순수 위임 검증이다.
 */
class ReviewQueryServiceTest {

    private final InquiryReviewQueueRepository repository = mock(InquiryReviewQueueRepository.class);
    private final ReviewQueryService service = new ReviewQueryService(repository);

    @Test
    @DisplayName("createdAt ASC(오래된 순) 정렬을 실어 repository.search 로 그대로 위임한다")
    void searchDelegatesWithOldestFirstSort() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-07T00:00:00Z");
        given(repository.search(eq(QueueStatus.PENDING), eq(from), eq(to), org.mockito.ArgumentMatchers.any()))
                .willReturn(Page.empty());

        service.search(QueueStatus.PENDING, from, to, 2, 30);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(eq(QueueStatus.PENDING), eq(from), eq(to), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getSort())
                .as("적체 방지가 목적이라 상담원이 오래된 항목부터 본다 — 정렬 축은 협상 대상이 아니다")
                .isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt"));
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(30);
    }
}
