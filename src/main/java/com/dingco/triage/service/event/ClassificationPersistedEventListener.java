package com.dingco.triage.service.event;

import com.dingco.triage.service.cache.ClassificationCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 확정된 판정을 캐시에 넣는다 — <b>②의 넣기</b> (TRI-53 · 계약 C · D-036 · D-048).
 *
 * <p><b>사람 답은 덮지 않는다.</b> 그래서 조건 없이 쓰는 {@code put} 이 아니라
 * {@link ClassificationCache#putIfNotHuman} 을 쓴다. 덮어쓰기 방향은 한 방향이다 —
 * <b>사람 답은 AI 답을 덮고, AI 답은 사람 답을 덮지 않는다</b> (D-036).
 *
 * <p><b>보기와 쓰기를 쪼개지 않는 이유 (D-048)</b> — {@code GET → 판단 → SET} 으로 나누면
 * 그 사이에 ③(사람 확정)이 넣은 답을 ②가 덮어버린다. 확률은 낮지만 그때 사라지는 것은
 * <b>감사가 잡아낸 정정</b>이라, 없어지면 시스템이 그 정정을 도로 무시하게 된다. 저 메서드는
 * 그 판단을 Lua 한 덩어리로 처리한다.
 *
 * <p><b>커밋 후에만 넣는다.</b> ②가 롤백됐는데 캐시에 값이 남으면 <b>저장되지도 않은 판정이
 * 다음 문의로 재사용된다.</b>
 *
 * <p><b>여기서 실패해도 판정은 되돌리지 않는다.</b> 이미 커밋된 뒤이기도 하고, 캐시에 못 넣은
 * 것은 <b>다음 문의가 2단 DB 조회로 같은 답을 찾는다</b>는 뜻이라 정확성 문제가 아니라 속도
 * 문제다 (D-014 — 캐시가 줄이는 것은 DB 조회이지 AI 호출이 아니다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClassificationPersistedEventListener {

    private final ClassificationCache cache;

    /**
     * 판정을 캐시에 넣는다.
     *
     * <p><b>{@code @Async} 를 붙이지 않았다.</b> 넣기는 Redis 왕복 한 번이라 분류 스레드를 오래
     * 잡지 않고, 비동기로 넘기면 <b>캐시에 들어가기 전에 다음 문의가 조회해 miss 가 나는 창</b>이
     * 넓어진다. ③({@code ReviewConfirmedEventListener}, TRI-44)도 같은 방식이다.
     */
    @TransactionalEventListener
    public void onClassificationPersisted(ClassificationPersistedEvent event) {
        try {
            boolean wrote = cache.putIfNotHuman(event.normalizedKey(), event.value());
            if (!wrote) {
                // 안 쓴 것이 정상 동작이다 — 사람 답을 지킨 것이다 (D-036).
                // 그래도 남기는 이유: 이 로그가 없으면 "캐시에 왜 AI 답이 안 들어갔지"를
                // 나중에 설명할 수 없다.
                log.debug("cache_put_skipped reason=human_answer_kept key={}", event.normalizedKey());
            }
        } catch (RuntimeException e) {
            // 로그만 찍고 끝내는 것이 아니다 — "넣기를 포기하고 진행한다"는 처리를 한다 (D-030).
            // 판정은 이미 커밋됐고, 다음 문의는 2단 DB 조회로 같은 답을 찾는다.
            //
            // ⚠️ 이 상태가 계속되면 hit rate 는 0 인데 절감률은 살아 있는 모양이 된다.
            // 그 둘을 따로 노출하는 이유가 이것이다 (D-014). 캐시 장애 대응은 TRI-85.
            log.warn("cache_put_failed key={} — 판정은 저장됐고 다음 문의는 2단 DB 로 찾는다",
                    event.normalizedKey(), e);
        }
    }
}
