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
- `scripts/push-fixtures.sh` : `test-fixtures/images/` → `/sdcard/Pictures/fixtures` (+MediaStore 스캔). 문서/음성/영상은 `/sdcard/Documents|Recordings|Movies/fixtures`에 직접 push
- `./gradlew assembleDebug testDebugUnitTest` / `./gradlew connectedDebugAndroidTest`
- 모델 파일(*.gguf, *.tflite, *.onnx 등)은 git 제외.

## 앱 구조
이미지·텍스트·문서(PDF/Office)·음성·영상을 인덱싱하고 시맨틱 + 키워드로 검색한다. 모델은 APK에 넣지 않고 Settings에서 사용자가 받는다(ML Kit OCR과 Silero VAD 0.6MB만 앱에 포함).

- **데이터**(`data/db`): `items`(파일 1개: `sourceKey`, `kind`, `pipelineKey`, `progress`, 상태) + `segments`(검색 단위: `type`, 시간/쪽, 텍스트, int8 벡터 `Quant`, `thumbPath`). 종류별 세그먼트: IMAGE/OCR_TEXT(이미지), TEXT_CHUNK(텍스트·문서), PAGE_IMAGE(PDF 쪽), CARD_IMAGE(Office 텍스트 카드), TRANSCRIPT/AUDIO_CLIP(음성), VIDEO_CLIP(영상). 마이그레이션 v1→v2는 `Migrations.kt`.
- **소스**(`data/media`): `MediaStoreRepository`(이미지·음성·영상) + `FileSource`(txt/md/문서, "모든 파일 접근" 필요) → `CompositeSource`. 폴더 키는 MediaStore `RELATIVE_PATH`와 같은 상대 경로.
- **파이프라인**(`indexing`): `IndexRunner.sync()`(삭제·이동 정리, 대상 계산, 모델 불필요) → `process()`(파일 단위 커밋, `progress` 체크포인트로 재개). `ItemSession`이 종류별 추출기로 분배: `ImageExtractor`, `text/TextExtractor`(+ `TextChunker`·`MarkdownChunker`·`TextFormats`), `docs/PdfExtractor`·`OfficeExtractor`(+ `OfficeParsers`, `TextCard`, `ThumbStore`), `audio/AudioExtractor`(+ `AudioDecoder`, `SpeechTools`=sherpa-onnx VAD/ASR, `AudioWindows`), `video/VideoExtractor`(+ `VideoClips`). `pipelineKey`가 바뀌면(모델·청커 버전·영상 정밀도 등) 그 종류만 재처리.
- **검색**(`search`): 세그먼트를 VISUAL / TEXT / AUDIO 그룹으로 나눠 그룹별로 "중앙값 대비 편차 게이트 + 상대 점수"(`Fusion`), SQL `LIKE` 키워드, 항목 단위로 묶어 가장 잘 맞은 위치(쪽/시간)를 표시. 완전일치 키워드는 최상단, 영상 전사 히트는 겹치는 클립으로 좁힘.
- **자동 인덱싱**(`AutoIndexScheduler`): MANUAL / IMMEDIATE(WorkManager content-URI 트리거, 앱이 죽어 있어도 동작) / SCHEDULED / IDLE(충전+유휴). 첫 실행 온보딩에서 선택. 백그라운드 자동 실행은 foreground service를 못 띄워 `setForeground`를 try/catch로 시도.
- **모델 메모리**(`ml/ModelResidency`, lease 방식): KEEP / IDLE_2M(기본) / IMMEDIATE + `onTrimMemory`. 임베딩·OCR·음성(ASR) 모두 관리. 로그 태그 `LSA.Residency`.
- UI 문자열은 `values/strings.xml`(en) + `values-ko/strings.xml`(ko), 키 동일해야 함(`StringsParityTest`).

## 빌드 전 준비
- `scripts/fetch-sherpa-onnx.sh`: sherpa-onnx AAR(`app/libs`)과 Silero VAD(`app/src/main/assets`)를 내려받는다. 둘 다 git에 올리지 않는다.

## 조사 결과(Phase 0, 실측)
- EmbeddingGemma 2 **740M만 오디오** 지원. `EmbeddingEngineConfig(audioBackend=…)`가 있어야 하고, 입력은 **WAV 바이트(16kHz mono)**. 한 입력은 **1024토큰 ≈ 40초**까지(30초 창 + 5초 겹침 사용).
- `InputData.Image` 여러 장 리스트는 **벡터 1개**를 돌려준다(프레임별 평균과 코사인 0.90, 순서 무관). 클립당 최대 8장, 프레임당 `visionTokensPerImage=70`.
- sherpa-onnx(SenseVoice int8 + Silero VAD)는 에뮬레이터에서 실시간의 ~2% 속도, 한국어·영어 인식 양호. 모델은 GitHub 릴리스 tar.bz2(163MB)라 앱이 받아 `ArchiveExtractor`로 푼다.

## 실제 모델 E2E (에뮬레이터)
- 테스트 파일은 커밋하지 않는다. 생성: `scripts/fetch-photo-fixtures.sh`, `uv run --with pillow scripts/make-text-fixtures.py`, `python3 scripts/make-doc-fixtures.py`, `uv run --with reportlab --with pillow --with python-docx --with python-pptx --with openpyxl scripts/make-office-fixtures.py`, `scripts/make-audio-fixtures.sh`(+ 영상은 ffmpeg로 장면 3개 mp4).
- `scripts/e2e-setup.sh`(UI 구동 설정)는 `adb uiautomator`가 막히면 못 쓴다. 그 경우 mobile-mcp로 직접 진행. 검색은 `scripts/ui-search.sh "질의"`(영어) / mobile-mcp 입력 후 `scripts/search-result.sh`(한국어)로 결과 파일명을 확인.
- 주의: `./gradlew connectedDebugAndroidTest`는 끝나고 앱을 제거해 받은 모델도 사라진다. 계측 테스트는 기기의 앱 설정에 의존하지 않게 작성한다.
- 에뮬레이터는 CPU 추론. 실기기는 GPU 우선, 실패 시 CPU 폴백.

## 한계
- Office 문서는 폰에서 렌더링할 수 없어 텍스트 + 생성한 텍스트 카드 이미지만 쓴다. 구형 .doc/.ppt/.xls, hwp는 미지원.
- 벡터는 메모리 전체 탐색(int8): 약 30만 세그먼트가 목표 규모. 키워드는 SQL LIKE(느려지면 FTS5 trigram 검토).
- 영상은 장면/길이 기준 클립 임베딩이라 짧은 순간(몇 초 미만)은 놓칠 수 있다. 정밀도는 Settings에서 조절.
