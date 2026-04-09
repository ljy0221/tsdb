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
