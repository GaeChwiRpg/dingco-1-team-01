package com.dingco.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.support.MySqlTestContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 측정 5ⓔ 당일에 마이그레이션이 깨지는 상황을 미리 막는다.
 *
 * <p>V2 는 평소 기동에서 적용되지 않으므로(=평소엔 아무도 실행하지 않으므로) 문법 오류가
 * 있어도 측정을 시작하는 순간까지 드러나지 않는다. 그날 부팅이 실패하면 A/B 를 못 하고,
 * 못 하면 후보 인덱스를 근거 없이 승격하거나 근거 없이 폐기하게 된다 — D-018 이 지목한
 * "가장 하기 쉬운 실수"로 되돌아가는 경로다.
 *
 * <p>{@code BaselineSmokeTest} 와 property 가 달라 별도 컨텍스트·별도 컨테이너로 뜬다.
 * 같은 DB 를 공유하면 실행 순서에 따라 "후보 인덱스 없음" 검증이 흔들리기 때문에 의도한 분리다.
 */
@SpringBootTest(properties = "spring.flyway.target=2")
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class CandidateIndexMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("flyway.target=2 로 올리면 후보 인덱스 2개가 붙는다")
    void candidateIndexesApplyCleanly() {
        assertThat(countIndexColumns("idx_error_group_status_category_count"))
                .as("(status, current_category, occurrence_count DESC) 3컬럼")
                .isEqualTo(3);
        assertThat(countIndexColumns("idx_error_group_status_category_last_seen"))
                .as("(status, current_category, last_seen_at) 3컬럼")
                .isEqualTo(3);
    }

    private int countIndexColumns(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'error_group' "
                        + "AND index_name = ?",
                Integer.class, indexName);
        return count == null ? 0 : count;
    }
}
