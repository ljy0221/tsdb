# /project:bench

JMH 벤치마크를 실행하고 결과를 docs/benchmarks/에 저장한다.

## 실행 순서

1. 현재 Phase 확인 (CLAUDE.md 체크리스트 기준)
2. JMH 실행:
   ```bash
   ./gradlew :kronos-core:jmh -Pjmh.includes=".*" 2>&1 | tee /tmp/jmh-result.txt
   ```
3. 결과를 `docs/benchmarks/phase{N}-$(date +%Y%m%d).md`에 저장
4. 이전 결과와 비교해서 regression 여부 체크
5. 결과 요약을 한국어로 설명

## 저장 포맷
```markdown
## Phase {N} 벤치마크 — {날짜}

**환경**: {java --version} / {uname -m} / JMH {version}
**커밋**: {git rev-parse --short HEAD}

| Benchmark | Mode | Cnt | Score | Error | Units |
|---|---|---|---|---|---|
| ... | ... | ... | ... | ... | ... |

**결론**: (한 줄 요약)
```
