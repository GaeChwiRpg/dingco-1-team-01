package com.dingco.triage.service;

import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.service.cache.ClassificationCache;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 같은 내용의 문의에 이미 나온 답이 있는지 찾는다 — <b>2단 절감 경로의 소비 측</b> (TRI-47 · D-031).
 *
 * <p><b>찾는 순서가 이 클래스의 전부다.</b>
 *
 * <pre>
 * 1단 캐시(Redis)   → 있으면 여기서 끝. AI 안 부름
 *      ↓ 없음
 * 2단 DB · 1순위    → 같은 키에서 <b>사람이 확정한</b> 답
 *      ↓ 없음
 * 2단 DB · 2순위    → 같은 키에서 <b>AI 가 자동 확정한</b> 답
 *      ↓ 없음
 * 비어 있음         → 부르는 쪽이 AI 를 호출한다
 * </pre>
 *
 * <p><b>순서를 여기 가둔 이유</b> — 리포지토리는 1순위·2순위 메서드를 나란히 제공할 뿐이라
 * <b>뒤집어 불러도 컴파일된다.</b> 그러면 사람이 정정한 답을 두고 AI 답을 쓰게 되는데(D-033),
 * 그건 감사가 잡아낸 정정을 시스템이 도로 무시하는 것이다. 순서를 한 곳에 모으고 테스트로
 * 고정해야 그 실수가 재발하지 않는다.
 *
 * <p><b>캐시가 줄이는 것은 DB 조회이지 AI 호출이 아니다 (D-014).</b> 1단이 비어 있어도 2단이
 * 답을 찾으면 AI 는 안 부른다. 그래서 {@code hit rate} 와 {@code AI 절감률} 은 <b>다른 숫자</b>이고
 * 각각 노출한다 — 이 클래스의 로그가 그 둘을 가르는 근거다.
 *
 * <p><b>재사용을 다시 재사용하지 않는다 (체인 금지, D-033).</b> 2순위가 {@code AUTO_ACCEPTED}
 * 등치라 {@code REUSED} 를 안 준다. 1순위가 주는 것은 사람 답이라 체인이 아니라 새 원본이다 —
 * 재사용 건에 {@code final_category} 가 있다는 것은 <b>감사로 뽑혀 사람이 다시 판단했다</b>는 뜻이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassificationReuseLookup {

    private final ClassificationCache cache;
    private final InquiryClassificationResultRepository resultRepository;

    /**
     * 재사용할 답을 찾는다.
     *
     * @return 있으면 재사용할 답, 없으면 {@link Optional#empty()} — 부르는 쪽이 AI 를 호출한다
     */
    public Optional<CachedClassification> find(String normalizedKey) {
        Optional<CachedClassification> cached = fromCache(normalizedKey);
        if (cached.isPresent()) {
            log.debug("reuse_hit tier=CACHE key={}", normalizedKey);
            return cached;
        }

        // 1순위 — 사람이 확정한 답. 사람 답이 AI 답보다 신뢰도가 높다 (D-033).
        //
        // 이게 없으면 같은 내용의 저확신 문의가 사람이 아무리 확정해도 계속 큐에 쌓인다 —
        // AI 호출만 아끼고 사람 노동은 하나도 못 아끼는 상태가 된다.
        Optional<InquiryClassificationResult> human =
                resultRepository.findLatestHumanConfirmed(normalizedKey);
        if (human.isPresent()) {
            log.debug("reuse_hit tier=DB_HUMAN key={} sourceResultId={}",
                    normalizedKey, human.get().getId());
            return human.map(r -> CachedClassification.ofHuman(r.getFinalCategory(), r.getId()));
        }

        // 2순위 — 1순위가 비었을 때만. 등치 조건이 REUSED 를 걸러 체인을 막는다.
        Optional<InquiryClassificationResult> ai =
                resultRepository.findLatestAutoAccepted(normalizedKey);
        if (ai.isPresent()) {
            log.debug("reuse_hit tier=DB_AI key={} sourceResultId={}",
                    normalizedKey, ai.get().getId());
            return ai.map(r -> CachedClassification.ofAi(
                    r.getCategory(), r.getConfidence(), r.getId()));
        }

        log.debug("reuse_miss key={}", normalizedKey);
        return Optional.empty();
    }

    /**
     * 1단 캐시 조회. <b>실패하면 못 찾은 것으로 본다.</b>
     *
     * <p><b>임시 조치다 (TRI-85 가 제대로 한다).</b> {@code ClassificationCache} 는 지금 Redis 가
     * 죽으면 예외를 던지는데, 그대로 두면 <b>캐시 장애가 분류 실패({@code verdict=FAILED})로
     * 기록된다.</b> 그러면 측정 2 의 사유별 분포에 「AI 문제」와 「캐시 문제」가 섞여서, 나중에
     * 분포를 봐도 무엇을 고쳐야 하는지 알 수 없다.
     *
     * <p>여기서 삼키는 대신 <b>2단 DB 조회가 그대로 남는다</b> — 캐시가 죽어도 절감은 유지되고
     * 조회가 조금 느려질 뿐이다. 로그를 남기는 이유는 이 상태가 <b>조용히 지나가면 안 되기</b>
     * 때문이다: 캐시가 계속 죽어 있으면 hit rate 가 0 인데 절감률은 살아 있는 모양이 되고,
     * 그건 D-014 가 두 지표를 나눠 놓은 이유와 정확히 맞닿는다.
     */
    private Optional<CachedClassification> fromCache(String normalizedKey) {
        try {
            return cache.get(normalizedKey);
        } catch (RuntimeException e) {
            // 로그만 찍고 끝내는 것이 아니다 — miss 로 떨어뜨려 2단으로 넘긴다는 처리를 한다 (D-030).
            log.warn("reuse_cache_unavailable key={} — 2단 DB 조회로 넘어간다 (TRI-85 전 임시 처리)",
                    normalizedKey, e);
            return Optional.empty();
        }
    }
}
