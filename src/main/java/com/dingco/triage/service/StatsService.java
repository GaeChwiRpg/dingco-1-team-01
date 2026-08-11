package com.dingco.triage.service;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.config.MonitoringProperties;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository.VerdictCount;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository.AuditReviewRow;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository.ReasonCount;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository.SampledVerdictCount;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.Verdict;
import io.sentry.Sentry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 운영 통계 (계약 §7, {@code GET /api/stats}).
 *
 * <p><b>지금 상태 — {@code stuckReceived} · {@code backlog} · {@code classification} ·
 * {@code aiCallSavings} · {@code audit} 을 산출한다 (TRI-72 · TRI-67 · TRI-68).</b> 계약 §7 의
 * {@code cache} 블록만 아직 없다 — 1단(Redis) 캐시 자체는 있지만, 그 캐시를 실제로 찾아보는
 * 코드가 아직 어디에도 없어서(TRI-53) hit/miss 를 셀 지점이 없다. 여기서 없는 값을 지어내지 않는다
 * (CLAUDE.md "안 만든 것을 만든 것처럼 쓰지 않는다" · "본인 실측만").
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

    /** 캐시 이름은 값 타입마다 하나씩 나눈다 — 타입 지정 직렬화기를 쓰는 이유는 {@code CacheConfig} 참조. */
    public static final String BACKLOG_CACHE = "stats:summary:backlog";
    public static final String CLASSIFICATION_CACHE = "stats:summary:classification";
    public static final String AI_CALL_SAVINGS_CACHE = "stats:summary:aiCallSavings";
    public static final String AUDIT_CACHE = "stats:summary:audit";

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
     * <p><b>10초 캐시</b>({@code stats:summary:backlog}, {@code CacheConfig}). 매번 전수
     * COUNT/MIN 을 다시 돌지 않도록 짧게 담아둔다 — "변경 빈도 << 조회 빈도"에 해당한다(CLAUDE.md
     * 캐시 전략). TTL 뿐 아니라 <b>큐 삽입·확정 시점에 {@link #evictSummary()}</b> 도 함께 걸어
     * "방금 확정했는데 화면 숫자가 그대로"인 것처럼 보이는 지연을 없앤다 — 그쪽은 사람이 방금 한
     * 행동의 결과를 바로 확인하는 경로라 10초도 체감된다. 재사용 자동 확정에는 이 evict 를
     * 걸지 않는다(D-042) — 접수 경로마다 일어나 빈도가 너무 높아 걸면 캐시가 상시 비게 된다.
     */
    @Cacheable(BACKLOG_CACHE)
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
     * 판정 집계 요약 — 계약 §7 {@code classification} 블록 (TRI-68).
     *
     * <p>{@code backlog()} 와는 캐시 이름이 다르다({@code stats:summary:classification}) — 값
     * 타입마다 캐시 이름을 나누는 이유는 {@code CacheConfig} 참조(다형 타이핑 없이 타입 지정
     * 직렬화기를 쓰기 위함, CWE-502 회피).
     */
    @Cacheable(CLASSIFICATION_CACHE)
    public Classification classification() {
        Map<Verdict, Long> byVerdict = verdictCounts();
        long autoAccepted = byVerdict.getOrDefault(Verdict.AUTO_ACCEPTED, 0L)
                + byVerdict.getOrDefault(Verdict.REUSED, 0L);
        long needsReview = byVerdict.getOrDefault(Verdict.NEEDS_REVIEW, 0L);
        long failed = byVerdict.getOrDefault(Verdict.FAILED, 0L);
        long total = byVerdict.values().stream().mapToLong(Long::longValue).sum();
        double autoAcceptRate = total == 0 ? 0.0 : (double) autoAccepted / total;
        return new Classification(total, autoAccepted, needsReview, failed, autoAcceptRate);
    }

    /**
     * AI 절감률 — 계약 §7 {@code aiCallSavings} 블록 (TRI-68).
     *
     * <p><b>이 값이 줄이는 것은 AI 호출이지 DB 조회가 아니다 (D-014).</b> {@code cache} 블록(1단
     * Redis hit)과 별개 지표인 이유가 이것이다 — 캐시가 miss 여도 2단(DB)에 같은 정규화 키의
     * 지난 결과가 있으면 AI 를 부르지 않으므로, hit rate 는 항상 이 절감률 이하다.
     *
     * <p><b>실제 AI 호출 건수는 새 카운터를 두지 않고 판정 행에서 그대로 읽는다.</b>
     * {@code verdict = REUSED} 만 AI 를 안 부른 경로다(D-033) — 나머지 세 판정
     * ({@code AUTO_ACCEPTED}·{@code NEEDS_REVIEW}·{@code FAILED})은 전부 최소 한 번은 AI 를
     * 불렀기에 생긴 행이다. 재시도로 여러 번 부른 것까지 세지 않는 이유는 이 값이 답하려는
     * 질문이 "재사용으로 몇 건을 아꼈나"이지 "네트워크 호출이 총 몇 번 나갔나"가 아니기
     * 때문이다 — 후자는 {@code attempt_count} 합산으로 별도로 낼 수 있지만 지금 계약은
     * 전자만 요구한다.
     *
     * <p>분모는 {@link InquiryRepository#countAll()} — 아직 분류를 기다리는(RECEIVED) 문의도
     * 포함한다. "받은 문의 대비 얼마나 아꼈나"를 보는 값이라 분류 완료 여부로 분모를 좁히지 않는다.
     */
    @Cacheable(AI_CALL_SAVINGS_CACHE)
    public AiCallSavings aiCallSavings() {
        long inquiriesReceived = inquiryRepository.countAll();
        Map<Verdict, Long> byVerdict = verdictCounts();
        long reused = byVerdict.getOrDefault(Verdict.REUSED, 0L);
        long total = byVerdict.values().stream().mapToLong(Long::longValue).sum();
        long aiCallsMade = total - reused;
        double savingsRate = rate(inquiriesReceived - aiCallsMade, inquiriesReceived);
        return new AiCallSavings(inquiriesReceived, aiCallsMade, savingsRate);
    }

    /**
     * 감사 대조 — 계약 §7 {@code audit} 블록 (TRI-68 · D-012 · D-033). <b>이 프로젝트의 결론이
     * 나오는 자리다</b> — {@code autoAccepted.byConfidenceBucket} 이 "AI 가 0.85 라고 신고한
     * 것들의 실제 정확도"를 답한다.
     *
     * <p><b>두 블록을 합치지 않는다.</b> {@code autoAccepted} 는 "AI 답 vs 사람 답" 비교이지만
     * {@code reused} 에는 비교할 AI 답이 없다 — 재사용된 건이기 때문이다. 합치면 측정 8ⓐ가
     * 오염된다(CLAUDE.md 캐시 전략).
     */
    @Cacheable(AUDIT_CACHE)
    public Audit audit() {
        Map<Verdict, Long> eligibleByVerdict = verdictCounts();
        Map<Verdict, Long> sampledByVerdict = new EnumMap<>(Verdict.class);
        for (SampledVerdictCount count : queueRepository.countAuditSampledByVerdict()) {
            sampledByVerdict.put(count.getVerdict(), count.getCount());
        }

        List<AuditReviewRow> autoAcceptedRows = new ArrayList<>();
        List<AuditReviewRow> reusedRows = new ArrayList<>();
        for (AuditReviewRow row : queueRepository.findResolvedAuditSampleRows()) {
            if (row.getVerdict() == Verdict.AUTO_ACCEPTED) {
                autoAcceptedRows.add(row);
            } else if (row.getVerdict() == Verdict.REUSED) {
                reusedRows.add(row);
            }
        }

        AutoAcceptedAudit autoAccepted = buildAutoAcceptedAudit(
                eligibleByVerdict.getOrDefault(Verdict.AUTO_ACCEPTED, 0L),
                sampledByVerdict.getOrDefault(Verdict.AUTO_ACCEPTED, 0L),
                autoAcceptedRows);
        VerdictAudit reused = buildVerdictAudit(
                eligibleByVerdict.getOrDefault(Verdict.REUSED, 0L),
                sampledByVerdict.getOrDefault(Verdict.REUSED, 0L),
                reusedRows);

        return new Audit(classificationProperties.audit().sampleRate().doubleValue(), autoAccepted, reused);
    }

    private AutoAcceptedAudit buildAutoAcceptedAudit(
            long eligibleTotal, long sampledTotal, List<AuditReviewRow> reviewedRows) {
        long reviewed = reviewedRows.size();
        long mismatched = countMismatched(reviewedRows);
        List<ConfidenceBucket> buckets = confidenceBuckets(reviewedRows);
        return new AutoAcceptedAudit(eligibleTotal, sampledTotal, rate(sampledTotal, eligibleTotal),
                reviewed, mismatched, rate(mismatched, reviewed), buckets);
    }

    private VerdictAudit buildVerdictAudit(
            long eligibleTotal, long sampledTotal, List<AuditReviewRow> reviewedRows) {
        long reviewed = reviewedRows.size();
        long mismatched = countMismatched(reviewedRows);
        return new VerdictAudit(eligibleTotal, sampledTotal, rate(sampledTotal, eligibleTotal),
                reviewed, mismatched, rate(mismatched, reviewed));
    }

    private static long countMismatched(List<AuditReviewRow> rows) {
        return rows.stream()
                .filter(row -> row.getCategory() != row.getFinalCategory())
                .count();
    }

    /**
     * 신뢰도를 0.1 폭 구간으로 나눠 구간별 정확도를 낸다. {@code confidence} 는
     * {@code AUTO_ACCEPTED} 라 항상 존재한다(D-022) — null 검사를 하지 않는다.
     *
     * <p><b>{@code double} 대신 {@link BigDecimal} 로 경계를 계산한다.</b> 부동소수로
     * {@code 0.1} 을 반복 곱하면 오차가 쌓여 {@code 0.7999999...} 같은 값이 구간 경계를
     * 흔들 수 있다 — {@code confidence} 자체가 {@code BigDecimal} 인 이유(D-034)와 같다.
     * 데이터에 실제로 있는 구간만 담는다 — 비어 있는 구간을 0 으로 채워 넣지 않는다.
     */
    private static List<ConfidenceBucket> confidenceBuckets(List<AuditReviewRow> reviewedRows) {
        Map<BigDecimal, List<AuditReviewRow>> byLowerBound = new TreeMap<>();
        for (AuditReviewRow row : reviewedRows) {
            byLowerBound.computeIfAbsent(bucketLowerBound(row.getConfidence()), k -> new ArrayList<>())
                    .add(row);
        }
        List<ConfidenceBucket> buckets = new ArrayList<>();
        for (Map.Entry<BigDecimal, List<AuditReviewRow>> entry : byLowerBound.entrySet()) {
            BigDecimal lower = entry.getKey();
            BigDecimal upper = lower.add(new BigDecimal("0.1"));
            long reviewed = entry.getValue().size();
            long mismatched = countMismatched(entry.getValue());
            buckets.add(new ConfidenceBucket(lower + "-" + upper, reviewed, mismatched,
                    rate(reviewed - mismatched, reviewed)));
        }
        return buckets;
    }

    /** 구간 하한 — {@code 1.000} 은 최상위 구간({@code 0.9-1.0})에 포함시킨다. */
    private static BigDecimal bucketLowerBound(BigDecimal confidence) {
        BigDecimal tenths = confidence.multiply(BigDecimal.TEN).setScale(0, RoundingMode.FLOOR);
        if (tenths.compareTo(BigDecimal.TEN) >= 0) {
            tenths = BigDecimal.valueOf(9);
        }
        return tenths.movePointLeft(1).setScale(1);
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private Map<Verdict, Long> verdictCounts() {
        Map<Verdict, Long> byVerdict = new EnumMap<>(Verdict.class);
        for (VerdictCount count : resultRepository.countByVerdict()) {
            byVerdict.put(count.getVerdict(), count.getCount());
        }
        return byVerdict;
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
     *
     * <p><b>캐시 이름 4개를 전부 비운다.</b> 값 타입마다 캐시 이름을 나눴으므로(CacheConfig,
     * CWE-502 회피) 하나만 비우면 나머지 세 블록은 옛 값을 계속 돌려준다.
     */
    public void evictSummary() {
        try {
            for (String cacheName : List.of(BACKLOG_CACHE, CLASSIFICATION_CACHE, AI_CALL_SAVINGS_CACHE, AUDIT_CACHE)) {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            }
        } catch (RuntimeException e) {
            Sentry.captureException(e);
            log.warn("stats_summary_evict_failed", e);
        }
    }

    /**
     * 계약 §7 {@code backlog} 블록의 값 구조. {@code byReason} 에 없는 사유는 0건이라 키 자체가
     * 없다 — 0 으로 채워 넣지 않는다(CLAUDE.md "안 만든 것을 만든 것처럼 쓰지 않는다"와 같은 정신,
     * 여기서는 "일어나지 않은 것을 일어난 것처럼 채우지 않는다").
     */
    public record Backlog(long total, Map<QueueReason, Long> byReason, Instant oldestPendingAt) {
    }

    /**
     * 계약 §7 {@code classification} 블록의 값 구조. {@code autoAccepted} 는
     * {@code AUTO_ACCEPTED} 와 {@code REUSED} 를 합친 값이다 — 둘 다 사람 손을 안 거치고
     * 자동으로 확정된 판정이라 "자동 확정" 이라는 계약상 의미로는 같은 부류다.
     */
    public record Classification(
            long inquiriesTotal, long autoAccepted, long needsReview, long failed, double autoAcceptRate) {
    }

    /**
     * 계약 §7 {@code aiCallSavings} 블록의 값 구조. {@code savingsRate} 는
     * {@code 1 - aiCallsMade / inquiriesReceived} — 캐시 {@code hitRate}(D-014) 와는 다른 지표다.
     */
    public record AiCallSavings(long inquiriesReceived, long aiCallsMade, double savingsRate) {
    }

    /**
     * 계약 §7 {@code audit} 블록의 값 구조. {@code configuredSampleRate} 는 두 하위 블록이
     * 공유한다 — 감사 표본 비율은 {@code autoAccepted}/{@code reused} 를 가리지 않고 단일값이다
     * (D-005).
     */
    public record Audit(double configuredSampleRate, AutoAcceptedAudit autoAccepted, VerdictAudit reused) {
    }

    /**
     * {@code audit.autoAccepted}. {@code byConfidenceBucket} 이 이 프로젝트의 결론이 나오는 자리다 —
     * "AI 가 X 라고 신고한 것들의 실제 정확도".
     */
    public record AutoAcceptedAudit(long eligibleTotal, long sampledTotal, double actualSampleRate,
            long reviewed, long mismatched, double misclassificationRate,
            List<ConfidenceBucket> byConfidenceBucket) {
    }

    /**
     * {@code audit.reused}. {@code byConfidenceBucket} 이 없다 — 재사용 건은 비교할 AI 확신도가
     * 없다(사람 답을 재사용한 것은 {@code confidence} 자체가 null, D-033).
     */
    public record VerdictAudit(long eligibleTotal, long sampledTotal, double actualSampleRate,
            long reviewed, long mismatched, double misclassificationRate) {
    }

    /** 신뢰도 구간 1개의 대조 결과. {@code range} 는 {@code "0.8-0.9"} 형식. */
    public record ConfidenceBucket(String range, long reviewed, long mismatched, double actualAccuracy) {
    }
}
