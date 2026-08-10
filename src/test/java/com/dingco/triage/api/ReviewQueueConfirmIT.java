package com.dingco.triage.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.service.ClassificationService;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code PATCH /api/inquiry-review-queue/{id}} 를 <b>목 없이</b> 끝까지 태운다 (TRI-60 후속,
 * CodeRabbit 리뷰 반영).
 *
 * <p>{@code ReviewQueueControllerTest} 는 {@code ReviewService} 를 {@code @MockBean} 으로 대체하고
 * {@code item} 도 직접 {@code new} 한 순수 객체를 쓴다 — 그래서 {@code ReviewConfirmResponse.from()}
 * 이 실제 프록시를 다루는지는 그 테스트로 증명되지 않는다. 여기서는 진짜 DB에서 {@code Inquiry}·
 * {@code InquiryClassificationResult} 를 LAZY 로 불러온 뒤 {@code ReviewService.confirm} 을 거쳐
 * 나온 실제 {@code InquiryReviewQueueItem} 을 컨트롤러가 그대로 응답으로 바꾸게 해서,
 * {@code ReviewConfirmResponse} 의 javadoc 주장(<i>"확정 안에서 이미 초기화됐으니 트랜잭션 밖에서
 * 읽어도 LazyInitializationException 이 안 난다"</i>)이 실제로 성립하는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ReviewQueueConfirmIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    @Autowired
    private ClassificationService classificationService;

    /** {@code givenPendingQueueItem}(ReviewServiceTest) 와 같은 방식 — 트랜잭션 ②의 정상 경로로 만든다. */
    private InquiryReviewQueueItem givenPendingQueueItem(InquiryCategory aiCategory) {
        Inquiry inquiry = inquiryRepository.save(Inquiry.receive(9300L, "PATCH 통합테스트 " + UUID.randomUUID(),
                Channel.WEB, UUID.randomUUID().toString(), Instant.now()));

        classificationService.verifyAndPersist(inquiry.getId(),
                AiParsedClassification.classified(aiCategory, new BigDecimal("0.400")),
                new AiRawResponse("claude-sonnet-5", "{}"), 1);

        return queueRepository.findByInquiryId(inquiry.getId()).getFirst();
    }

    @Test
    @DisplayName("PATCH 로 확정하면 목 없이도 응답이 계약대로 나간다 — LAZY 로딩이 트랜잭션 밖에서 안 터진다")
    void confirmThroughRealServiceReturnsContractShapedResponse() throws Exception {
        InquiryReviewQueueItem item = givenPendingQueueItem(InquiryCategory.DELIVERY);

        mockMvc.perform(patch("/api/inquiry-review-queue/{id}", item.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalCategory\":\"RETURN_REFUND\"}")
                        .header("X-User-Id", "7").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(item.getId()))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.inquiryId").value(item.getInquiry().getId()))
                .andExpect(jsonPath("$.inquiryStatus").value("CLASSIFIED"))
                .andExpect(jsonPath("$.suggestedCategory").value("DELIVERY"))
                .andExpect(jsonPath("$.finalCategory").value("RETURN_REFUND"))
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.agentId").value(7))
                .andExpect(jsonPath("$.resolvedAt").exists())
                // blind 규칙(D-010) — reason·confidence·threshold 는 실제 DB 값이 있어도 안 나가야 한다.
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.confidence").doesNotExist())
                .andExpect(jsonPath("$.threshold").doesNotExist());

        // DB 에도 실제로 반영됐는지 — 응답이 아니라 저장소를 다시 읽어 확인한다.
        Inquiry reloaded = inquiryRepository.findByIdForClassification(item.getInquiry().getId()).orElseThrow();
        assertThat(reloaded.getCurrentCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
    }
}
