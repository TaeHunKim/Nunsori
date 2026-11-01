# Local Search Anything (Android)
온디바이스 멀티모달 임베딩 + 시맨틱 검색 + 로컬 LLM RAG 앱. 패키지 `com.lsa.app`, Kotlin + Compose, minSdk 29 / target 36 / compile 37.

## 환경
- JDK: Android Studio 내장 JBR (`scripts/env.sh`가 JAVA_HOME/ANDROID_HOME/PATH 설정). 셸에서 adb 등이 안 잡히면 `source scripts/env.sh`.
- SDK: `~/Library/Android/sdk` (API 36 + 37.0 platform, NDK 28.2, CMake 3.31.6). AVD: `lsa_api36` (RAM 8GB, data 32GB).
- MCP: `mobile-mcp` (.mcp.json) — 화면 스냅샷/탭/입력용. 빌드·로그는 아래 스크립트/gradlew 사용.

## 워크플로우
- `scripts/emu-start.sh [--window]` / `scripts/emu-stop.sh`
- `scripts/run.sh` : installDebug + 앱 실행
- `scripts/screenshot.sh` → `build/screens/*.png` (Read로 확인)
- `scripts/logcat.sh [N]` : 앱 PID 로그 (죽었으면 에러 로그)
- `scripts/push-fixtures.sh` : `test-fixtures/images/` → `/sdcard/Pictures/fixtures` (+MediaStore 스캔)
- `./gradlew assembleDebug testDebugUnitTest` / `./gradlew connectedDebugAndroidTest`
- 모델 파일(*.gguf, *.tflite, *.onnx 등)은 git 제외.

## 앱 구조 (1차: 이미지 인덱싱/검색)
- `data/` Room(`images`, `indexed_folders`) · MediaStore · DataStore / `ml/` Embedder(EmbeddingGemma 2 via LiteRT-LM), OCR(ML Kit ko+latin) / `models/` 카탈로그·HF 다운로더(이어받기)·워커 / `indexing/` IndexRunner(재개 가능 루프)+IndexingWorker(foreground dataSync) / `search/` SearchEngine + Fusion / `ui/` Drawer(Index, Search, Settings).
- 모델은 APK에 넣지 않는다. Settings에서 사용자가 Download (HF `litert-community/...`). OCR(ML Kit)만 번들.
- 검색: 이미지·OCR 임베딩 코사인 + OCR 키워드. 신호별로 중앙값 대비 편차(`*_MIN_SPREAD`)가 작으면 노이즈로 보고 버리고, 최고점 기준 `*_MARGIN` 안에서만 가중합. 키워드 완전일치는 최상단. (순위 기반 RRF는 무관한 OCR 점수가 ~0.6으로 몰려 부적합해 폐기)
- **자동 인덱싱**(`indexing/AutoIndexScheduler`): 모드 MANUAL / IMMEDIATE(WorkManager content-URI 트리거: 15초~2분, 앱이 죽어 있어도 동작, 생성·수정·삭제 모두 감지) / SCHEDULED(24h periodic + 지정 시각 지연, 충전 옵션) / IDLE(충전+기기 유휴+배터리 OK, 3h periodic). CPU/GPU/NPU 점유율은 앱이 읽을 수 없어 유휴 모드는 대리 지표 + 발열(`isHot`) 감시. 첫 실행 온보딩에서 선택(기본값 없음).
- `IndexRunner.sync()`는 모델 없이 삭제 정리·이동 처리·대상 계산, `process()`가 임베딩. 조회 결과가 비면(권한 회수 등) 전체 삭제하지 않음. 앱 시작 시(`MainActivity.onStart`) 가벼운 정리.
- 모델 메모리(`ml/ModelResidency`, lease 방식): KEEP / IDLE_2M(기본) / IMMEDIATE, 시스템 `onTrimMemory`≥BACKGROUND면 유휴 시 해제. 로그 태그 `LSA.Residency`.
- 백그라운드에서 시작된 자동 작업은 foreground service를 못 띄워서(Android 12+) `setForeground`를 try/catch로 시도하고 조용히 진행.
- UI 문자열은 `values/strings.xml`(en) + `values-ko/strings.xml`(ko), 키 동일해야 함(`StringsParityTest`).

## 실제 모델 E2E (에뮬레이터)
1. 테스트 이미지는 커밋하지 않는다. `scripts/fetch-photo-fixtures.sh`(공개 사진 4장) + `uv run --with pillow scripts/make-text-fixtures.py`(한/영 텍스트 이미지 4장)로 `test-fixtures/images/`에 만든 뒤 `scripts/push-fixtures.sh`
2. 앱 설치 후 UI(mobile-mcp)로: Settings에서 사진 권한 허용 → `Pictures/fixtures` 체크 → EmbeddingGemma 2 440M Download → Index에서 Start.
3. `scripts/e2e-search.sh` (영어 질의 top-1 검증). 한국어 질의는 `adb input`이 안 되므로 mobile-mcp `type_keys` 사용 후 `adb logcat -s LSA.Search`의 `RESULT` 로그 확인.
- 주의: `./gradlew connectedDebugAndroidTest`는 끝나고 앱을 제거해 다운로드한 모델도 사라진다. 모델을 다시 받아야 함.
- 에뮬레이터는 CPU 추론(이미지+OCR 약 2~3초/장). 실기기는 GPU 우선, 실패 시 CPU 폴백.
