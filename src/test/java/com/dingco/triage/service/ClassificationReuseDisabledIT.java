package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.cache.CacheSource;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.service.cache.ClassificationCache;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 재사용을 <b>껐을 때 무엇이 멈추고 무엇이 계속 도는지</b> 고정한다 (TRI-90 · D-062).
 *
 * <p><b>단위 테스트로는 못 보는 것을 본다.</b> {@code ClassificationReuseLookupTest} 는 스위치를
 * 생성자로 넣어주므로 <b>설정 파일의 값이 그 자리까지 실제로 도달하는지는 확인하지 못한다.</b>
 * 여기서는 진짜 설정({@code classification.reuse.enabled=false})으로 앱을 띄우고 실 MySQL 에
 * 재사용할 답을 심어둔 뒤, 그것을 지나치는지 본다.
 *
 * <p><b>「끄는 것은 읽기뿐」이 이 테스트의 핵심이다 (D-062 ⓓ).</b> 넣기까지 꺼지면 캐시가 빈 채로
 * 남아 <b>다시 켠 직후 구간의 hit rate 가 실제보다 낮게 나오는데</b>, 측정 11 이 바로 그 숫자를
 * 읽는다. 그래서 「안 찾는다」와 「그래도 넣는다」를 <b>같은 실행에서</b> 확인한다 — 따로 보면
 * 한쪽만 맞는 상태를 놓친다.
 *
 * <p><b>캐시를 모의 객체로 두는 이유</b> — 이 프로파일에는 Redis 가 없어서 진짜 캐시는 넣기가
 * 실패한다. 그러면 「넣기를 시도했는지」와 「넣기가 성공했는지」가 구분되지 않는다. 여기서 볼
 * 것은 <b>스위치가 넣기 경로를 건드리지 않았다는 사실</b>이므로 호출 여부만 본다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*ClassificationReuseDisabledIT'}
 */
@SpringBootTest(properties = {
        "classification.reuse.enabled=false",
        // 감사에 뽑히든 말든 이 테스트의 관심사가 아니다. 켜두면 20번에 한 번 큐 행이 늘어
        // 실패 원인이 흐려진다 — application-test.yml 이 이미 0 이지만 의도를 여기 적어둔다.
        "classification.audit.sample-rate=0"})
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ClassificationReuseDisabledIT {

    @Autowired
    private ClassificationReuseLookup reuseLookup;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    /** 넣기가 <b>불렸는지</b>만 본다. 실제 Redis 동작은 이 테스트의 관심사가 아니다. */
    @MockBean
    private ClassificationCache cache;

    private Inquiry givenReceivedInquiry(String normalizedKey) {
        return inquiryRepository.save(Inquiry.receive(
                9400L, "재사용스위치 " + UUID.randomUUID(), Channel.WEB, normalizedKey, Instant.now()));
    }

    /**
     * 같은 키로 이미 자동 확정된 답을 심는다 — 켜져 있었다면 2단이 이걸 찾아 재사용한다.
     *
     * <p><b>이 한 줄이 트랜잭션 ②를 실제로 돌린다</b> — 판정 저장 + 커밋 후 캐시 넣기까지. 이름이
     * {@code given...} 이라 준비만 하는 것처럼 보이지만, {@code stillWritesToCacheWhileDisabled}
     * 에서는 <b>이것이 재는 대상 그 자체</b>다. AI 리뷰가 "넣기를 유발하는 코드가 없다"고 읽은
     * 자리라 적어둔다 — 없었다면 그 테스트의 {@code verify} 가 통과할 수 없다.
     */
    private void givenAutoAcceptedAnswerFor(String normalizedKey) {
        Inquiry earlier = givenReceivedInquiry(normalizedKey);
        classificationService.verifyAndPersist(
                earlier.getId(),
                AiParsedClassification.classified(InquiryCategory.DELIVERY, new BigDecimal("0.930")),
                new AiRawResponse("claude-sonnet-5", "{\"category\":\"DELIVERY\",\"confidence\":0.93}"),
                1);
    }

    @Test
    @DisplayName("DB 에 재사용할 답이 있어도 찾지 않는다 — 설정값이 조회까지 실제로 도달한다")
    void doesNotReuseEvenWhenAnswerExists() {
        String key = UUID.randomUUID().toString();
        givenAutoAcceptedAnswerFor(key);

        // 켜져 있었다면 2단 2순위가 이걸 찾는다. 심어둔 답이 실제로 DB 에 있다는 것부터 단언한다 —
        // 안 그러면 「없어서 못 찾은 것」과 「꺼서 안 찾은 것」이 구분되지 않아, 스위치를 지워도
        // 통과하는 테스트가 된다.
        assertThat(resultRepository.findLatestAutoAccepted(key)).isPresent();

        // 바로 윗줄과 이 줄을 붙여 읽으면 그것이 곧 「2단이 안 돌았다」는 증거다 — 돌았다면
        // 같은 쿼리가 같은 답을 줘서 비어 있을 수가 없다. 그래서 2단은 따로 verify 하지 않는다
        // (AI 리뷰 제안). 저 리포지토리는 진짜 빈이라 verify 자체가 안 되기도 한다.
        assertThat(reuseLookup.find(key)).isEmpty();

        // 1단은 위와 같은 방식으로 못 본다 — 캐시를 모의 객체로 뒀으니 읽었어도 빈 값이라
        // 결과가 같다. 그래서 여기만 호출 여부로 확인한다. 이 단언이 뜻을 가지는 이유는
        // 이 경로가 실제로 find 를 지나기 때문이다 — find 를 부르지 않는 테스트에 두면
        // 캐시를 읽는 코드가 거기밖에 없어서 스위치를 지워도 통과한다 (헌법 감사 지적).
        verify(cache, never()).get(any());
    }

    @Test
    @DisplayName("꺼도 캐시에 넣기는 계속한다 — 안 그러면 다시 켠 직후 hit rate 가 낮게 나온다")
    void stillWritesToCacheWhileDisabled() {
        String key = UUID.randomUUID().toString();

        givenAutoAcceptedAnswerFor(key);

        // ② 커밋 후의 넣기(TRI-53)는 스위치와 무관하다. 이게 멈추면 꺼진 동안 채워지는 것이
        // 하나도 없어서, 측정 11 을 「켠 뒤 구간」에서 읽을 수 없게 된다 (D-062 ⓓ).
        ArgumentCaptor<CachedClassification> put = ArgumentCaptor.forClass(CachedClassification.class);
        verify(cache).putIfNotHuman(eq(key), put.capture());
        assertThat(put.getValue().source()).isEqualTo(CacheSource.AI);
        assertThat(put.getValue().category()).isEqualTo(InquiryCategory.DELIVERY);
    }

    @Test
    @DisplayName("꺼도 판정·저장(②)은 그대로다 — 스위치가 끄는 것은 조회뿐이다")
    void stillPersistsVerdict() {
        String key = UUID.randomUUID().toString();
        Inquiry inquiry = givenReceivedInquiry(key);

        classificationService.verifyAndPersist(
                inquiry.getId(),
                AiParsedClassification.classified(InquiryCategory.PAYMENT, new BigDecimal("0.910")),
                new AiRawResponse("claude-sonnet-5", "{\"category\":\"PAYMENT\",\"confidence\":0.91}"),
                1);

        InquiryClassificationResult result = resultRepository
                .findByInquiryIdOrderByCreatedAtDesc(inquiry.getId()).stream().findFirst().orElseThrow();
        assertThat(result.getVerdict()).isEqualTo(Verdict.AUTO_ACCEPTED);
        assertThat(result.getCategory()).isEqualTo(InquiryCategory.PAYMENT);
    }
}
