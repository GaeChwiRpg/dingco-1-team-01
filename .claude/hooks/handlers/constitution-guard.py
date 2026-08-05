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
import os
import re
import subprocess
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

# ── Flyway 마이그레이션 (D-023 — forward-only) ───────────────────────
MIGRATION_FILENAME = re.compile(r"(^|/)V(\d+)__[A-Za-z0-9_]+\.sql$")

# git 을 못 쓸 때만 쓰는 최소 안전망. 여기 적힌 버전은 "확실히 적용됐다"고 아는 것만.
# 평시에는 아래 is_committed() 가 판정하므로 V3·V4 가 생겨도 이 목록을 고칠 필요가 없다.
FALLBACK_APPLIED_VERSIONS = {1, 2}


def is_committed(path: str) -> "bool | None":
    """이 파일이 git 에 커밋돼 있나. 판정 불가면 None.

    **"적용됨"의 판정 기준을 커밋 여부로 둔다.** flyway_schema_history 를 직접 보는 편이
    정확하지만 훅이 DB 에 붙어야 하고, DB 가 안 떠 있으면 검사가 통째로 죽는다.
    커밋된 마이그레이션은 팀원 로컬·CI 볼륨에 이미 적용됐다고 봐야 안전한 쪽이다.

    작성 중인 새 마이그레이션(아직 커밋 전)은 여러 번 고치는 것이 정상 작업이므로 통과시킨다 —
    이 훅의 원칙이 "오탐이 거의 없을 것"이기 때문이다.

    **`git ls-files` 를 쓰지 않는다 (AI 리뷰 지적).** 그건 인덱스 추적 여부라
    `git add` 만 해둔 새 마이그레이션까지 "적용됨"으로 잡는다 — 커밋 직전에 한 번 더
    고치는 것은 정상 작업이고, 여기서 막히면 훅이 꺼진다. 커밋 이력을 직접 본다.
    """
    absolute = os.path.abspath(path)
    directory = os.path.dirname(absolute) or os.sep
    try:
        result = subprocess.run(
            ["git", "log", "-1", "--format=%H", "--", absolute],
            cwd=directory,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except Exception:
        return None
    if result.returncode != 0:
        return None  # 레포 밖 / 커밋이 하나도 없음 / git 자체가 실패. 판정하지 않는다
    return bool(result.stdout.strip())


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
    # 버전 번호를 하드코딩하지 않는다 — 하드코딩하면 다음에 만들 V3 가 적용된 뒤에도
    # 훅이 막아주지 않는다. 판정은 "커밋됐나"로 하고, git 을 못 쓸 때만 상수로 떨어진다.
    migration = MIGRATION_FILENAME.search(path)
    if migration:
        version = int(migration.group(2))
        committed = is_committed(path)
        if committed is None:
            print(
                "[constitution-guard] git 판정에 실패해 상수 목록으로 대체한다 "
                f"(V3 이상은 검사되지 않는다): {path}",
                file=sys.stderr,
            )
            applied = version in FALLBACK_APPLIED_VERSIONS
        else:
            applied = committed

        if applied:
            deny(
                f"[D-023] 이미 적용된 마이그레이션을 수정하려 한다: {path}\n"
                "Flyway 는 forward-only 이고 checksum 이 어긋나면 부팅이 실패한다 — "
                "이미 적용한 팀원 로컬·CI 볼륨이 전부 깨진다. "
                f"스키마를 바꾸려면 V{version + 1} 이상을 새 파일로 만든다.\n"
                "지난 버전을 지우거나 합치지도 않는다 — 만들었다 지우는 왕복이 생기더라도 "
                "이력은 앞으로만 남긴다 (V1 → V2 도메인 전환이 그 예다).\n"
                "커밋 전 작성 중인 마이그레이션은 이 검사에 걸리지 않는다."
            )

    sys.exit(0)


if __name__ == "__main__":
    main()
