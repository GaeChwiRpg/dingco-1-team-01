package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.service.cache.CacheSource;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.service.cache.ClassificationCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 *   <li><b>재사용을 껐는데 반만 꺼지는 것</b> — 1단·2단이 <b>함께</b> 꺼져야 한다. 한쪽만 꺼지면
 *       절감이 반만 남아 측정 1·8ⓐ-1 의 조건이 애매해진다 (TRI-90 · D-062)
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
        lookup = lookupWithReuse(true);

        when(cache.get(any())).thenReturn(Optional.empty());
        when(resultRepository.findLatestHumanConfirmed(any())).thenReturn(Optional.empty());
        when(resultRepository.findLatestAutoAccepted(any())).thenReturn(Optional.empty());
    }

    /** 기준값·감사 비율은 이 클래스와 무관하다 — 재사용 조회는 판정 전에 일어난다. */
    private ClassificationReuseLookup lookupWithReuse(boolean enabled) {
        ClassificationProperties properties = new ClassificationProperties(
                new BigDecimal("0.8"),
                new ClassificationProperties.Audit(new BigDecimal("0.05")),
                new ClassificationProperties.Reuse(enabled));
        return new ClassificationReuseLookup(cache, resultRepository, properties, new SimpleMeterRegistry());
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
            // 계약 §7 cache.hits 가 세는 지점이 정확히 여기다 (TRI-68).
            assertThat(lookup.cacheHitCount()).isEqualTo(1);
            assertThat(lookup.cacheMissCount()).isZero();
        }

        @Test
        @DisplayName("캐시가 죽어도 2단 DB 로 넘어간다 — 분류가 통째로 멈추지 않는다")
        void fallsBackToDatabaseWhenCacheIsDown() {
            // TRI-85 후: 캐시가 죽으면 ClassificationCache 가 스스로 miss(빈 결과)로 강등한다
            // (fail-open). 소비 측은 그 빈 결과를 받아 2단 DB 로 이어가야 한다 — 확인 대상이
            // 「예외 전파」에서 「빈 결과 처리」로 바뀌었을 뿐, 검증하려는 성질은 같다.
            when(cache.get(KEY)).thenReturn(Optional.empty());
            when(resultRepository.findLatestHumanConfirmed(KEY)).thenReturn(
                    Optional.of(resultWithId(11L, InquiryCategory.PAYMENT, new BigDecimal("0.900"),
                            InquiryCategory.PAYMENT)));

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
            // 1단이 비었으면 2단 결과와 무관하게 miss 다 — hit rate 는 1단만의 결과다 (D-014).
            assertThat(lookup.cacheMissCount()).isEqualTo(1);
            assertThat(lookup.cacheHitCount()).isZero();
        }
    }

    /**
     * 측정 1·8ⓐ-1 을 잴 때 쓰는 스위치 (TRI-90 · D-062).
     *
     * <p><b>「찾을 게 있는데도 안 찾는다」를 확인한다.</b> 아무것도 없는 상태로 껐다 켜면 결과가
     * 양쪽 다 비어 있어서 <b>스위치를 지워도 통과하는 테스트</b>가 된다 — 그래서 1단·2단 모두
     * 답을 준비해 두고 그것을 지나치는지 본다.
     */
    @Nested
    @DisplayName("재사용을 끄면")
    class WhenDisabled {

        @BeforeEach
        void bothTiersHaveAnswers() {
            lookup = lookupWithReuse(false);

            when(cache.get(KEY)).thenReturn(Optional.of(
                    CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.930"), 7L)));
            when(resultRepository.findLatestHumanConfirmed(KEY)).thenReturn(
                    Optional.of(resultWithId(41L, InquiryCategory.PAYMENT, new BigDecimal("0.900"),
                            InquiryCategory.PAYMENT)));
            when(resultRepository.findLatestAutoAccepted(KEY)).thenReturn(
                    Optional.of(resultWithId(42L, InquiryCategory.PAYMENT, new BigDecimal("0.900"), null)));
        }

        @Test
        @DisplayName("1단도 2단도 보지 않는다 — 둘 중 하나만 꺼지면 절감이 반만 남는다")
        void looksAtNeitherTier() {
            // 켜져 있었다면 1단에서 바로 답이 나왔을 상태다.
            assertThat(lookup.find(KEY)).isEmpty();

            verify(cache, never()).get(any());
            verify(resultRepository, never()).findLatestHumanConfirmed(any());
            verify(resultRepository, never()).findLatestAutoAccepted(any());
            // 안 찾아본 것은 miss 가 아니다 — 껐을 때는 카운터도 안 움직여야 hit rate 의 뜻이
            // 스위치 상태에 따라 달라지지 않는다.
            assertThat(lookup.cacheHitCount()).isZero();
            assertThat(lookup.cacheMissCount()).isZero();
        }

        @Test
        @DisplayName("도로 켜면 다시 찾는다 — 끄는 것이 데이터를 지우는 게 아니다")
        void findsAgainWhenReEnabled() {
            // 꺼진 동안에도 쓰기는 계속되므로(D-062 ⓓ) 켜는 순간 그대로 재사용된다.
            // 이게 성립해야 측정 11 의 hit rate 를 「켠 뒤 구간」에서 읽을 수 있다.
            assertThat(lookupWithReuse(true).find(KEY)).isPresent();
        }
    }
}
