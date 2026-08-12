package com.dingco.triage.service.event;

import com.dingco.triage.config.AsyncConfig;
import com.dingco.triage.service.ClassificationReuseLookup;
import com.dingco.triage.service.ClassificationService;
import com.dingco.triage.service.ContentMasker;
import com.dingco.triage.service.cache.CachedClassification;
import java.util.Optional;
import com.dingco.triage.service.ai.ClassifyAttempt;
import com.dingco.triage.service.ai.RetryingAiClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 접수 신호를 받아 다른 스레드에서 분류를 진행한다 (TRI-47 · 계약 A · D-058).
 *
 * <p><b>이건 {@link InquiryReceivedEvent} 를 받는 자리다.</b> 다른 코드가 불러 쓰는 것이 아니라
 * 접수가 커밋되면 저절로 도는 문이라, 이름에 "무엇을 하나" 대신 <b>"누가 부르나"</b> 를 담았다
 * (D-058). 문서·용어집의 「분류 담당」이 이것이다.
 *
 * <p><b>하는 일은 두 가지다</b> — AI 분류를 호출하고, 그 결과를 저장 묶음 ②
 * ({@link ClassificationService#verifyAndPersist})에 넘긴다. 판정도 저장도 여기서 하지 않는다.
 *
 * <p><b>직접 호출하지 않는다.</b> 부르면 접수 저장(①)과 분류 저장(②)이 <b>한 트랜잭션으로
 * 붙어</b> D-031 이 금지한 상태가 된다 — ②가 실패할 때 ①까지 되돌아가서 고객이 이미 받은 접수
 * 확인이 거짓말이 된다. 그래서 이 클래스는 패키지 밖에서 보이지 않게 두었다. 다만 <b>같은
 * 패키지 안에서는 여전히 부를 수 있으므로</b> 컴파일러가 전부 막아주지는 않는다.
 *
 * <p><b>여기에는 트랜잭션이 없다.</b> {@code @Async} 로 넘어온 이 메서드는 접수 트랜잭션과 아무
 * 관계가 없고, 저장 묶음 ②는 {@code verifyAndPersist} 안에서 새로 열린다.
 *
 * <p><b>갈래는 둘이다 — 재사용이 먼저다 (D-031).</b>
 *
 * <pre>
 * 재사용 조회 ─ 찾음 ─→ AI 를 부르지 않고 REUSED 로 ②
 *      ↓ 못 찾음
 * 가리기 → AI 호출 → 값 검증 → ②
 * </pre>
 *
 * <p>찾는 순서(1단 캐시 → 2단 DB 1순위 → 2순위)는 {@link ClassificationReuseLookup} 안에 있다.
 * 여기서 다시 쓰지 않는 이유는 <b>같은 순서가 두 곳에 있으면 어긋나기 때문</b>이다.
 *
 * <p><b>재사용은 꺼져 있을 수 있다</b> ({@code classification.reuse.enabled=false}, TRI-90 · D-062).
 * 그때는 조회가 <b>항상 비어서</b> 모든 문의가 아래 AI 갈래로 간다 — 측정 1·8ⓐ-1 을 잴 때 쓰는
 * 상태다. <b>여기에는 그 분기가 없다.</b> 스위치를 저 안에 둔 이유는 1단·2단이 <b>함께</b> 꺼져야
 * 하기 때문이고, 여기서 끄면 캐시 조회만 건너뛰고 DB 조회가 남는 <b>반만 꺼진 상태</b>를 만들 수
 * 있다. 꺼져 있어도 <b>저장·캐시 넣기는 그대로 돈다</b> (D-062 ⓓ).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class InquiryReceivedEventListener {

    /**
     * 같은 내용의 답이 이미 있는지 찾는다 (TRI-47). <b>AI 호출보다 먼저 부른다</b> — 이게 이
     * 시스템이 AI 호출을 아끼는 유일한 경로다 (D-031).
     */
    private final ClassificationReuseLookup reuseLookup;

    private final ContentMasker contentMasker;

    /**
     * <b>별도 빈이어야 재시도가 걸린다.</b> {@code @Retryable} 은 스프링이 그 클래스를 대신
     * 감싸주는 방식이라, 같은 클래스 안에서 자기 메서드를 부르면 통째로 무시된다.
     */
    private final RetryingAiClassifier retryingAiClassifier;

    private final ClassificationService classificationService;

    /**
     * 접수된 문의 1건을 분류한다.
     *
     * <p><b>커밋된 뒤에 받는다</b> ({@code AFTER_COMMIT}). 접수가 되돌아가면 신호도 나가지 않으므로
     * 저장되지 않은 문의를 분류하는 일이 없다.
     *
     * <p><b>실행기를 이름으로 지정한다.</b> 이름을 빠뜨리면 스프링 기본 실행기로 가는데, 그쪽에는
     * 종료 대기도 포화 정책도 없어서 <b>배포할 때마다 처리 중이던 문의가 버려진다</b> (D-047).
     *
     * <p>예외를 밖으로 던지지 않는다. 던져봐야 받을 사람이 없고({@code @Async} 는 반환값을 안 쓴다),
     * 그러면 문의가 {@code RECEIVED} 인 채로 남아 아무도 모르게 된다. AI 를 못 부른 것도
     * <b>판정 결과의 하나로 기록</b>한다.
     */
    @Async(AsyncConfig.CLASSIFY_EXECUTOR)
    @TransactionalEventListener
    public void onInquiryReceived(InquiryReceivedEvent event) {
        // ── 재사용 조회가 AI 호출보다 먼저다 (D-031 2단 절감 경로).
        //
        // 찾으면 AI 를 아예 부르지 않는다. 순서(1단 캐시 → 2단 DB 1순위 → 2순위)는 저 안에
        // 가둬 뒀다 — 여기서 순서를 다시 쓰면 두 곳이 어긋날 수 있다.
        Optional<CachedClassification> reusable = reuseLookup.find(event.normalizedKey());
        if (reusable.isPresent()) {
            // 마스킹도 건너뛴다 — 가리는 이유는 AI 로 내보내기 위해서인데 안 내보낸다.
            classificationService.persistReuse(event.inquiryId(), reusable.get());
            return;
        }

        // 개인정보를 가리고 보낸다. 가리는 규칙은 정규화와 같은 구현을 쓴다 (D-040) —
        // 두 벌이면 한쪽만 조여져서 화면에는 가려지는데 프롬프트에는 남는다.
        String maskedContent = contentMasker.mask(event.content());

        // 호출·검증·재시도가 전부 저 안에서 끝난다 (TRI-54). 여기서 try-catch 를 하지 않는
        // 이유는, 실패도 「판정 결과의 하나」로 돌아오기 때문이다 — 재시도를 다 쓴 건은
        // @Recover 가 FAILED 로 만들어 보낸다.
        //
        // ⚠️ 다른 빈을 통해 부르는 것이 재시도가 걸리는 조건이다. 이 클래스 안으로 옮겨
        // 자기 메서드를 부르면 프록시를 안 타서 @Retryable 이 통째로 무시된다 —
        // 예외도 로그도 없이 한 번만 호출된다.
        ClassifyAttempt attempt = retryingAiClassifier.classify(event.inquiryId(), maskedContent);

        // 판정·저장·큐 삽입은 전부 저 안에서 한 덩어리로 일어난다 (트랜잭션 ②).
        // raw 가 null 일 수 있다 — 응답을 못 받았으면 남길 원문이 없다.
        classificationService.verifyAndPersist(
                event.inquiryId(), attempt.parsed(), attempt.raw(), attempt.attemptCount());
    }
}
