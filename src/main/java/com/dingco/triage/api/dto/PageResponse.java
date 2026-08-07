package com.dingco.triage.api.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** 페이징 응답 공통 모양 — {@code {content, page, size, totalElements}} (API-CONTRACT §2·§4). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
