package com.dingco.triage.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
 * <p><b>넣기는 두 가지다.</b>
 * <ul>
 *   <li>{@link #put} 은 <b>무조건 덮는 원시 연산</b>이다. ③(사람 확정, TRI-44)이 그대로 쓴다 —
 *       사람 답은 조건 없이 덮기 때문이다
 *   <li>{@link #putIfNotHuman} 은 ②(AI 자동 확정, TRI-53)가 쓰는 <b>조건부 넣기</b>다. 기존이
 *       사람 답이면 덮지 않으며, 보기와 쓰기를 Lua 로 <b>한 덩어리(원자적)</b>로 처리한다
 *       (GET→판단→SET 으로 쪼개면 그 사이에 ③이 넣은 사람 답을 ②가 덮는다, D-048 · TRI-84)
 * </ul>
 *
 * <p><b>evict 는 없다.</b> 정확성은 지우기가 아니라 사람 답의 덮어쓰기(③)가 지킨다. 무한히 쌓이는
 * 것은 {@code maxmemory} + {@code allkeys-lru} 로 막는다 — 이 상한은 {@code docker-compose.yml} 의
 * redis 설정으로 건다 (TRI-84). <b>TTL 로 상한을 대신하지 않는다</b>: 캐시가 비는 시점이 시계에
 * 달리면 측정 6·11 이 실행마다 다른 값을 낸다.
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

    /**
     * ②의 조건부 넣기 — <b>보기와 쓰기를 한 덩어리(원자적)로</b> 한다 (D-048).
     *
     * <p>기존 값이 사람 답({@code source=HUMAN})이면 덮지 않고, 아니면 덮는다. 이걸 GET→판단→SET
     * 으로 쪼개면 그 사이에 ③이 넣은 사람 답을 ②가 덮어버려 "덮어쓰기는 한 방향"(D-036) 규칙이
     * 그대로 뚫린다. Lua 스크립트 하나로 원자적으로 처리해 그 창을 없앤다.
     *
     * <p>기존 값은 JSON 이라 {@code cjson} 으로 {@code source} 만 본다. 사람 답으로 <b>확인되지
     * 않는 모든 경우</b>는 덮는다 — 파싱이 안 되거나({@code pcall} 실패), 파싱은 됐지만 객체가
     * 아니거나(다른 도구가 같은 키에 스칼라 {@code 123}·{@code "x"} 를 쓴 경우), 객체지만
     * {@code source} 가 HUMAN 이 아닌 경우. 깨진/낯선 값은 새 AI 값으로 자가 치유된다.
     * <b>{@code type(decoded) == 'table'} 가드가 없으면</b> 스칼라를 인덱싱하다 Lua 런타임 에러가
     * 호출자에게 그대로 올라가, 위 "자가 치유" 설명과 실제 동작이 어긋난다 (AI 리뷰 지적).
     * 반환: 썼으면 {@code 1}, 사람 답이라 안 썼으면 {@code 0}.
     */
    private static final RedisScript<Long> PUT_IF_NOT_HUMAN = RedisScript.of("""
            local cur = redis.call('GET', KEYS[1])
            if cur then
              local ok, decoded = pcall(cjson.decode, cur)
              if ok and type(decoded) == 'table' and decoded.source == 'HUMAN' then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private final RedisTemplate<String, CachedClassification> classificationCacheTemplate;

    /**
     * Lua 스크립트 실행용. 결과가 {@code Long}(0/1)이라 값 타입 템플릿의 값 직렬화기와 섞으면
     * 결과 역직렬화가 깨진다 — 키·인자·결과가 전부 단순 문자열/정수인 이 템플릿으로 분리한다.
     * 인자로 넘기는 JSON 은 {@link #classificationCacheTemplate} 이 쓰는 것과 같은 {@code ObjectMapper}
     * 로 만들어, {@link #get} 이 그대로 읽을 수 있다.
     */
    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

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
     * ("기존이 사람 답이면 덮지 않기")는 원자성이 필요해 별도 메서드 {@link #putIfNotHuman} 로 있다.
     */
    public void put(String normalizedKey, CachedClassification value) {
        classificationCacheTemplate.opsForValue().set(redisKey(normalizedKey), value);
    }

    /**
     * 값을 넣되 <b>기존이 사람 답({@code source=HUMAN})이면 덮지 않는다</b>. 보기와 쓰기를 원자적으로
     * 한 덩어리로 처리한다 (D-048). ②(AI 자동 확정, TRI-53)의 넣기가 이것이다.
     *
     * @return 실제로 썼으면 {@code true}, 기존 사람 답을 지키느라 안 썼으면 {@code false}
     */
    public boolean putIfNotHuman(String normalizedKey, CachedClassification value) {
        Long wrote = stringRedisTemplate.execute(
                PUT_IF_NOT_HUMAN, List.of(redisKey(normalizedKey)), writeJson(value));
        return wrote != null && wrote == 1L;
    }

    private String writeJson(CachedClassification value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 계약 C 값 구조는 JSON 직렬화가 항상 되는 단순 record 라, 여기 오면 매퍼 설정이
            // 깨진 것이다 — 조용히 삼키지 않는다.
            throw new IllegalStateException("캐시 값 직렬화 실패: " + value, e);
        }
    }

    /** 이름공간 + 규칙 번호를 붙인 실제 Redis 키. */
    static String redisKey(String normalizedKey) {
        return KEY_NAMESPACE + RULE_VERSION + ":" + normalizedKey;
    }
}
