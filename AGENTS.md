# Nunsori / 눈소리 (Local Search Anything, Android)
온디바이스 멀티모달 임베딩 + 시맨틱 검색 + 로컬 LLM RAG 앱. 앱 이름 Nunsori(눈소리), applicationId `io.github.taehunkim.nunsori`, 코드 패키지·namespace `com.lsa.app`(둘은 별개; 스크립트는 `scripts/env.sh`의 `APP_ID`/`APP_ACTIVITY` 사용), Kotlin + Compose, minSdk 29 / target 36 / compile 37.

## 문서 유지 규칙 (코드 구현 시 항상 적용)
코드를 구현·수정할 때는 **같은 작업 안에서 관련 문서도 함께 갱신**하고, 코드와 문서를 같은 커밋에 넣는다. 갱신이 필요 없다고 판단하면 이유를 한 줄로 남긴다.

| 변경 | 갱신할 문서 |
|---|---|
| 새 기능·화면·설정 | `README.md` + `README.ko.md`(기능 표), `docs/user-guide.md` |
| 데이터 모델·파이프라인·패키지 구조 | `docs/architecture.md`, `docs/indexing.md` |
| 랭킹·임계값·가중치 | `docs/search.md` |
| 모델 추가·교체, 의존성, 라이선스 | `docs/models.md`, `THIRD_PARTY_NOTICES.md` |
| 권한·네트워크·저장 데이터 | `docs/privacy.md` |
| 빌드·스크립트·테스트 방법 | `docs/getting-started.md`, `docs/development.md` |
| 계획·한계 변화 | `docs/roadmap.md` |

- 영어판 README를 고치면 `README.ko.md`도 맞춘다. 문서에 적은 상수·경로는 코드와 일치하는지 확인한다.
- 문서만 바꾼 변경은 코드 테스트를 다시 돌릴 필요 없지만 링크는 확인한다. 상세 설명은 `docs/`에 두고 이 파일에는 요약과 링크만 둔다.

## 스키마·버전 규칙
`@Entity`를 바꾸면 같은 커밋에서 `AppDatabase.VERSION` 증가 + `Migrations.kt`의 Migration 추가(`ALL_MIGRATIONS`에 등록) + `app/schemas` JSON 커밋 + `MigrationTest` 보강. 업그레이드 경로에 destructive 폴백 금지. 설정·파일 레이아웃 변경은 `AppUpgrade`, 파생 데이터 변경은 `PipelineKeys`. 앱 버전은 `app/build.gradle.kts`의 `appVersionName` 한 곳에서 올린다. 자세히: `docs/development.md`.

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

## 앱 구조 (요약)
이미지·텍스트·문서(PDF/Office)·음성·영상을 인덱싱하고 시맨틱 + 키워드로 검색한다. 모델은 동반 앱 OndolSaem(별도 레포)이 받고 실행한다. 눈소리는 `ml/ModelHost`(SDK 클라이언트)로만 접근하며 INTERNET 권한이 없다. 문자열은 `values/strings.xml`(en)과 `values-ko/strings.xml`(ko)의 키가 같아야 한다(`StringsParityTest`).
- 데이터 `data/db`(`items` + `segments`, int8 벡터), 소스 `data/media`, 파이프라인 `indexing`(`IndexRunner.sync()`/`process()`, `pipelineKey`로 종류별 재처리), 검색 `search`(그룹별 상대 점수 + 키워드), 자동 인덱싱 `AutoIndexScheduler`, 모델 접근 `ml/ModelHost`(OndolSaem 클라이언트).
- 채팅: `chat/`(`ChatController`, `ChatTools`: search_index·read_file, `ContextBudget`), `chat/schedule/`(예약 작업: 알람·워커·파일 트리거), `ml/llm/`(`ChatModel` 인터페이스, 실행은 OndolSaem), `ui/chat/`. 웹 검색·fetch 도구는 후순위(roadmap). 자세히: `docs/chat.md`.
- 자세히: `docs/architecture.md`, `docs/indexing.md`, `docs/search.md`, `docs/models.md`.

## OndolSaem (모델 호스트)
모델 다운로드·로딩·상주는 별도 레포 `taehunkim/ondolsaem`(앱 OndolSaem/온돌샘)이 맡는다(0.8.0). 활성 모델 id는 `ModelHost.refresh()`가 설정에 미러링하고 `pipelineKey`에 들어가 모델 변경 시 재인덱싱된다. E2E·계측 테스트에는 기기에 OndolSaem 설치 + 모델 다운로드가 필요하다(`ModelHostTest`는 없으면 건너뜀). SDK와 같이 고칠 때는 형제 폴더 `../ondolsaem`에 체크아웃하고 `local.properties`에 `ondolsaem.dir=../ondolsaem`(composite build). 자세히: `docs/development.md`.

## 빌드 전 준비
- 없음(0.8.0부터 네이티브 ML 의존성은 OndolSaem에 있다. OndolSaem 쪽은 그 레포의 `scripts/fetch-sherpa-onnx.sh`).

## 조사 결과(Phase 0, 실측)
- EmbeddingGemma 2 **740M만 오디오** 지원. `EmbeddingEngineConfig(audioBackend=…)`가 있어야 하고, 입력은 **WAV 바이트(16kHz mono)**. 한 입력은 **1024토큰 ≈ 40초**까지(30초 창 + 5초 겹침 사용).
- `InputData.Image` 여러 장 리스트는 **벡터 1개**를 돌려준다(프레임별 평균과 코사인 0.90, 순서 무관). 클립당 최대 8장, 프레임당 `visionTokensPerImage=70`.
- sherpa-onnx(SenseVoice int8 + Silero VAD)는 에뮬레이터에서 실시간의 ~2% 속도, 한국어·영어 인식 양호. 모델은 GitHub 릴리스 tar.bz2(163MB)라 OndolSaem이 받아 푼다. (Phase 0 측정은 모두 지금 OndolSaem 안에서 돌아가는 엔진 기준)

## 실제 모델 E2E (에뮬레이터)
- 테스트 파일은 커밋하지 않는다. 생성: `scripts/fetch-photo-fixtures.sh`, `uv run --with pillow scripts/make-text-fixtures.py`, `python3 scripts/make-doc-fixtures.py`, `uv run --with reportlab --with pillow --with python-docx --with python-pptx --with openpyxl scripts/make-office-fixtures.py`, `scripts/make-audio-fixtures.sh`(+ 영상은 ffmpeg로 장면 3개 mp4).
- `scripts/e2e-setup.sh`(UI 구동 설정)는 `adb uiautomator`가 막히면 못 쓴다. 그 경우 mobile-mcp로 직접 진행. 검색은 `scripts/ui-search.sh "질의"`(영어) / mobile-mcp 입력 후 `scripts/search-result.sh`(한국어)로 결과 파일명을 확인.
- 주의: `./gradlew connectedDebugAndroidTest`는 끝나고 앱을 제거한다(모델은 OndolSaem에 있어 남는다. OndolSaem의 계측 테스트는 OndolSaem을 지워 모델도 사라짐). 계측 테스트는 기기의 앱 설정에 의존하지 않게 작성한다.
- 에뮬레이터는 CPU 추론. 실기기는 GPU 우선, 실패 시 CPU 폴백.

## 한계
- Office 문서는 폰에서 렌더링할 수 없어 텍스트 + 생성한 텍스트 카드 이미지만 쓴다. 구형 .doc/.ppt/.xls, hwp는 미지원.
- 벡터는 메모리 전체 탐색(int8): 약 30만 세그먼트가 목표 규모. 키워드는 SQL LIKE(느려지면 FTS5 trigram 검토).
- 영상은 장면/길이 기준 클립 임베딩이라 짧은 순간(몇 초 미만)은 놓칠 수 있다. 정밀도는 Settings에서 조절.
