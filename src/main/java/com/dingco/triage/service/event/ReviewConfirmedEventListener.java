package com.dingco.triage.service.event;

import com.dingco.triage.service.StatsService;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.service.cache.ClassificationCache;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * <p><b>사람이 확정한 답으로 1단(Redis) 캐시를 덮어쓴다.</b> (TRI-44 · 계약 C · D-036).
 *
 * <p>캐시를 갱신하는 곳은 ②와 ③ 두 곳이다(D-036).
 *
 * <p>사람 답은 기존 값이 무엇이든 덮어쓰므로, 기존 값을 확인하거나 비교할 필요가 없다.
 * {@link ClassificationCache#put}은 기존 값을 확인하지 않고 SET으로 덮어쓴다.
 *
 * <p>반대로 AI 답은 사람이 확정한 답을 덮어쓰면 안 되므로, 사람이 확정한 결과가 있는지
 * 확인하고 AI 답을 저장하는 과정을 한 번에 처리한다.
 * {@link ClassificationCache#putIfNotHuman}은 이 과정을 Lua로 처리한다(D-048, TRI-53).
 *
 * <p><b>DB 커밋 후에 캐시를 갱신한다.</b> {@code @TransactionalEventListener(AFTER_COMMIT)}로
 * 실행되므로, 캐시 갱신 중 예외가 발생해도 이미 확정된 DB 결과는 롤백되지 않는다.
 *
 * <p><b>덮어쓰기가 실패해도 아래 통계 비우기는 계속한다 (CodeRabbit 지적).</b> 예외가 그대로
 * 올라가면 같은 메서드의 뒷줄이 통째로 건너뛰어져, <b>캐시 한 곳이 실패했다는 이유로 통계까지
 * 낡은 채 남는다.</b> 둘은 서로 독립이라 하나가 실패해도 다른 하나는 해야 한다.
 *
 * <p><b>삼키는 이유는 되돌릴 수단이 없어서다.</b> 커밋 후에 도는 자리라 예외를 올려도 사람이
 * 확정한 답이 되돌아가지 않는다. 대신 <b>캐시에는 옛 AI 답이 남고, 1단 캐시에는 evict 가 없어
 * TTL 만료까지 그 답이 계속 재사용된다</b> — D-036 이 막으려던 상황 그대로다. 그래서 조용히
 * 넘기지 않고 {@code Sentry.captureException} 으로 올린다 (D-030).
 *
 * <p>⚠️ <b>이 자리에서 자동 캡처가 도는지는 재보지 않았다.</b> 커밋 후 리스너의 예외를 스프링이
 * 어디까지 올려보내는지 확인 안 된 상태라, 자동 경로를 믿지 않고 직접 부른다
 * ({@code SENTRY-GUIDE.md} 2-2 — 두 경로 중 어느 쪽도 안 타는 사각지대가 실제로 있다).
 *
 * <p><b>통계 캐시({@code stats:summary}) 비우기도 여기서 한다.</b> {@code ReviewService.confirm()}
 * 은 {@code @Transactional} 이라, 그 안에서 직접 {@link StatsService#evictSummary()} 를 부르면
 * 커밋 전에 비우는 셈이 된다 — 그 틈에 다른 요청이 아직 커밋 안 된 옛 상태를 캐시에 다시 채울
 * 수 있다. 이 리스너는 커밋 후에만 실행되므로 그 창이 없다.
 *
 * <p>단, {@code ClassificationService} 쪽 큐 삽입(②)은 이 리스너를 거치지 않고
 * {@code TransactionSynchronization.afterCommit()} 으로 직접 {@code evictSummary()} 를 부른다.
 * 확정(③)만 이 리스너에서 처리하는 이유는, 확정은 이미 {@code ReviewConfirmedEvent} 라는
 * 도메인 이벤트가 있어 캐시 갱신과 통계 비우기를 한 곳에서 처리하는 게 자연스럽기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ReviewConfirmedEventListener {

    private final ClassificationCache classificationCache;
    private final StatsService statsService;

    @TransactionalEventListener
    public void onReviewConfirmed(ReviewConfirmedEvent event) {
        try {
            classificationCache.put(event.normalizedKey(),
                    CachedClassification.ofHuman(event.finalCategory(), event.resultId()));
            log.debug("classification_cache_overwritten_by_human normalizedKey={} resultId={}",
                    event.normalizedKey(), event.resultId());
        } catch (RuntimeException e) {
            // 여기서 막지 않으면 아래 통계 비우기까지 건너뛴다. 그리고 이 실패는 되돌릴 수 없이
            // 확정된 것이라(커밋 후) 캡처는 자동에 맡기지 않고 직접 부른다 — D-030.
            Sentry.captureException(e);
            log.warn("classification_cache_overwrite_failed normalizedKey={} resultId={}",
                    event.normalizedKey(), event.resultId(), e);
        }
        statsService.evictSummary();
    }
}
