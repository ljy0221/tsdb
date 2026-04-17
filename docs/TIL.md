# TIL — Today I Learned

> 세션마다 배운 것 한 줄 요약

---

<!-- 형식: - YYYY-MM-DD: 내용 -->

## 2026-04-09

- Gradle 8.7 멀티모듈 프로젝트 구조 세팅 (`settings.gradle.kts` + `kronos-core/build.gradle.kts`)
- JMH 플러그인(`me.champeau.jmh`)은 `src/jmh` 소스셋을 별도로 인식한다. `src/test`에 넣으면 안 됨.
- `--enable-native-access=ALL-UNNAMED` JVM 옵션 없으면 FFM API 사용 시 경고 발생. 테스트/벤치마크 태스크 모두에 추가해야 함.
- `MemorySegment.get()`의 두 번째 인자는 바이트 오프셋. `index * Long.BYTES` 필수. `index`만 넣으면 조용히 잘못된 주소 접근.
- `Arena.ofConfined()`는 생성한 스레드에서만 접근 가능. JMH `@Setup`/`@TearDown`과 같은 스레드에서 실행되므로 문제없음.
- `Arena.close()` 이후 `MemorySegment`에 접근하면 `IllegalStateException` 발생 — 테스트로 검증 가능.
- 인힙 `long[]` vs 오프힙 `MemorySegment` 성능: JMH 결과 오차 범위 내 동등. FFM API 오버헤드 = 0.
- WSL2 환경에서 `gradle wrapper` 생성하려면 Gradle 바이너리를 직접 받아서 실행해야 함 (`gradle` 명령어 기본 설치 안 됨).

## 2026-04-17

- LSM-Tree 읽기 경로에서 **push-style callback(`forEachInOrder`)은 merge에 부적합**. 여러 소스를 한 스텝씩 전진시켜야 하는데 callback이 제어 흐름을 가져가버린다. `Iterator<T>`로 통일해야 MergingIterator가 성립한다.
- **k-way merge의 newest-wins는 힙 비교자에서**: 1차 키 timestamp 오름차순, 2차 키 priority(소스 index) 오름차순 → poll한 원소와 같은 ts를 가진 나머지는 drain. 소스 순서만 `[memTable, sst_new, ..., sst_old]`로 주면 자연히 LSM 의미론이 된다.
- `SSTableReader`에 체크섬 변조 테스트를 쓸 때, 매직 넘버 변조와 CRC32 변조는 **다른 코드 경로**다. 하나로 커버되지 않으니 둘 다 써야 한다.
- `MergingIterator`의 `Node` record는 힙 원소당 할당 → 대량 병합 시 GC 압력 우려. 정확성 먼저, 벤치마크로 확인 후 mutable node / 풀링 검토 예정.
- Phase 1 JMH 쓰기 벤치 확보: `memTableOnly` 9.47M ops/s, `endToEndFlush` 3.27M ops/s, `memTablePlusWal` 389 ops/s. **WAL DSYNC fsync가 ~2.57ms**로 durable 쓰기의 물리 상한이며, 이게 곧 "compaction을 쓰기 경로에 동기화하지 말 것"이라는 설계 제약으로 전환된다.
- **백그라운드 compaction이 지금 기술적으로 불가능**한 이유: `SSTableReader`가 `Arena.ofConfined()`로 mmap을 쥐고 있어서 다른 스레드 접근은 `WrongThreadException` 확정. 해결은 `ofShared()` 전환(ADR 3개 재작성) 또는 중복 mmap뿐 — Phase 1에서 수동 트리거가 **유일한 합법적 선택**이 된 결정적 근거.
- Java 숫자 리터럴 규칙: 언더스코어는 숫자 사이에만 허용. **상수 이름이 숫자로 시작하면 안 되지만**, `20_NEW`처럼 숫자+언더스코어+식별자 형태도 같은 규칙에 걸린다(compiler가 숫자 리터럴로 파싱). 테스트 상수는 `TS20_NEW`처럼 문자부터 시작해야 함.
- **Phase 1 closure 조건은 "체크박스 채우기"가 아니라 "네 편의 블로그가 새 결정을 설명할 수 있게 끝맺는 것"**이었다. 5편(Basic Compaction)은 ADR-004의 세 결정(size-tiered / 수동 트리거 / 뷰 스냅샷)이 **이전 ADR들(ADR-001의 `ofAuto` 금지, `ofConfined`로 인한 `WrongThreadException`, 4편 벤치의 WAL 2.57ms 상한)에 의해 강제된 결과**임을 드러내야 말이 된다. 자유도가 줄어드는 게 아니라 탐색 공간이 수렴하는 구조.

## 2026-04-17 (Phase 2 킥오프)

- **Phase 2 첫 태스크는 "구현"이 아니라 "측정 하네스 + 판정식 박제"였다.** CLAUDE.md 체크리스트는 "타임스탬프 인덱스"로 시작하지만, 그걸 정말 해야 하는지를 **먼저** 판정하는 벤치를 구축. spec(`docs/superpowers/specs/2026-04-17-phase2-scan-bench-design.md`) → plan(`docs/superpowers/plans/2026-04-17-phase2-scan-bench.md`) → 실행 순서.
- **tech-decision-debate 스킬의 비판자 에이전트가 A안(time_ratio 단일 기준)을 구조적으로 무너뜨렸다.** 지적: (1) compaction 직후 파일 1개 상태로만 재면 편향, (2) `BiConsumer + Double boxing` 이 분모를 오염. 혼자였으면 `time_ratio < 10`을 그냥 확정했을 것. 결국 판정식은 `p99 < 5ms AND ratio > 20× (@files=1)` + `scanCount` raw primitive 경로로 교체됐다.
- **계획 단계에서 코드를 다시 읽다가 가정 하나가 틀려있음을 발견.** `SSTableReader.scan()`은 **이미 이진 탐색 기반**이고 O(n) 경로는 `LsmReadView.scan()`의 MergingIterator 경유뿐이다. spec이 "파일 내부 O(n)"을 전제로 쓰였는데 실제로는 **"MergingIterator가 파일 전체를 iterator화"** 하는 게 원인. 결론은 그대로 유지되지만 구현 방향(파일 내부 sparse index를 `SSTableReader`가 아니라 MergingIterator 진입 전에 쓸 수 있게 노출)이 달라진다.
- **벤치 결과**: 16 조합 중 `ratio(files=1, RECENT, 1.0/0.01) = 1.02×` — 선택률이 100배 줄어도 시간이 거의 그대로. 현재 구현이 selectivity를 **완전히 무시**한다는 가장 강한 증거. 판정식 3개 조건 전부 FAIL → ADR-005는 인덱스 방향.
- **예상 밖 수치 2개**: (1) `files=4` 가 `files=1` 보다 빠름 — `SSTableMeta.overlaps()` 프루닝이 기대 이상으로 효과적. compaction이 파일 수를 줄이는 게 읽기 관점에서는 **손해**일 수 있다. (2) `RECENT` 가 `UNIFORM` 보다 느림 — 원인은 `endTs == dataMax` 에서 MergingIterator `> endTs` break가 발동 안 되는 것. 페이지 캐시 warm 이점 가설이 틀렸다.
- **`sourceSets { test { java.srcDir 'src/jmh/java' } }` + `testImplementation jmh-core`** 조합으로 JMH 헬퍼 클래스(`ScanDistribution`, `ScanBenchFixture`)를 unit test에서 건드릴 수 있게 됐다. 이걸 안 하면 JMH 벤치 안의 로직을 테스트할 방법이 **없다** — 플러그인 관례가 벤치 코드를 main과 분리한 결과다.
