package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.ErrorEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** 소유: P1 (수신·그룹핑). append-only 라 조회 요구가 거의 없다. */
public interface ErrorEventRepository extends JpaRepository<ErrorEvent, Long> {
}
