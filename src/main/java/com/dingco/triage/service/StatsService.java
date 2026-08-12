package com.dingco.triage.service;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.config.MonitoringProperties;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository.VerdictCount;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository.ReasonCount;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.Verdict;
import io.sentry.Sentry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 운영 통계 (계약 §7, {@code GET /api/stats}).
 *
 * <p><b>지금 상태 — {@code stuckReceived} 와 {@code backlog} 를 산출한다 (TRI-72 · TRI-67).</b>
 * 계약 §7 의 응답은 {@code aiCallSavings} · {@code cache} · {@code audit} 까지 담지만, 그 블록들은
 * 각각 다른 측정의 소유이고 그 측정이 끝나는 대로 <b>이 서비스에 증분으로</b> 붙는다. 여기서 없는
 * 값을 지어내지 않는다 (CLAUDE.md "안 만든 것을 만든 것처럼 쓰지 않는다" · "본인 실측만").
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 단일 read 는 트랜잭션 경계의 이득보다 비용이
 * 크다 (CLAUDE.md {@code @Transactional} 위치 규칙). 묶음 read+write 인 ①②③ 세 메서드에만 붙는다.
 *
 * <p><b>시각을 {@link Clock} 으로 주입받는다.</b> {@code stuckReceived} 는 "접수 후 임계 시간을
 * 넘겼는가"를 <b>지금 시각 기준</b>으로 세므로, 테스트에서 시각을 고정하고 임계값을 초 단위로 낮춰야
 * D-017 을 검증할 수 있다 (엔티티가 {@code Instant.now()} 를 직접 부르지 않는 것과 같은 이유).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final String STATS_SUMMARY_CACHE = "stats:summary";

    private final InquiryRepository inquiryRepository;
    private final InquiryReviewQueueRepository queueRepository;
    private final InquiryClassificationResultRepository resultRepository;
    private final MonitoringProperties monitoringProperties;
    private final ClassificationProperties classificationProperties;
    private final CacheManager cacheManager;
    private final Clock clock;

    /**
     * {@code stuckReceived} — 접수 후 임계 시간({@code monitoring.stuck-received-threshold})을
     * 넘겨 아직 {@code RECEIVED} 인 문의 수 (D-017).
     *
     * <p>경계(cutoff)를 여기서 계산해 레포지토리에 넘긴다: {@code now - threshold} 이전에 접수됐는데
     * 아직 {@code RECEIVED} 면 유실 의심 건이다. <b>0 이 아니면 분류 파이프라인이 실패 중</b>이라는
     * 뜻이다 — {@code GET /api/stats} 와 Actuator gauge {@code triage.inquiries.stuck_received}
     * 양쪽이 이 값을 읽는다.
     */
    public long stuckReceivedCount() {
        Instant cutoff = Instant.now(clock).minus(monitoringProperties.stuckReceivedThreshold());
        return inquiryRepository.countStuckReceived(cutoff);
    }

    /**
     * 검토 큐 적체(backlog) 요약 — 계약 §7 {@code backlog} 블록 (TRI-67).
     *
     * <p><b>10초 캐시</b>({@code stats:summary}, {@code CacheConfig}). 매번 전수 COUNT/MIN 을
     * 다시 돌지 않도록 짧게 담아둔다 — "변경 빈도 << 조회 빈도"에 해당한다(CLAUDE.md 캐시 전략).
     * TTL 뿐 아니라 <b>큐 삽입·확정 시점에 {@code @CacheEvict(allEntries=true)}</b> 도 함께 걸어
     * "방금 확정했는데 화면 숫자가 그대로"인 것처럼 보이는 지연을 없앤다 — 그쪽은 사람이 방금 한
     * 행동의 결과를 바로 확인하는 경로라 10초도 체감된다. 재사용 자동 확정에는 이 evict 를
     * 걸지 않는다(D-042) — 접수 경로마다 일어나 빈도가 너무 높아 걸면 캐시가 상시 비게 된다.
     */
    @Cacheable(STATS_SUMMARY_CACHE)
    public Backlog backlog() {
        Map<QueueReason, Long> byReason = new EnumMap<>(QueueReason.class);
        for (ReasonCount count : queueRepository.countPendingByReason()) {
            byReason.put(count.getReason(), count.getCount());
        }
        long total = byReason.values().stream().mapToLong(Long::longValue).sum();
        Instant oldestPendingAt = queueRepository.findOldestPendingCreatedAt().orElse(null);
        return new Backlog(total, byReason, oldestPendingAt);
    }

    /**
     * 큐 삽입(②)·확정(③) 커밋 후에 부른다 — TRI-67 완료 조건. {@code @CacheEvict} 애노테이션을
     * 안 쓰는 이유는 두 가지다.
     *
     * <ol>
     *   <li>호출부(②의 {@code enqueueIfNeeded})가 <b>같은 클래스 안에서 자기 자신을 호출</b>하는
     *       사설(private) 메서드라 스프링 AOP 프록시를 안 거친다 — 애노테이션을 붙여도 안 먹는다</li>
     *   <li><b>실패해도 절대 삼켜야 한다.</b> 이 메서드는 문의 접수·분류·확정이라는 핵심
     *       트랜잭션의 커밋 후에 곁다리로 불린다 — Redis 가 잠깐 죽었다고 이미 끝난 핵심
     *       트랜잭션에 영향을 주면 안 된다. 통계 캐시는 부가 기능이고, 최악의 경우도 "10초 뒤에는
     *       어차피 새로 계산된다"일 뿐이다</li>
     * </ol>
     *
     * <p><b>호출부가 커밋 후(AFTER_COMMIT)인지가 중요하다.</b> 커밋 전에 비우면, 그 틈에 다른
     * 요청이 캐시 미스를 만나 <b>아직 커밋 안 된 옛 상태</b>를 다시 캐시에 채울 수 있다 — 그러면
     * 방금 커밋된 변경이 TTL(10초) 동안 반영 안 된 것처럼 보인다. 그래서 이 메서드는
     * {@code ClassificationService}·{@code ReviewService} 의 {@code @Transactional} 메서드
     * 안에서 직접 불리지 않고, 그 트랜잭션이 커밋된 뒤(각각 커밋 후 동기화·
     * {@code ReviewConfirmedEventListener})에만 불린다.
     *
     * <p>실패를 로그만 남기고 삼키지 않는다 — Sentry 로도 보낸다(D-030). catch 해서 아무것도
     * 안 하면 이 실패를 아는 사람이 로그를 직접 뒤진 사람뿐이라, Redis 장애가 조용히 지나간다.
     */
    public void evictSummary() {
        try {
            Cache cache = cacheManager.getCache(STATS_SUMMARY_CACHE);
            if (cache != null) {
                cache.clear();
            }
        } catch (RuntimeException e) {
            Sentry.captureException(e);
            log.warn("stats_summary_evict_failed", e);
        }
    }

    /**
     * 감사 장치 자체를 감사한다 — 설정한 비율만큼 실제로 뽑히고 있는지 (TRI-66 · D-012).
     *
     * <p>설정 비율({@code configuredSampleRate})과 실측 비율({@code actualSampleRate})을
     * 나란히 두어 표본 삽입 누락을 감지한다. 자동 확정과 재사용을 따로 내며(D-033),
     * {@code backlog}와 달리 캐시하지 않는다(D-042).
     *
     * <p>오분류 집계({@code reviewed}·{@code mismatched} 등)는 측정 8ⓐ의 몫이며,
     * 이 메서드는 그 측정의 분모가 믿을 만한지만 답한다.
     */
    public AuditRates auditRates() {
        Map<Verdict, Long> eligible = byVerdict(resultRepository.countAuditEligibleByVerdict());
        Map<Verdict, Long> sampled = byVerdict(resultRepository.countAuditSampledByVerdict());
        return new AuditRates(
                classificationProperties.audit().sampleRate(),
                sampling(eligible, sampled, Verdict.AUTO_ACCEPTED),
                sampling(eligible, sampled, Verdict.REUSED));
    }

    /** 판정별 집계를 맵으로. 행이 없는 판정은 키가 없고, 그 "없음"은 {@link #sampling} 이 0 으로 읽는다. */
    private Map<Verdict, Long> byVerdict(List<VerdictCount> counts) {
        Map<Verdict, Long> byVerdict = new EnumMap<>(Verdict.class);
        for (VerdictCount count : counts) {
            byVerdict.put(count.getVerdict(), count.getCount());
        }
        return byVerdict;
    }

    /**
     * 한 판정의 「뽑힐 수 있었던 수 · 뽑힌 수 · 실측 비율」.
     *
     * <p>모집단이 0 이면 비율을 {@code null} 로 둔다 — <b>{@code 0.0} 으로 채우지 않는다.</b>
     * 0.0 은 "뽑힐 게 있었는데 하나도 안 뽑혔다"라는 뜻이고 그건 표본 누락의 신호인데, 아직
     * 아무 건도 안 들어온 상태가 같은 얼굴로 보이면 <b>없는 장애를 보게 된다.</b> 확신도에
     * {@code 0} 을 안 채우는 규칙(D-022)과 같은 층위다.
     */
    private Sampling sampling(Map<Verdict, Long> eligible, Map<Verdict, Long> sampled, Verdict verdict) {
        long eligibleTotal = eligible.getOrDefault(verdict, 0L);
        long sampledTotal = sampled.getOrDefault(verdict, 0L);
        BigDecimal actualSampleRate = eligibleTotal == 0 ? null
                : BigDecimal.valueOf(sampledTotal)
                        .divide(BigDecimal.valueOf(eligibleTotal), 3, RoundingMode.HALF_UP);
        return new Sampling(eligibleTotal, sampledTotal, actualSampleRate);
    }

    /**
     * 계약 §7 {@code backlog} 블록의 값 구조. {@code byReason} 에 없는 사유는 0건이라 키 자체가
     * 없다 — 0 으로 채워 넣지 않는다(CLAUDE.md "안 만든 것을 만든 것처럼 쓰지 않는다"와 같은 정신,
     * 여기서는 "일어나지 않은 것을 일어난 것처럼 채우지 않는다").
     */
    public record Backlog(long total, Map<QueueReason, Long> byReason, Instant oldestPendingAt) {
    }

    /**
     * 계약 §7 {@code audit} 블록 중 <b>감사율</b> 부분의 값 구조 (TRI-66).
     *
     * <p>{@code configuredSampleRate} 를 두 블록 밖에 한 번만 둔다 — 설정값은 판정별로 다르지
     * 않다. 안에 넣으면 <b>판정마다 다른 비율을 줄 수 있는 것처럼</b> 보이는데, 그것은 폐기된
     * 카테고리별 차등(D-006)과 같은 오해를 부른다.
     */
    public record AuditRates(BigDecimal configuredSampleRate, Sampling autoAccepted, Sampling reused) {
    }

    /**
     * 한 판정의 감사율. <b>비율만 두지 않고 분자·분모를 함께 낸다</b> — 비율은 소수 셋째 자리에서
     * 반올림하므로 그것만으로는 검산이 안 되고, 무엇보다 <b>모집단이 몇 건인지 모르면 어긋남이
     * 유의미한지 판단할 수 없다.</b> 20건에서 1건 차이와 2000건에서 1건 차이는 다른 이야기다.
     *
     * @param actualSampleRate 모집단이 0 이면 {@code null} — 이유는 {@code sampling} 참조
     */
    public record Sampling(long eligibleTotal, long sampledTotal, BigDecimal actualSampleRate) {
    }
}
