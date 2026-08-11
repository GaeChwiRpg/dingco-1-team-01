package com.dingco.triage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 측정 6 용 1000건 시드({@code inquiries-1000.csv})를 <b>실제 정규화 키로</b> 검산한다 (TRI-81).
 *
 * <p>이 데이터의 값어치는 "표현만 바꿔 불렸다"가 실제로 지켜졌는지에 달려 있다. 그 검산을 눈이나
 * 파이썬 재구현이 아니라 <b>제품 코드({@link NormalizedKeyGenerator})</b>로 한다 — 재구현은
 * 원본과 조용히 어긋나고(예: 마스킹 순서·유니코드 접기), 그러면 "접힘 변형은 키가 같다"는 주장이
 * 데이터가 아니라 짐작이 된다 (CLAUDE.md "측정 결과는 본인 실측만").
 *
 * <p>검산하는 것 두 갈래:
 * <ul>
 *   <li><b>구조</b> — 1000행·전행 묶음번호·20변형(12 접힘 + 8 어형)·클래스 44개
 *   <li><b>키 의미</b> — 접힘 변형은 원본과 <b>같은 키</b>, 어형 변형은 <b>다른 키</b>, 원본 50건은
 *       서로 <b>다른 키</b>(과도 병합 없음)
 * </ul>
 *
 * <p>끝에서 측정 6 이 읽을 수를 뽑아 콘솔에 남긴다 — {@code evidence/measurement-6-seed-1000.md}
 * 의 숫자가 이 실측과 같아야 한다.
 */
class SeedInquiries1000Test {

    private static final String RESOURCE = "/seed/inquiries-1000.csv";
    private static final String[] HEADER =
            {"id", "source_id", "variant_kind", "dup_group", "content", "expected_category", "channel"};

    private record Row(int id, int sourceId, String kind, String group, String content,
                       String category, String channel) {}

    private final NormalizedKeyGenerator keyGen = new NormalizedKeyGenerator(new ContentMasker());

    @Test
    void 시드_1000건이_구조와_정규화_키_의미를_모두_지킨다() throws Exception {
        List<Row> rows = load();

        // ── 구조 ──
        assertEquals(1000, rows.size(), "1000행이어야 한다");
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).id(), "id 는 1..1000 연속");
            assertFalse(rows.get(i).group().isBlank(), "묶음번호가 빈 행이 있으면 안 된다 (완료조건 1)");
        }
        long collapse = rows.stream().filter(r -> r.kind().equals("COLLAPSE")).count();
        long wording = rows.stream().filter(r -> r.kind().equals("WORDING")).count();
        assertEquals(600, collapse, "COLLAPSE 12×50");
        assertEquals(400, wording, "WORDING 8×50");

        // 원본(source_id) 별로 20행 = 12 접힘 + 8 어형, 카테고리·채널·묶음번호는 원본에서 물려받음
        Map<Integer, List<Row>> bySource = new TreeMap<>();
        for (Row r : rows) {
            bySource.computeIfAbsent(r.sourceId(), k -> new ArrayList<>()).add(r);
        }
        assertEquals(50, bySource.size(), "원본 50건에서 파생");
        for (var e : bySource.entrySet()) {
            List<Row> g = e.getValue();
            assertEquals(20, g.size(), "원본당 20변형");
            assertEquals(12, g.stream().filter(r -> r.kind().equals("COLLAPSE")).count());
            assertEquals(8, g.stream().filter(r -> r.kind().equals("WORDING")).count());
            String cat = g.get(0).category(), chan = g.get(0).channel(), grp = g.get(0).group();
            assertTrue(g.stream().allMatch(r ->
                    r.category().equals(cat) && r.channel().equals(chan) && r.group().equals(grp)),
                    "원본 " + e.getKey() + ": 카테고리·채널·묶음번호는 파생 전체가 같아야 한다");
        }

        // 클래스(묶음번호) = 사람이 같은 뜻으로 보는 단위. 측정 6ⓐ 의 분모가 여기서 나온다.
        Set<String> classes = new HashSet<>();
        rows.forEach(r -> classes.add(r.group()));
        int humanDupDenominator = rows.size() - classes.size(); // 대표 1건씩 빼면 나머지가 "사람 기준 중복"
        assertEquals(44, classes.size(), "G1~G6(6) + 유일원본 38 = 44 클래스");
        assertEquals(956, humanDupDenominator, "사람이 보기에 중복인 건수 = 1000 - 44");

        // ── 정규화 키 의미 ──
        Map<Integer, String> canonicalKey = new LinkedHashMap<>();
        for (var e : bySource.entrySet()) {
            List<Row> g = e.getValue();
            // 정본 = 이 원본의 첫 COLLAPSE 행(레시피 0 = 원본 그대로). id 순이라 리스트 선두다.
            Row canonical = g.stream().filter(r -> r.kind().equals("COLLAPSE")).findFirst().orElseThrow();
            String key = keyGen.generate(canonical.content());
            canonicalKey.put(e.getKey(), key);
            for (Row r : g) {
                String k = keyGen.generate(r.content());
                if (r.kind().equals("COLLAPSE")) {
                    assertEquals(key, k,
                            "접힘 변형은 원본과 같은 키여야 한다 (원본 " + e.getKey() + ", id " + r.id() + ")");
                } else {
                    assertNotEquals(key, k,
                            "어형 변형은 원본과 다른 키여야 한다 (원본 " + e.getKey() + ", id " + r.id() + ")");
                }
            }
        }
        // 원본 50건의 키가 서로 다르다 = 서로 다른 문의를 같다고 보는 과도 병합이 없다
        assertEquals(50, new HashSet<>(canonicalKey.values()).size(),
                "원본 50건의 정규화 키가 전부 달라야 한다 (과도 병합 없음)");

        // ── 측정 6 이 읽는 수 (실측) ──
        Set<String> distinctKeys = new HashSet<>();
        rows.forEach(r -> distinctKeys.add(keyGen.generate(r.content())));
        int systemReuse = rows.size() - distinctKeys.size(); // 시스템이 키 충돌로 아끼는 상한

        // 접힘만 키가 겹치므로 시스템 절감 = 원본당 (12-1) × 50 = 550. 어형·원본 키는 전부 유일.
        assertEquals(550, systemReuse, "시스템 절감 상한(키 충돌) = 접힘 중복분");
        assertEquals(450, distinctKeys.size(), "서로 다른 정규화 키 = 원본당 9(정본1+어형8) × 50");
        // D-014: 캐시가 아끼는 것(키 충돌) ≤ 사람이 보기에 중복. 이 데이터가 그 부등식을 담는다.
        assertTrue(systemReuse < humanDupDenominator, "시스템 절감(550) < 사람 기준 중복(956)");

        System.out.printf(
                "%n[measurement-6 seed-1000 실측]%n"
                        + "  전체 행                 : %d%n"
                        + "  의미 클래스(묶음)        : %d%n"
                        + "  사람이 보기에 중복(분모) : %d%n"
                        + "  서로 다른 정규화 키      : %d%n"
                        + "  시스템 절감 상한(키충돌) : %d%n"
                        + "  변형 분포                : COLLAPSE %d / WORDING %d%n",
                rows.size(), classes.size(), humanDupDenominator,
                distinctKeys.size(), systemReuse, collapse, wording);
    }

    private List<Row> load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE + " 를 클래스패스에서 찾지 못했다");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String header = br.readLine();
                assertNotNull(header, "헤더가 없다");
                assertEquals(String.join(",", HEADER), header, "열 구성이 계약과 다르다");
                List<Row> rows = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    List<String> f = parseCsv(line);
                    assertEquals(7, f.size(), "열 수가 7이 아니다: " + line);
                    rows.add(new Row(Integer.parseInt(f.get(0)), Integer.parseInt(f.get(1)),
                            f.get(2), f.get(3), f.get(4), f.get(5), f.get(6)));
                }
                return rows;
            }
        }
    }

    /** 따옴표로 감싼 콤마 포함 필드를 처리하는 최소 RFC4180 파서 (임베드 개행은 데이터에 없다). */
    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
