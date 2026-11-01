"""Generates text-like fixture files (no dependencies). Output: test-fixtures/docs/ (not committed).
Run: python3 scripts/make-doc-fixtures.py
Each file contains a unique marker word so keyword search can be verified, plus text for semantic queries."""
from pathlib import Path

out = Path(__file__).resolve().parent.parent / "test-fixtures" / "docs"
out.mkdir(parents=True, exist_ok=True)

def w(name, text, enc="utf-8"):
    (out / name).write_bytes(text.encode(enc))

w("notes_ko.txt", """프로젝트 킥오프 회의 메모

참석자: 김민수 대리, 이지은 과장, 박서준 팀장

안건 1. 출시 일정
은하수프로젝트의 출시일은 12월 15일로 확정되었습니다. 디자인 리뷰는 다음 주 화요일 오전 열 시에 진행합니다.

안건 2. 예산
마케팅 예산은 3천만 원으로 승인되었고, 추가 집행은 팀장 승인이 필요합니다.

안건 3. 리스크
서버 용량이 부족할 수 있어 인프라 팀과 별도 회의를 잡기로 했습니다.
""")

w("legacy_cp949.txt", "옛날 문서입니다.\r\n\r\n무지개떡 레시피: 쌀가루에 색소를 넣어 층층이 쌓아 찝니다.\r\n\r\n오래된 윈도우 메모장에서 저장한 파일입니다.\r\n", enc="cp949")

w("readme.md", """# Zephyr Toolkit

A small toolkit for scheduling background jobs.

## Installation

Install with the package manager. The marker word zephyrquartz appears only here.

```bash
pip install zephyr-toolkit
zephyr init --path ./jobs
```

## Usage

Create a job file and run `zephyr run`. Jobs can be retried automatically.

| Option | Meaning |
| --- | --- |
| --retries | number of retries |
| --timeout | seconds before a job is cancelled |

## FAQ

### How do I cancel a running job?

Press Ctrl+C or call `zephyr cancel <job-id>`.
""")

w("회의록.md", """---
title: 주간 회의록
---

# 주간 회의록

## 결정 사항

- 신규 기능 이름은 **별빛서점**으로 정했습니다.
- 베타 테스트는 다음 달 첫째 주에 시작합니다.

## 다음 할 일

1. 디자인 시안 공유
2. 서버 부하 테스트
""")

w("table.csv", "name,department,city\n" + "\n".join(f"Employee{i},Team{i % 5},City{i % 7}" for i in range(1, 60)) + "\nHyeonwoo Seo,Quokkalab,Busan\n")

w("page.html", """<html><head><title>Garden Guide</title><style>p{color:red}</style></head><body>
<h1>Garden Guide</h1><p>Welcome to the garden guide.</p>
<h2>Tomatoes</h2><p>Tomatoes need full sun and regular watering. The special marker is fenneltomato.</p>
<h2>Herbs</h2><p>Basil and mint grow well in pots near a sunny window.</p>
<script>var hidden = 'scriptonlytoken';</script></body></html>
""")

w("data.xml", """<?xml version="1.0"?><catalog><book id="b1"><title>The Silent Ocean</title><author>Marina Cole</author>
<description>A story about deep sea explorers who discover a glowing reef called luminareef.</description></book></catalog>
""")

w("sub.srt", """1
00:00:01,000 --> 00:00:04,000
Welcome to the cooking class.

2
00:00:05,000 --> 00:00:09,000
Today we will bake sourdough bread with rye flour.

3
00:01:10,000 --> 00:01:15,500
Remember to preheat the oven before shaping the loaf.
""")

# a long plain text with a needle deep inside, to check chunking and result location
filler = ["The village sat quietly beside the grey sea, and the days passed one after another without much news." for _ in range(1)]
paras = []
for i in range(1, 41):
    paras.append(f"Chapter note {i}. The fishermen mended their nets and talked about the weather and the price of salt. "
                 f"Nothing unusual happened on day {i}, and the harbour bell rang at the usual hour.")
paras[29] += " Then the lighthouse keeper found a small brass key hidden under the old staircase, engraved with a seahorse."
w("long_story.txt", "\n\n".join(paras) + "\n")
print(sorted(p.name for p in out.iterdir()))
