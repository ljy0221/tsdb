#!/usr/bin/env bash
# pre-stop-checklist.sh
# 세션 종료 전 최소한의 품질 게이트

WARNINGS=()

# 1. Unsafe 사용 여부 검사
if grep -r "sun.misc.Unsafe" kronos-core/src/main --include="*.java" -l 2>/dev/null | grep -q .; then
  FILES=$(grep -r "sun.misc.Unsafe" kronos-core/src/main --include="*.java" -l)
  WARNINGS+=("❌ Unsafe 사용 감지: $FILES")
fi

# 2. Arena.ofAuto() 사용 여부 (GC 연동 — 이 프로젝트 목적에 반함)
if grep -r "Arena.ofAuto" kronos-core/src/main --include="*.java" -l 2>/dev/null | grep -q .; then
  WARNINGS+=("⚠️  Arena.ofAuto() 감지 — GC 연동 Arena는 Zero-GC 목표에 반합니다. Arena.ofConfined()로 교체하세요.")
fi

# 3. TIL.md 업데이트 여부 확인
if [[ -f "docs/TIL.md" ]]; then
  TODAY=$(date +%Y-%m-%d)
  if ! grep -q "$TODAY" docs/TIL.md 2>/dev/null; then
    WARNINGS+=("📝 오늘($TODAY) TIL 미작성. docs/TIL.md에 오늘 배운 것 한 줄 추가하세요.")
  fi
fi

# 4. 미커밋 변경사항 확인
if git diff --quiet && git diff --cached --quiet 2>/dev/null; then
  : # 커밋 완료
else
  WARNINGS+=("📦 미커밋 변경사항이 있습니다. 의미 있는 단위로 커밋하세요.")
fi

# 결과 출력
if [[ ${#WARNINGS[@]} -gt 0 ]]; then
  MSG="세션 종료 전 확인 필요:\n\n"
  for W in "${WARNINGS[@]}"; do
    MSG+="• $W\n"
  done
  echo "{\"decision\":\"block\",\"reason\":\"$MSG\"}"
  exit 1
fi

exit 0