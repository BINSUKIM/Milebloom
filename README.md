# Milebloom

**당신은 달리고, 차는 풍경을 그립니다.** — *Every mile, painted by your car.*

Milebloom은 차가 아는 것들 — 와이퍼가 움직인 시간, 헤드램프를 켠 밤, 창문을 열어둔 바람 — 으로 하나의 풍경을 그리고, 운전이 쌓일수록 그 풍경이 자라나는 차량 내장 앱입니다.

Pleos Connect(AAOS)용 인포테인먼트 앱이며, 모바일 앱이 아닙니다. 차와 풍경은 카메라 영상이 아니라 앱이 그리는 그림입니다.

> **이 저장소는 1차 제출용 프로토타입입니다.** 위 소개는 완성된 제품의 설명이고, 지금 동작하는 범위는 아래 [구현 범위](#구현-범위)에 따로 적었습니다. 동작 확인은 모두 **Pleos Connect Emulator v2.1.0**에서 했습니다. 실차에서는 시험하지 못했습니다.

## APK 위치

- [`apk/김수빈_Milebloom_release.apk`](apk/%EA%B9%80%EC%88%98%EB%B9%88_Milebloom_release.apk) — 서명된 release 빌드, 약 26 MB
- 패키지 `com.milebloom.app`, 최소 API 34

## 실행 방법

에뮬레이터 준비는 [테스트 환경 및 필요한 설정](#테스트-환경-및-필요한-설정)을 먼저 보세요.

### APK로 실행 (권장)

```bash
adb install -r apk/김수빈_Milebloom_release.apk
```

기기 앱 목록에서 **Milebloom**을 실행합니다. 앱은 시동만으로 켜지지 않으며 사용자가 직접 실행합니다. 첫 실행 때 안내 후 권한 창이 두 번 뜨니 모두 허용하세요. 위치(주차 지점 조회)와 차량 속도(정차 판별) 권한입니다. 속도 권한은 시스템 창에 '위치 정보'로 표시됩니다.

이미 다른 서명의 빌드가 설치돼 있으면 설치가 거부됩니다. 먼저 지우세요.

```bash
adb uninstall com.milebloom.app
```

### 소스에서 빌드

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew assembleDebug
```

```bash
./gradlew assembleRelease
```

| 결과물 | 경로 | 비고 |
|---|---|---|
| debug | `app/build/outputs/apk/debug/app-debug.apk` | 바로 설치 가능. 신호 패널·시험 버튼·검증 화면 포함 |
| release | `app/build/outputs/apk/release/app-release-unsigned.apk` | **서명 키가 저장소에 없어 서명 없이 만들어지며, 그대로는 설치되지 않습니다.** 설치용 release는 `apk/`의 파일을 쓰세요 |

서명 설정은 `~/.milebloom/signing.properties`가 있을 때만 적용되며, 없어도 빌드는 성공합니다.

## 테스트 환경 및 필요한 설정

### 1. 빌드 도구

| 항목 | 값 |
|---|---|
| Android Studio | 2026.2 (AGP 9.4.1을 지원하는 버전) |
| JDK | 25 — Android Studio 내장 JBR이면 됩니다 |
| Android SDK | Platform 37 (`android.car` 선택 라이브러리 포함) |
| Gradle | Wrapper 9.6.0 (자동 다운로드) |

터미널에서 빌드하면 위치를 직접 알려 줘야 합니다. macOS 예시입니다. Android Studio로 프로젝트를 열면 SDK 위치(`local.properties`)는 자동으로 만들어집니다.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

### 2. Pleos SDK

**따로 받을 파일도, 넣을 인증 정보도 없습니다.** Vehicle·FusedLocation·SpeechToText·TextToSpeech SDK는 `settings.gradle.kts`에 적힌 Pleos Maven 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 빌드 때 자동으로 받습니다. 인터넷 연결만 필요합니다. NaviHelper SDK는 쓰지 않습니다.

### 3. 에뮬레이터

[Pleos 시작하기 문서](https://document.pleos.ai/en/docs/pleos-only/getting-started)를 따릅니다.

1. Android Studio **SDK Manager > SDK Update Sites**에 Pleos가 안내한 Pleos Connect Emulator 다운로드 URL을 추가합니다.
2. **SDK Platforms**에서 API 34 Pleos Connect 시스템 이미지를 설치합니다.
3. **Device Manager > Create device > Automotive**에서 Pleos Connect 기기를 만듭니다. 문서 기준 Cold boot, RAM 4 GB입니다.

확인에 쓴 환경은 Apple Silicon Mac, `Pleos_Connect_v2.1.0` AVD(API 34, arm64-v8a)입니다.

앱은 에뮬레이터의 **user 10**으로 돕니다. `pm` 명령과 브로드캐스트에는 `--user 10`을 붙여야 합니다.

## Virtual Console 시연 절차

에뮬레이터 안 웹 서버인 Virtual Console로 차량 신호를 넣습니다.

```bash
adb forward tcp:18088 tcp:8088
```

```bash
open http://127.0.0.1:18088
```

**브라우저의 Console 탭을 앞에 띄워 두세요.** 닫혀 있거나 탭이 뒤로 가면 주입이 503 오류로 거부되거나 조용히 무시됩니다. 주입 전에 상태를 확인할 수 있습니다.

```bash
curl -s http://127.0.0.1:18088/vehicle/state
```

release APK 기준 순서입니다. 앱을 켜고 기어 P에서 시작합니다.

| # | Console 요청 | 화면에서 볼 것 |
|---|---|---|
| 1 | 앱 실행 (기어 P) | 멈춰 있는 그림 풍경과 그 앞의 차 |
| 2 | `POST /vehicle/gear?gear=Drive` | 주행 톤으로 바뀝니다. **속도가 0이면 정차로 판정해 아직 흐르지 않습니다** |
| 3 | `POST /vehicle/controls?speed_kph=40` | 풍경이 5초에 걸쳐 흐르기 시작하고 바퀴가 돕니다 |
| 4 | `POST /vehicle/lights?feature=headlamp_mode&value=LowBeam` | 차 헤드램프가 켜지고, 낮이었다면 약 20초에 걸쳐 밤 톤으로 바뀝니다 |
| 5 | `POST /vehicle/body?window_fl=100` | 그림 속 차의 앞 창문이 열립니다 |
| 6 | `POST /vehicle/body?front_wiper=Low` 후 30초 넘게 기다린 다음 `front_wiper=Off` | 화면 변화는 없습니다. 비로 집계되어 8번의 색이 달라집니다 |
| 7 | `POST /vehicle/controls?speed_kph=0` | 약 2초 뒤 정차로 판정하고 6초에 걸쳐 흐름이 멈춥니다 |
| 8 | `POST /vehicle/gear?gear=Park` | 하늘이 오늘의 색으로 물듭니다. **Console의 AMBIENT 패널에서** 색이 바뀝니다 |

예시:

```bash
curl -X POST "http://127.0.0.1:18088/vehicle/gear?gear=Drive"
```

- 에뮬레이터 화면에는 실내 조명이 없습니다. 조명 색은 Console 스와치와 `vehicle/state`의 `ambient_light`로 확인합니다.
- 앱은 조명의 색만 바꾸고 스위치와 밝기는 건드리지 않습니다. 색은 기어 P 체결 때 한 번만 씁니다. 주행 중에는 바꾸지 않습니다.
- 에뮬레이터의 주행거리는 저절로 늘지 않습니다. 그래서 곧은 길 비율은 항상 최댓값으로 계산됩니다. 이 계산은 단위 테스트로만 확인했습니다.
- Console의 속도 모의는 실행마다 다르게 움직였습니다. 40을 넣으면 그대로 유지될 때도, 약 20초에 걸쳐 0으로 줄어들 때도 있고, `speed_kph=0`이 바로 먹지 않을 때도 있습니다. 3·7번 전후로 `vehicle/state`의 `controls.speed_kph`를 확인하세요. 줄어들면 3번을 다시 보내고, 안 멈추면 감속을 기다립니다. P로 바꿔도 속도 값이 남을 수 있으니 다시 시연할 때는 먼저 0인지 확인합니다.
- 낮·밤은 **서울 기준 실제 해 진 시간**으로 정합니다. 해가 진 뒤 시험하면 처음부터 밤 톤이고, 에뮬레이터 상태 표시줄의 시계와는 다를 수 있습니다.

**신호 수신 확인** — 앱을 켠 직후 로그에서 신호별 지원 여부를 볼 수 있습니다. 기대하는 결과는 `지원 …: Supported`이고 `예외` 로그가 없는 것입니다. `SPEED`만 권한을 허용하기 전에는 `Unsupported`로 나오고, 허용하면 `Supported`로 바뀝니다.

```bash
adb logcat -s MBSIG:I MBVEH:E
```

**내비 옆 분할 화면** — 앱 오른쪽 끝 손잡이(⋮)를 왼쪽으로 끌면 열립니다. 다시 끌면 앱이 사라지므로 앱 목록에서 다시 실행합니다.

**debug 빌드 전용 조작** — 해·달·차종·성장 단계를 바꿔 볼 수 있습니다. release에는 없습니다.

```bash
adb shell am broadcast --user 10 -a com.milebloom.app.SCENE --es sun down --ei stage 7 --ei month 4 --es car TUCSON --ez hud false
adb shell am broadcast --user 10 -a com.milebloom.app.SCENE --ei uxActive 511   # 제한 강화 흉내 → Lv3, -1이면 해제
```

## 구현 범위

**1차 제출본의 범위는 주행 화면 프로토타입과 SDK 연동 계층입니다.** 전체 기능 통합은 2차 심사를 목표로 합니다.

### 동작함 (에뮬레이터에서 확인)

| 기능 | 내용 |
|---|---|
| 기어로 나뉘는 화면 | AAOS `GEAR_SELECTION`으로 승차(P)·주행(D)·도착(P) 구분. R·N은 경계로 치지 않습니다 |
| 주행 풍경 | 벡터 에셋으로 그린 레이어 풍경이 30 fps로 흐름. 단독·분할 화면 |
| 차량 신호 수신 | 기어, UX 제약, 주행거리, 와이퍼, 헤드램프, 창문, 도어, 조향, 브레이크, 앰비언트, 속도 |
| 신호 → 그림 | 헤드램프·해 진 시간 → 밤 톤, 창문 → 그림 속 차 창문, 정차 → 흐름 멈춤 |
| 오늘의 색 | 달·비·밤·곧은 길로 색을 계산해 P 체결 때 앰비언트 라이트에 1회 적용하고 하늘을 물들임. 달린 집계는 메모리에만 있음 |
| P 지점 좌표 | FusedLocation 1회 조회. 지금은 로그로만 남김 |
| 주행 중 표시 | `distractionOptimized` 선언으로 D에서 앱이 가려지지 않음 |
| 주행 제한 강화 → 최소 화면 | 플랫폼 UX 제한(`activeRestrictions`)에 평소 주행(`NO_VIDEO`·`NO_SETUP`) 밖의 비트가 켜지면 즉시 세로 그라데이션 한 장(Lv3)으로 바꾸고, 평소로 돌아오면 원래 화면으로 돌아옵니다. 전환 연출은 없습니다 |

### 1차 이후로 미룸 (이 APK에 없음)

| 항목 | 현재 상태 |
|---|---|
| **저장·숲 성장** | 저장소가 없습니다. **숲은 자라지 않으며, 화면의 숲과 들판은 시연용 고정값(5단계)입니다.** 앱을 다시 켜면 오늘의 색도 첫 P 전까지 사라집니다 |
| **주차 화면·전국 지도** | P에서는 풍경만 보입니다. 도착 인사, 기록 열람, 지도, 설정 화면은 없습니다 |
| **음성** | STT·TTS 래퍼 코드만 있고 음성 버튼과 음성 기록은 없습니다. debug 빌드에 시험 버튼이 있습니다. 앱 안 음성 인식은 에뮬레이터에서 확인하지 못했습니다 |
| **표시 수준 Lv2** | 사용자가 고르는 정적 화면(Lv2)은 없습니다. 제한 강화 때의 Lv3 전환만 있습니다 |
| **차종 선택** | 기본 아반떼 고정입니다. 그랜저·투싼 도형은 있고 debug 조작으로만 바꿉니다 |
| **일부 에셋** | 가는 나무·개울은 자리표시, 여름·겨울 들판은 봄·가을 형태를 빌려 씁니다 |

### 확인하지 못한 것

- **실제 제한 강화로 Lv3 전환을 확인하지 못했습니다.** 에뮬레이터 설정에서 평소보다 강한 제한(`0x1ff`)은 주행 상태를 알 수 없을 때만 걸리고, 그 상태는 Virtual Console로 만들 수 없습니다. 같은 값을 debug 빌드에 주입해(`--ei uxActive 511`, 해제 `-1`) 전환을 확인했고, 판정 규칙은 단위 테스트로 확인했습니다
- 실차 동작과 실차 성능. 위 수치와 동작은 모두 에뮬레이터 결과입니다
- 실차에서 꺼진 실내 조명에 색을 썼을 때의 동작
- 등록 카테고리(Lifestyle)에서 주행 중 표시가 App Market 심사를 통과하는지

## 개인정보와 네트워크

이 프로토타입은 위치·음성을 차 밖으로 보내지 않고 저장하지도 않습니다. 앱 코드에 네트워크 호출이 없습니다. APK의 `INTERNET` 권한은 SDK 라이브러리 매니페스트가 합쳐 넣은 것입니다. 앰비언트 라이트 제어(`CONTROL_CAR_LIGHTS`)는 개발 단계에서는 승인 없이 쓸 수 있고, 실제 배포 때는 App Market 심사 승인이 필요합니다.

## 프로젝트 구조

| 경로 | 내용 |
|---|---|
| `app/src/main/java/com/milebloom/app/vehicle` | Vehicle SDK·AAOS 신호 수집, 정차 판별 |
| `.../mode` | 기어 P/D 상태 머신 |
| `.../render` | 풍경 렌더링 (`LandscapeShapes`·`CarShapes`·`MbPalette`는 생성된 파일) |
| `.../ambient` | 오늘의 색 계산, 실내 조명 연동 |
| `.../location` · `.../speech` | FusedLocation 1회 조회, STT·TTS 래퍼 |
| `app/src/debug` | SDK 검증 화면 등 **debug 빌드 전용** 코드. release에는 들어가지 않습니다 |
| `app/src/test` | 단위 테스트 |
| `tools` | 차 도형 생성기. 입력 SVG는 이 저장소에 없습니다. 빌드에는 필요 없고, 생성 결과 `CarShapes.kt`가 들어 있습니다 |

코드 주석의 `D-0xx`, `30번 문서` 같은 표시는 비공개 기획 문서의 항목 번호입니다.

## release 빌드 참고

release는 R8을 끄고 빌드합니다. 켜면 Vehicle SDK가 kotlin-reflect로 읽는 부분이 깨져 주행거리를 받지 못합니다.
