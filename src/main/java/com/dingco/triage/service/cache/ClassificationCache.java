package com.dingco.triage.service.cache;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 2단 절감 경로의 <b>1단 캐시</b> — 정규화 키로 지난 분류 결과를 찾는다 (TRI-40 · 계약 C · D-036).
 *
 * <p><b>이 캐시가 줄이는 것은 DB 조회이지 AI 호출이 아니다 (D-014).</b> 캐시가 miss 여도 DB(2단)에
 * 같은 키의 지난 결과가 있으면 AI 를 부르지 않는다. 그래서 hit rate 와 AI 절감률은 별개 메트릭이고,
 * hit rate 는 항상 절감률 이하다. 이 클래스는 그중 <b>1단(Redis)</b> 만 담당한다 — 2단(DB)은
 * {@code InquiryClassificationResultRepository}(TRI-41)다.
 *
 * <p><b>키에 규칙 번호를 접두어로 붙인다</b> ({@code v1:}). 정규화 규칙은 측정 6 을 보고 조이기로
 * 이미 정해져 있어(D-031 재평가), 번호가 없으면 규칙을 조여도 옛 규칙으로 만든 답이 계속
 * 재사용된다. <b>번호 승격과 캐시 장애 대응(캐시가 죽어도 접수·분류는 계속)은 TRI-85</b> 가
 * 이 위에 얹는다.
 *
 * <p><b>이 판(TRI-40)이 제공하는 것은 {@link #get} 과 조건 없는 {@link #put} 둘뿐이다.</b>
 * <ul>
 *   <li>{@code put} 은 <b>무조건 덮는 원시 연산</b>이다. ③(사람 확정, TRI-44)이 그대로 쓴다 —
 *       사람 답은 조건 없이 덮기 때문이다
 *   <li>②(AI 자동 확정, TRI-53)가 쓸 <b>"사람 답이면 덮지 않는" 조건부 넣기는 여기 없다.</b>
 *       그 검사는 보기와 쓰기를 <b>한 덩어리(원자적)</b>로 해야 하는데(GET→판단→SET 으로 쪼개면
 *       그 사이에 ③이 넣은 사람 답을 ②가 덮는다, D-048), 그 원자적 구현은 <b>TRI-84</b> 가 붙인다
 * </ul>
 *
 * <p><b>evict 는 없다.</b> 정확성은 지우기가 아니라 사람 답의 덮어쓰기(③)가 지킨다. 무한히 쌓이는
 * 것은 {@code maxmemory} + {@code allkeys-lru} 로 막는다 — 이 상한도 TRI-84 다. <b>TTL 로 상한을
 * 대신하지 않는다</b>: 캐시가 비는 시점이 시계에 달리면 측정 6·11 이 실행마다 다른 값을 낸다.
 */
@Component
@RequiredArgsConstructor
public class ClassificationCache {

    /**
     * 다른 캐시(예: {@code stats:summary})와 키가 섞이지 않게 하는 이름공간.
     *
     * <p>6 공통 필수 기능 매핑의 캐시 이름 {@code classification:byNormalizedKey} 를 그대로 쓴다.
     */
    static final String KEY_NAMESPACE = "classification:byNormalizedKey:";

    /**
     * 정규화 규칙 번호. 규칙을 바꿀 때 이 번호를 올리면 옛 규칙으로 만든 답이 자동으로 안 잡힌다.
     *
     * <p><b>이 상수의 승격은 TRI-85 소관이다</b> — 여기서는 첫 규칙을 {@code v1} 으로 못박아 두기만
     * 한다. TRI-85 가 붙으면 규칙 변경 시 번호를 올리고 옛 답이 안 나오는지 확인한다.
     */
    static final String RULE_VERSION = "v1";

    private final RedisTemplate<String, CachedClassification> classificationCacheTemplate;

    /**
     * 같은 키의 지난 결과를 찾는다.
     *
     * @return 있으면 값, 없으면 {@link Optional#empty()}
     */
    public Optional<CachedClassification> get(String normalizedKey) {
        return Optional.ofNullable(
                classificationCacheTemplate.opsForValue().get(redisKey(normalizedKey)));
    }

    /**
     * 값을 <b>조건 없이</b> 넣는다 (덮어쓰기 포함).
     *
     * <p>③(사람 확정)의 넣기가 이것이다 — 사람 답은 조건 없이 덮는다 (D-036). ②의 조건부 넣기
     * ("기존이 사람 답이면 덮지 않기")는 원자성이 필요해 <b>TRI-84</b> 에서 별도 메서드로 붙는다.
     */
    public void put(String normalizedKey, CachedClassification value) {
        classificationCacheTemplate.opsForValue().set(redisKey(normalizedKey), value);
    }

    /** 이름공간 + 규칙 번호를 붙인 실제 Redis 키. */
    static String redisKey(String normalizedKey) {
        return KEY_NAMESPACE + RULE_VERSION + ":" + normalizedKey;
    }
}
