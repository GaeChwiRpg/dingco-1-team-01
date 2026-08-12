package com.dingco.triage.service;

import com.dingco.triage.config.ClassificationProperties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 자동으로 확정된 건 중 일부를 몰래 뽑아 사람이 다시 보게 한다 (TRI-64 · D-005 · D-012 · D-033).
 *
 * <p><b>이 시스템의 검증은 두 겹이다.</b> 1차는 확신도가 기준값 미만이면 격리하는 것이고,
 * 2차가 이것이다 — <b>기준값을 넘겨 자동 확정된 건</b>도 일부를 다시 본다. 1차만 있으면
 * <b>확신하면서 틀린 건은 영원히 발견되지 않는다.</b>
 *
 * <p><b>뽑는 대상은 「AI 가 답한 것」이 아니라 「자동으로 확정된 것」이다 (D-033).</b> 그래서
 * 재사용({@code REUSED})도 대상이다. 자동 확정은 하나 틀리면 그 문의 하나가 틀리지만,
 * <b>재사용은 맨 처음 답 하나가 틀리면 같은 내용의 모든 문의가 틀린다.</b> 게다가 원본이 사람
 * 답이면 "사람이 정했다"는 사실이 신뢰의 근거가 되어 아무도 의심하지 않는다 — 오히려 더 위험하다.
 *
 * <p><b>판단을 한 곳에 모으는 이유</b>: 뽑는 규칙이 여러 자리에 흩어지면 어느 경로가 얼마나
 * 뽑았는지 알 수 없게 되고, 그러면 측정 8ⓐ-2("5% 감사가 오분류를 몇 건 집어냈나")의 분모를
 * 믿을 수 없다.
 *
 * <p><b>여기서 하는 일은 판단뿐이다.</b> 뽑힌 건을 큐에 넣는 것은 트랜잭션 ②의 몫이고
 * (TRI-65), <b>반드시 그 트랜잭션 안에서</b> 일어나야 한다 — 밖으로 빼면 감사율이 설정값
 * 미달이 되어 오분류율의 분모가 조용히 줄어든다 (D-012).
 */
@Service
public class AuditSamplingPolicy {

    private final double sampleRate;

    /**
     * 난수원. <b>테스트에서 갈아끼울 수 있게 분리했다</b> — 무작위가 고정되지 않으면
     * "뽑혔다/안 뽑혔다"를 테스트로 못 박을 수 없고, 그러면 이 클래스는 확인할 방법이 없는
     * 코드가 된다 (TRI-64 「끝났다고 볼 조건」).
     */
    private final DoubleSupplier randomSource;

    /**
     * <b>{@code @Autowired} 를 빼면 기동이 실패한다.</b> 생성자가 둘이면 스프링은 어느 쪽으로
     * 만들지 정하지 못하고 기본 생성자를 찾다가 {@code No default constructor found} 로 터진다
     * (실제로 겪었다). 애노테이션 하나가 "빈으로 만들 때는 이쪽"을 가리킨다.
     */
    @Autowired
    public AuditSamplingPolicy(ClassificationProperties properties) {
        // 기본 난수원은 ThreadLocalRandom 이다. 분류는 여러 스레드에서 동시에 돌기 때문에
        // (D-047) 하나의 Random 인스턴스를 공유하면 그 안에서 스레드끼리 경합한다.
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** 테스트에서 난수를 고정할 때 쓴다. */
    AuditSamplingPolicy(ClassificationProperties properties, DoubleSupplier randomSource) {
        this.sampleRate = properties.audit().sampleRate().doubleValue();
        this.randomSource = randomSource;
    }

    /**
     * 이 건을 감사 표본으로 뽑을지 정한다.
     *
     * <p><b>무엇을 판정하는지는 부르는 쪽이 정한다.</b> 이 메서드는 판정 종류를 보지 않는다 —
     * 「자동 확정된 것만 여기까지 온다」는 것을 트랜잭션 ②의 {@code switch} 가 이미 보장하고,
     * 여기서 한 번 더 검사하면 <b>같은 규칙이 두 곳에 생겨</b> 나중에 한쪽만 고쳐진다.
     *
     * <p>비율 비교는 {@code double} 로 한다. 확신도와 달리 이 값은 <b>경계에서 판정이 뒤집히는
     * 자리가 아니다</b> — 0.05 가 0.050000000000000003 이어도 20건에 1건이라는 사실은 같다.
     *
     * @return 뽑혔으면 {@code true}. 비율이 {@code 0} 이면 항상 {@code false} 이고,
     *         {@code 1} 이면 항상 {@code true} 다 ({@code nextDouble()} 이 {@code [0,1)} 이라
     *         경계가 정확히 맞는다)
     */
    public boolean shouldSample() {
        return randomSource.getAsDouble() < sampleRate;
    }
}
