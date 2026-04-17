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
