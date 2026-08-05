#!/usr/bin/env python3
"""PreToolUse(Write|Edit) 핸들러 — 헌법 위반 편집을 파일이 바뀌기 전에 차단한다.

dispatcher.sh 가 라우팅해서 호출한다. verify-before-push.sh 가 push 시점을 막는다면
이쪽은 편집 시점을 막는다 — 되돌리는 비용이 가장 싼 지점이다.

서브에이전트(constitution-auditor)와 역할이 다르다. 그쪽은 누가 호출해야 돌지만
이 훅은 조건이 맞으면 무조건 실행된다 — 규칙을 지킬지 말지를 모델의 판단에 맡기지 않는다.
대신 정적으로 확실한 것만 본다.

**여기 넣는 규칙의 기준은 오탐이 거의 없을 것.** 정상 작업을 막는 훅은 곧 꺼지고,
꺼진 훅은 없는 것만 못하다 — 통과가 보증처럼 보이기 때문이다. 판단이 필요한 규칙
(트랜잭션 경계 ①②③, 계약 A/B/C, 감사 표본 역산 가능성)은 정적 검사로 가릴 수 없으므로
constitution-auditor 의 몫으로 남긴다.

규칙을 추가할 때: 헌법 본문을 여기에 옮겨 적지 않는다. 검출 패턴과 차단 사유만 둔다.
사본을 두면 원본이 갱신될 때 조용히 낡는다.

stdin  : PreToolUse hook payload (JSON)
stdout : permissionDecision=deny 인 경우에만 JSON. 통과면 아무것도 쓰지 않는다
"""

import json
import re
import sys

# ── 1. 비밀 정보 (헌법 「작업 경계」 — commit 절대 금지) ──────────────
SECRET_FILENAMES = re.compile(
    r"(^|/)("
    r"\.env(\.[\w.-]+)?"      # .env, .env.local, .env.production
    r"|id_rsa|id_ed25519"
    r"|[\w.-]*\.(pem|p12|pfx|jks|keystore)"
    r")$"
)

# ── 2. 하드코딩된 크리덴셜 (헌법 「AI 호출 규칙」 — 환경변수만) ────────
HARDCODED_SECRETS = [
    (re.compile(r"sk-ant-[A-Za-z0-9_-]{10,}"), "Anthropic API key"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "AWS access key"),
    (re.compile(r"\bghp_[A-Za-z0-9]{20,}"), "GitHub personal access token"),
    (
        # api-key: "리터럴" — ${ENV} 참조는 통과시킨다
        re.compile(r"""api[-_]?key\s*[:=]\s*["']?(?!\$\{)[A-Za-z0-9_\-]{20,}"""),
        "API key 리터럴",
    ),
]

TX_ANNOTATION = re.compile(r"@Transactional\b")


def deny(reason: str) -> None:
    print(
        json.dumps(
            {
                "hookSpecificOutput": {
                    "hookEventName": "PreToolUse",
                    "permissionDecision": "deny",
                    "permissionDecisionReason": reason,
                }
            }
        )
    )
    sys.exit(0)


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except Exception as e:
        # 페이로드를 못 읽으면 통과시킨다 — 훅이 작업을 막는 사고를 만들지 않는다.
        # 다만 조용히 넘기지는 않는다. 검사가 통째로 안 돌았다는 사실이
        # 드러나지 않는 것이 이 프로젝트가 경계하는 실패 방식이다.
        print(f"[constitution-guard] 입력을 파싱하지 못해 검사를 건너뛴다: {e}", file=sys.stderr)
        sys.exit(0)

    tool_input = payload.get("tool_input") or {}
    path = tool_input.get("file_path") or ""
    # Write 는 content, Edit 는 new_string 에 새로 들어갈 내용이 담긴다.
    added = tool_input.get("content") or tool_input.get("new_string") or ""

    if not path:
        sys.exit(0)

    # ── 1. 비밀 파일 자체를 만지려는 경우 ──
    if SECRET_FILENAMES.search(path):
        deny(
            f"[헌법 「작업 경계」] 비밀 정보 파일을 편집하려 한다: {path}\n"
            "`.env` / 키 파일은 commit 절대 금지다. "
            "설정이 필요하면 환경변수로 주입하고 `application.yml` 에서 `${VAR}` 로 참조한다."
        )

    # ── 2. 크리덴셜 하드코딩 ──
    for pattern, label in HARDCODED_SECRETS:
        if pattern.search(added):
            deny(
                f"[헌법 「AI 호출 규칙」] {label} 로 보이는 값을 코드에 직접 쓰려 한다: {path}\n"
                "API key 는 환경변수만 허용된다 (`ANTHROPIC_API_KEY`). "
                "코드·설정 파일 하드코딩 금지."
            )

    # ── 3. api/ 계층의 @Transactional (헌법 「@Transactional 위치」) ──
    if re.search(r"/api/[^/]*\.java$", path) and TX_ANNOTATION.search(added):
        deny(
            f"[헌법 「@Transactional 위치」] Controller 에 @Transactional 을 붙이려 한다: {path}\n"
            "`api/` 는 HTTP 입출력과 DTO 변환까지만 한다. 트랜잭션 경계는 `service/` 의 "
            "①②③ 세 메서드에만 둔다 — 여기 밖에 새로 붙이려면 근거를 PR 본문에 쓴다."
        )

    # ── 4. 적용된 마이그레이션 수정 (D-023) ──
    # V1(구 도메인) · V2(도메인 전환, D-031) 둘 다 이미 적용된 상태다.
    if re.search(r"V[12]__[A-Za-z0-9_]+\.sql$", path):
        deny(
            f"[D-023] 이미 적용된 마이그레이션을 수정하려 한다: {path}\n"
            "Flyway 는 forward-only 이고 checksum 이 어긋나면 부팅이 실패한다 — "
            "이미 적용한 팀원 로컬·CI 볼륨이 전부 깨진다. "
            "스키마를 바꾸려면 `V3__*.sql` 을 새로 만든다.\n"
            "V1 은 구 도메인(에러 분류) 스키마이고 V2 가 그것을 걷어낸다 (D-031). "
            "둘 다 지우거나 합치지 않는다 — 이력을 앞으로만 남기기 위한 왕복이다."
        )

    sys.exit(0)


if __name__ == "__main__":
    main()
