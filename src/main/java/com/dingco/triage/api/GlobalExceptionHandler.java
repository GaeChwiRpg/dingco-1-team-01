package com.dingco.triage.api;

import com.dingco.triage.api.dto.ErrorResponse;
import com.dingco.triage.api.dto.ErrorResponse.FieldError;
import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.type.ErrorCode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.sentry.Sentry;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 API 오류가 여길 거친다 (TRI-29). {@code api/} 아래 컨트롤러에는 try-catch 를 두지 않는다.
 *
 * <p><b>여기서 다루지 않는 401/403 이 하나 더 있다</b> — endpoint 접근 자체를 막는 건
 * {@link com.dingco.triage.config.SecurityConfig} 의 몫이다. Security 필터 단계 예외는
 * 디스패치 이전에 던져져서 이 클래스가 애초에 못 잡는다. 여기서 잡는 401/403 은 컨트롤러가
 * 실행되는 도중에 발생하는 것 — 예를 들어 "내 문의가 아님"(D-038) 같은 서비스 계층 판단이다.
 *
 * <p>500(나머지)은 내부 예외 메시지를 응답에 담지 않는다 — 서버 구조가 고객에게 새는 자리라서다.
 *
 * <p><b>Sentry 는 500 만 보낸다 (TRI-30, D-030).</b> 400/401/403/404/409 는 클라이언트 잘못이라
 * 노이즈다 — 여기 있는 핸들러들은 정상적으로 값을 반환할 뿐 예외를 다시 던지지 않으므로 Sentry
 * 의 기본 처리되지 않은 예외 캡처에도 안 걸린다. 500 은 복구할 수 없는 최종 실패라 여기서
 * {@code Sentry.captureException} 을 명시적으로 부른다 — catch 하고 아무 것도 안 하면 자동도
 * 수동도 아니라서 Sentry 가 영구히 모른다({@code SENTRY-GUIDE.md} 2-3).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ErrorResponse.validation(ErrorCode.VALIDATION_FAILED.name(), "입력값이 올바르지 않습니다.", fieldErrors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        FieldError fieldError = new FieldError(ex.getName(), "허용되지 않는 값입니다: " + ex.getValue());
        return ErrorResponse.validation(ErrorCode.VALIDATION_FAILED.name(), "입력값이 올바르지 않습니다.", List.of(fieldError));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleUnreadableBody(HttpMessageNotReadableException ex) {
        String field = "body";
        if (ex.getCause() instanceof InvalidFormatException ife && !ife.getPath().isEmpty()) {
            field = ife.getPath().get(ife.getPath().size() - 1).getFieldName();
        }
        FieldError fieldError = new FieldError(field, "허용되지 않는 값입니다.");
        return ErrorResponse.validation(ErrorCode.VALIDATION_FAILED.name(), "입력값이 올바르지 않습니다.", List.of(fieldError));
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ErrorResponse handleAuthentication(AuthenticationException ex) {
        return ErrorResponse.of(ErrorCode.UNAUTHORIZED.name(), "인증이 필요합니다.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ErrorResponse handleAccessDenied(AccessDeniedException ex) {
        return ErrorResponse.of(ErrorCode.FORBIDDEN.name(), "이 작업을 수행할 권한이 없습니다.");
    }

    @ExceptionHandler({EntityNotFoundException.class, NoSuchElementException.class, NoResourceFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorResponse handleNotFound(Exception ex) {
        // NoResourceFoundException — 매핑된 컨트롤러가 없는 경로(Spring 6.1+). 이걸 안 잡으면
        // 아래 catch-all 이 삼켜서 404 여야 할 응답이 500 으로 나간다(실제 재현 확인, TRI-29).
        return ErrorResponse.of(ErrorCode.NOT_FOUND.name(), "대상을 찾을 수 없습니다.");
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse handleConflict(ConflictException ex) {
        return ErrorResponse.conflict(ex.getCode().name(), ex.getMessage(), ex.getReviewQueueItemId());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ErrorResponse handleUnexpected(Exception ex) {
        // 내부 메시지(ex.getMessage())를 응답에 담지 않는다 — 서버 구조가 새는 자리다.
        log.error("처리되지 않은 예외", ex);
        Sentry.captureException(ex);
        return ErrorResponse.of(ErrorCode.INTERNAL_ERROR.name(), "서버 오류가 발생했습니다.");
    }
}
