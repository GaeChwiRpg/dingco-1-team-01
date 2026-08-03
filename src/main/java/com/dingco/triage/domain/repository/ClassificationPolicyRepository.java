package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.ClassificationPolicy;
import com.dingco.triage.domain.type.ErrorCategory;
import org.springframework.data.jpa.repository.JpaRepository;

/** 소유: P2 (임계값 조회) / P3 (정책 API). PK 가 카테고리 enum 이다. */
public interface ClassificationPolicyRepository
        extends JpaRepository<ClassificationPolicy, ErrorCategory> {
}
