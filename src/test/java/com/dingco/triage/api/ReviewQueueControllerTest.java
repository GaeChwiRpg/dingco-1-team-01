package com.dingco.triage.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.config.SecurityConfig;
import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.ConflictCode;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.service.ContentMasker;
import com.dingco.triage.service.ReviewQueryService;
import com.dingco.triage.service.ReviewService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TRI-56 — {@code GET /api/inquiry-review-queue} 가 계약(API-CONTRACT §4)대로 응답하고,
 * blind 규칙(D-010)을 실제로 지키는지 확인한다. 역할별 401/403 매핑표 검증은
 * {@link com.dingco.triage.config.SecurityConfigTest} 가 이미 한다 — 여기서 반복하지 않는다.
 */
@WebMvcTest(controllers = ReviewQueueController.class)
@Import({SecurityConfig.class, ContentMasker.class})
class ReviewQueueControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReviewQueryService reviewQueryService;

    @MockBean
    private ReviewService reviewService;

    private InquiryReviewQueueItem queueItem(String content, Long inquiryId, Long itemId,
            InquiryCategory suggestedCategory) {
        Inquiry inquiry = Inquiry.receive(5001L, content, Channel.WEB, "nk-1", NOW);
        ReflectionTestUtils.setField(inquiry, "id", inquiryId);
        InquiryClassificationResult result = suggestedCategory == null
                ? InquiryClassificationResult.failed(inquiry, "claude-sonnet-5", "{broken", 3)
                : InquiryClassificationResult.autoAccepted(inquiry, suggestedCategory,
                        new BigDecimal("0.950"), "claude-sonnet-5", "{}", 1);
        InquiryReviewQueueItem item = InquiryReviewQueueItem.from(result);
        ReflectionTestUtils.setField(item, "id", itemId);
        ReflectionTestUtils.setField(item, "createdAt", NOW);
        return item;
    }

    /** {@code ReviewService.confirm} 이 돌려주는, 이미 확정 처리된 항목을 흉내낸다. */
    private InquiryReviewQueueItem resolvedQueueItem(InquiryCategory suggestedCategory,
            InquiryCategory finalCategory, Long agentId) {
        InquiryReviewQueueItem item = queueItem("환불해주세요", 4471L, 902L, suggestedCategory);
        item.getClassificationResult().recordFinalCategory(finalCategory);
        item.resolve(agentId, NOW);
        item.getInquiry().confirmByAgent(finalCategory);
        return item;
    }

    @Test
    @DisplayName("응답 항목은 계약 필드(id·inquiryId·content·suggestedCategory·status·createdAt)만 담고, "
            + "reason·confidence·threshold 는 어떤 형태로도 안 나간다 (blind, D-010)")
    void responseExposesOnlyContractFields() throws Exception {
        InquiryReviewQueueItem item = queueItem("환불해주세요 010-1234-5678", 4471L, 902L,
                InquiryCategory.RETURN_REFUND);
        given(reviewQueryService.search(any(), any(), any(), anyInt(), anyInt()))
                .willReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/inquiry-review-queue")
                        .header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(902))
                .andExpect(jsonPath("$.content[0].inquiryId").value(4471))
                .andExpect(jsonPath("$.content[0].content").value("환불해주세요 [연락처]"))
                .andExpect(jsonPath("$.content[0].suggestedCategory").value("RETURN_REFUND"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].reason").doesNotExist())
                .andExpect(jsonPath("$.content[0].confidence").doesNotExist())
                .andExpect(jsonPath("$.content[0].threshold").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("본문은 저장된 원문이 아니라 마스킹된 값으로 나간다 — 응답에서 계산한다 (D-040)")
    void contentIsMaskedOnTheWayOut() throws Exception {
        InquiryReviewQueueItem item = queueItem("주문번호 20260803-771234 확인해주세요", 1L, 1L,
                InquiryCategory.DELIVERY);
        given(reviewQueryService.search(any(), any(), any(), anyInt(), anyInt()))
                .willReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/api/inquiry-review-queue")
                        .header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("주문번호 [주문번호] 확인해주세요"));
    }

    @Test
    @DisplayName("CLASSIFY_FAILED 항목은 suggestedCategory 가 null 로 그대로 나간다 — false 로 치환하지 않는다 (D-022)")
    void suggestedCategoryIsNullForClassifyFailed() throws Exception {
        InquiryReviewQueueItem item = queueItem("문의합니다", 5L, 6L, null);
        given(reviewQueryService.search(any(), any(), any(), anyInt(), anyInt()))
                .willReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/api/inquiry-review-queue")
                        .header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].suggestedCategory").value(nullValue()));
    }

    @Test
    @DisplayName("status 파라미터를 생략하면 PENDING 이 기본값이다")
    void defaultsToPendingStatus() throws Exception {
        given(reviewQueryService.search(any(), any(), any(), anyInt(), anyInt()))
                .willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/inquiry-review-queue")
                        .header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk());

        ArgumentCaptor<QueueStatus> statusCaptor = ArgumentCaptor.forClass(QueueStatus.class);
        verify(reviewQueryService).search(statusCaptor.capture(), any(), any(), anyInt(), anyInt());
        assertThat(statusCaptor.getValue()).isEqualTo(QueueStatus.PENDING);
    }

    @Test
    @DisplayName("size 가 100 을 넘으면 400 VALIDATION_FAILED — 서비스는 호출되지 않는다")
    void rejectsSizeOver100() throws Exception {
        mockMvc.perform(get("/api/inquiry-review-queue").param("size", "101")
                        .header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        org.mockito.Mockito.verifyNoInteractions(reviewQueryService);
    }

    @Test
    @DisplayName("size 가 0 이하면 400 VALIDATION_FAILED — PageRequest.of 의 IllegalArgumentException 이 500 으로 새지 않는다")
    void rejectsSizeUnderOne() throws Exception {
        mockMvc.perform(get("/api/inquiry-review-queue").param("size", "0")
                        .header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        org.mockito.Mockito.verifyNoInteractions(reviewQueryService);
    }

    @Test
    @DisplayName("page 가 음수면 400 VALIDATION_FAILED — PageRequest.of 의 IllegalArgumentException 이 500 으로 새지 않는다")
    void rejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/inquiry-review-queue").param("page", "-1")
                        .header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        org.mockito.Mockito.verifyNoInteractions(reviewQueryService);
    }

    @Test
    @DisplayName("category·reason·confidence·threshold 파라미터는 애초에 컨트롤러가 받지 않는다 (blind, D-010)")
    void doesNotAcceptBlindQueryParameters() throws Exception {
        // 알 수 없는 쿼리 파라미터는 Spring MVC 가 조용히 무시한다 — 여기서 검증하는 건 그 값이
        // service 호출로 전달되지 않는다는 것이다(전달할 파라미터 자체가 컨트롤러 시그니처에 없다).
        given(reviewQueryService.search(any(), any(), any(), anyInt(), anyInt()))
                .willReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/inquiry-review-queue")
                        .param("category", "RETURN_REFUND")
                        .param("reason", "AUDIT_SAMPLE")
                        .param("confidence", "0.9")
                        .param("threshold", "0.8")
                        .header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk());

        verify(reviewQueryService).search(org.mockito.ArgumentMatchers.eq(QueueStatus.PENDING),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("확정 — AI 제안과 사람 확정이 같으면 matched: true (TRI-60)")
    void confirmReturnsMatchedTrueWhenSameCategory() throws Exception {
        given(reviewService.confirm(eq(902L), anyLong(), eq(InquiryCategory.DELIVERY)))
                .willReturn(resolvedQueueItem(InquiryCategory.DELIVERY, InquiryCategory.DELIVERY, 7L));

        mockMvc.perform(patch("/api/inquiry-review-queue/902")
                        .contentType("application/json")
                        .content("{\"finalCategory\":\"DELIVERY\"}")
                        .header("X-User-Id", "7").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(902))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.inquiryId").value(4471))
                .andExpect(jsonPath("$.inquiryStatus").value("CLASSIFIED"))
                .andExpect(jsonPath("$.suggestedCategory").value("DELIVERY"))
                .andExpect(jsonPath("$.finalCategory").value("DELIVERY"))
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.agentId").value(7));
    }

    @Test
    @DisplayName("확정 — AI 제안과 사람 확정이 다르면 matched: false (TRI-60)")
    void confirmReturnsMatchedFalseWhenDifferentCategory() throws Exception {
        given(reviewService.confirm(eq(902L), anyLong(), eq(InquiryCategory.RETURN_REFUND)))
                .willReturn(resolvedQueueItem(InquiryCategory.DELIVERY, InquiryCategory.RETURN_REFUND, 7L));

        mockMvc.perform(patch("/api/inquiry-review-queue/902")
                        .contentType("application/json")
                        .content("{\"finalCategory\":\"RETURN_REFUND\"}")
                        .header("X-User-Id", "7").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false));
    }

    @Test
    @DisplayName("확정 — AI 제안이 없으면(CLASSIFY_FAILED) matched 는 null 이지 false 가 아니다 (D-022)")
    void confirmReturnsMatchedNullWhenNoSuggestion() throws Exception {
        given(reviewService.confirm(eq(902L), anyLong(), eq(InquiryCategory.DELIVERY)))
                .willReturn(resolvedQueueItem(null, InquiryCategory.DELIVERY, 7L));

        mockMvc.perform(patch("/api/inquiry-review-queue/902")
                        .contentType("application/json")
                        .content("{\"finalCategory\":\"DELIVERY\"}")
                        .header("X-User-Id", "7").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedCategory").value(nullValue()))
                .andExpect(jsonPath("$.matched").value(nullValue()));
    }

    @Test
    @DisplayName("확정 — finalCategory 가 없으면 400 VALIDATION_FAILED, 서비스는 호출되지 않는다")
    void confirmRejectsMissingFinalCategory() throws Exception {
        mockMvc.perform(patch("/api/inquiry-review-queue/902")
                        .contentType("application/json")
                        .content("{}")
                        .header("X-User-Id", "7").header("X-User-Role", "AGENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        org.mockito.Mockito.verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("확정 — finalCategory 가 enum 10종 밖이면 400 VALIDATION_FAILED")
    void confirmRejectsUnknownCategory() throws Exception {
        mockMvc.perform(patch("/api/inquiry-review-queue/902")
                        .contentType("application/json")
                        .content("{\"finalCategory\":\"REFUND_XYZ\"}")
                        .header("X-User-Id", "7").header("X-User-Role", "AGENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        org.mockito.Mockito.verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("확정 — 이미 처리된 항목이면 409 ALREADY_RESOLVED 가 그대로 응답으로 나간다 (배선 확인)")
    void confirmPropagatesConflictFromService() throws Exception {
        given(reviewService.confirm(eq(902L), anyLong(), any()))
                .willThrow(new ConflictException(ConflictCode.ALREADY_RESOLVED, 902L, "이미 처리된 항목입니다."));

        mockMvc.perform(patch("/api/inquiry-review-queue/902")
                        .contentType("application/json")
                        .content("{\"finalCategory\":\"DELIVERY\"}")
                        .header("X-User-Id", "7").header("X-User-Role", "AGENT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RESOLVED"))
                .andExpect(jsonPath("$.reviewQueueItemId").value(902));
    }
}
