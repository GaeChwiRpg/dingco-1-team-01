package com.dingco.triage.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link InquiryRepository} 가 <b>소유자 없이 문의를 통째로 꺼내오는 메서드를 노출하지 않는지</b>
 * 고정한다 (TRI-88 · D-045(1)).
 *
 * <p>이 프로젝트의 권한 실패 모드는 "소유권 검사를 한 군데서 빠뜨리면 남의 문의가 나간다"이고,
 * 그걸 사람이 기억해 막는 대신 <b>메서드를 아예 안 만들어</b> 컴파일 단계에서 막기로 했다. 그 보증이
 * 유지되는지는 결국 "금지된 메서드가 상속되지 않았는가"이므로, 여기서 리플렉션으로 직접 확인한다.
 *
 * <p><b>회귀 시나리오</b>: 누군가 편의로 {@code extends JpaRepository} 로 되돌리면 {@code findById}
 * · {@code findAll} 등이 다시 상속돼 고객 경로에서 부를 수 있게 된다. 그 순간 이 테스트가 깨진다.
 *
 * <p>순수 리플렉션이라 스프링·DB 없이 돈다.
 */
class InquiryRepositoryGuardTest {

    /**
     * 상속되면 안 되는 소유자 없는 조회 메서드 이름. {@code save}(쓰기)와 {@code existsById}
     * (내용을 꺼내지 않는 boolean)는 여기 없다 — 둘은 허용된다.
     */
    private static final List<String> FORBIDDEN =
            List.of("findById", "findAll", "findAllById", "getReferenceById", "getById", "getOne");

    @Test
    @DisplayName("소유자 없이 문의를 꺼내오는 메서드가 저장소에 하나도 없다 (JpaRepository 복귀 시 깨짐)")
    void noOwnerlessInquiryFetchMethods() {
        List<String> exposed = Arrays.stream(InquiryRepository.class.getMethods())
                .map(Method::getName)
                .filter(FORBIDDEN::contains)
                .toList();

        assertThat(exposed)
                .as("소유자 없는 조회는 이름을 드러낸 권한 메서드(searchAll·findByIdForAgent)로만 열어야 한다 (D-045(1))")
                .isEmpty();
    }

    @Test
    @DisplayName("허용된 접근면은 그대로 열려 있다 — save·existsById·소유자/권한 조회")
    void allowedAccessSurfaceStaysOpen() {
        List<String> names = Arrays.stream(InquiryRepository.class.getMethods()).map(Method::getName).toList();
        assertThat(names).contains(
                "save",                    // 접수 저장 ①
                "existsById",              // 403/404 구분 (내용 안 꺼냄)
                "searchForCustomer",       // 소유자 필수
                "findByIdAndCustomerId",   // 소유자 필수
                "searchAll",               // 권한 전용 (이름에 드러남)
                "findByIdForAgent",        // 권한 전용 (이름에 드러남)
                "findByIdForClassification"); // 분류 ② 전용 owner-less 로드 (이름에 드러남, PR #30 findById 대체)
    }
}
