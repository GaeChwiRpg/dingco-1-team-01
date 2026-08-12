package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.service.cache.CacheSource;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.service.cache.ClassificationCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 재사용할 답을 <b>어떤 순서로 찾는지</b> 고정한다 (TRI-47 · D-033 · D-037).
 *
 * <p><b>이 테스트가 막는 회귀는 셋이다.</b>
 *
 * <ol>
 *   <li><b>순서가 뒤집히는 것</b> — 사람이 정정한 답을 두고 AI 답을 쓰게 된다. 그러면 감사가
 *       잡아낸 정정을 시스템이 도로 무시한다 (D-033). 리포지토리는 두 메서드를 나란히 제공할
 *       뿐이라 <b>뒤집어 불러도 컴파일된다</b> — 그래서 순서를 여기서 못 박는다
 *   <li><b>안 찾아도 되는데 찾는 것</b> — 1단이 답을 주면 DB 를 건드리면 안 된다. 그게 캐시가
 *       있는 이유다 (D-014 — 캐시가 줄이는 것은 DB 조회다)
 *   <li><b>캐시가 죽으면 분류가 통째로 멈추는 것</b> — 조회 실패는 miss 로 떨어져야 2단이 받는다
 * </ol>
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*ClassificationReuseLookupTest'} (DB·스프링 없이 돈다)
 */
class ClassificationReuseLookupTest {

    private static final String KEY = "normalized-key";

    private ClassificationCache cache;
    private InquiryClassificationResultRepository resultRepository;
    private ClassificationReuseLookup lookup;

    @BeforeEach
    void setUp() {
        cache = mock(ClassificationCache.class);
        resultRepository = mock(InquiryClassificationResultRepository.class);
        lookup = new ClassificationReuseLookup(cache, resultRepository);

        when(cache.get(any())).thenReturn(Optional.empty());
        when(resultRepository.findLatestHumanConfirmed(any())).thenReturn(Optional.empty());
        when(resultRepository.findLatestAutoAccepted(any())).thenReturn(Optional.empty());
    }

    /** id 는 DB 가 채우는 값이라 테스트에서는 리플렉션으로 넣는다. */
    private static InquiryClassificationResult resultWithId(long id, InquiryCategory category,
            BigDecimal confidence, InquiryCategory finalCategory) {
        Inquiry inquiry = Inquiry.receive(1L, "본문", Channel.WEB, KEY, Instant.now());
        InquiryClassificationResult result = confidence == null
                ? InquiryClassificationResult.failed(inquiry, "m", "{}", 1)
                : InquiryClassificationResult.autoAccepted(inquiry, category, confidence, "m", "{}", 1);
        ReflectionTestUtils.setField(result, "id", id);
        if (finalCategory != null) {
            ReflectionTestUtils.setField(result, "finalCategory", finalCategory);
        }
        return result;
    }

    @Nested
    @DisplayName("1단 캐시")
    class FirstTier {

        @Test
        @DisplayName("캐시에 있으면 그걸 쓰고 DB 는 건드리지 않는다")
        void usesCacheAndSkipsDatabase() {
            CachedClassification cached =
                    CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.930"), 7L);
            when(cache.get(KEY)).thenReturn(Optional.of(cached));

            assertThat(lookup.find(KEY)).contains(cached);

            // 캐시가 줄이는 것은 DB 조회다 (D-014). 여기서 DB 를 또 치면 캐시가 아무 일도 안 한 것이다.
            verify(resultRepository, never()).findLatestHumanConfirmed(any());
            verify(resultRepository, never()).findLatestAutoAccepted(any());
        }

        @Test
        @DisplayName("캐시가 죽어도 2단 DB 로 넘어간다 — 분류가 통째로 멈추지 않는다")
        void fallsBackToDatabaseWhenCacheIsDown() {
            when(cache.get(KEY)).thenThrow(new RuntimeException("Redis 연결 실패"));
            when(resultRepository.findLatestHumanConfirmed(KEY)).thenReturn(
                    Optional.of(resultWithId(11L, InquiryCategory.PAYMENT, new BigDecimal("0.900"),
                            InquiryCategory.PAYMENT)));

            // 예외가 밖으로 나가면 캐시 장애가 verdict=FAILED 로 기록되고, 측정 2 의 사유별
            // 분포에 「AI 문제」와 「캐시 문제」가 섞인다.
            assertThat(lookup.find(KEY)).isPresent();
            verify(resultRepository).findLatestHumanConfirmed(KEY);
        }
    }

    @Nested
    @DisplayName("2단 DB — 순서가 전부다")
    class SecondTier {

        @Test
        @DisplayName("사람이 확정한 답이 있으면 그걸 쓰고 AI 답은 보지도 않는다")
        void prefersHumanConfirmed() {
            when(resultRepository.findLatestHumanConfirmed(KEY)).thenReturn(
                    Optional.of(resultWithId(21L, InquiryCategory.DELIVERY, new BigDecimal("0.930"),
                            InquiryCategory.RETURN_REFUND)));

            Optional<CachedClassification> found = lookup.find(KEY);

            assertThat(found).isPresent();
            // 사람이 고친 답(final_category)이 나와야 한다 — AI 가 제안한 category 가 아니다.
            assertThat(found.get().category()).isEqualTo(InquiryCategory.RETURN_REFUND);
            assertThat(found.get().source()).isEqualTo(CacheSource.HUMAN);
            // 사람은 확신도를 매기지 않는다 (D-033).
            assertThat(found.get().confidence()).isNull();
            assertThat(found.get().sourceResultId()).isEqualTo(21L);

            // 2순위는 아예 안 나간다 — 나가면 쿼리 하나가 그냥 낭비다.
            verify(resultRepository, never()).findLatestAutoAccepted(any());
        }

        @Test
        @DisplayName("사람 답이 없을 때만 AI 자동 확정 답을 쓴다")
        void fallsBackToAutoAccepted() {
            when(resultRepository.findLatestAutoAccepted(KEY)).thenReturn(
                    Optional.of(resultWithId(31L, InquiryCategory.PAYMENT, new BigDecimal("0.880"), null)));

            Optional<CachedClassification> found = lookup.find(KEY);

            assertThat(found).isPresent();
            assertThat(found.get().category()).isEqualTo(InquiryCategory.PAYMENT);
            assertThat(found.get().source()).isEqualTo(CacheSource.AI);
            // AI 답은 원본 확신도를 그대로 가져온다.
            assertThat(found.get().confidence()).isEqualByComparingTo("0.880");
            assertThat(found.get().sourceResultId()).isEqualTo(31L);

            verify(resultRepository).findLatestHumanConfirmed(KEY);
        }

        @Test
        @DisplayName("둘 다 없으면 비어 있다 — 부르는 쪽이 AI 를 호출한다")
        void emptyWhenNothingFound() {
            assertThat(lookup.find(KEY)).isEmpty();

            verify(resultRepository).findLatestHumanConfirmed(KEY);
            verify(resultRepository).findLatestAutoAccepted(KEY);
        }
    }
}
