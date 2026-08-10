package com.dingco.triage.service.event;

import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.service.cache.ClassificationCache;
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
 * {@code StatsService#evictSummary()}와 달리 여기서는 캐시 갱신 실패를 별도로
 * try-catch로 처리하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ReviewConfirmedEventListener {

    private final ClassificationCache classificationCache;

    @TransactionalEventListener
    public void onReviewConfirmed(ReviewConfirmedEvent event) {
        classificationCache.put(event.normalizedKey(),
                CachedClassification.ofHuman(event.finalCategory(), event.resultId()));
        log.debug("classification_cache_overwritten_by_human normalizedKey={} resultId={}",
                event.normalizedKey(), event.resultId());
    }
}
