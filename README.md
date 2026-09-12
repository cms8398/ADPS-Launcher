# ADPS Launcher

ADPS 디스플레이 제품을 위한 가로형 Android 런처 프로토타입입니다.

터치 기반 산업용 디스플레이 환경에서 자주 사용하는 기능을 한 화면에 제공하며, 로컬·USB 미디어 재생과 스마트폰 원격 제어를 지원합니다.

이 프로젝트는 [Literal Launcher](https://github.com/256x/launcher)를 기반으로 제작되었습니다.

## 현재 버전

* Version name: `1.7.0`
* Version code: `23`
* Minimum SDK: API 28(Android 9.0)
* Target SDK: API 36

## 주요 기술

* Kotlin
* Jetpack Compose
* Material 3
* Android MediaStore
* AndroidX Media3 ExoPlayer
* Gemini API
* ZXing QR Code
* 로컬 HTTP 서버 기반 스마트폰 리모컨

## 대상 환경

* Android 9.0 이상
* 가로 화면 고정
* 기준 해상도: 1366 × 768
* 직접 터치 입력
* 검은색 전체 화면 UI
* Google Play Store가 없는 장비 환경 고려

## 현재 구현 기능

* Android 기본 홈 런처 등록
* 시스템 바가 숨겨진 전체 화면 실행
* 화면 크기와 글꼴 배율에 대응하는 가로형 홈 UI
* 설치된 애플리케이션 목록 조회 및 실행
* 현재 시각과 날짜 표시
* 도시명 기반 날씨 조회
* 기존 `TV` 앱을 이용한 HDMI 외부입력 실행
* YouTube 앱 실행 및 웹사이트 대체 실행
* 브라우저, 지도 및 Android 시스템 설정 바로가기
* Gemini 기반 텍스트 AI Assistant
* OLED 화면 보호기
* 로컬·USB 사진 및 동영상 통합 Gallery
* 선택형 Media Show
* 같은 Wi-Fi에서 사용하는 스마트폰 웹 리모컨

## 홈 화면

홈 화면 오른쪽에는 다음 아홉 개의 기능 타일이 표시됩니다.

* Weather
* HDMI
* YouTube
* Browser
* Maps
* Settings
* AI Assistant
* Screen Saver
* Gallery

왼쪽에는 시계, 날짜, 날씨 정보와 `All Apps` 버튼이 표시됩니다.

화면 크기와 글꼴 배율에 따라 여백, 타일 간격, 글자 크기 및 왼쪽 정보 영역의 크기가 자동으로 조정됩니다.

## 기능별 동작

### HDMI

HDMI 타일을 누르면 기기에 설치된 실행 가능한 앱을 조회한 뒤, 표시 이름이 `TV`인 앱을 찾아 실행합니다.

앱 이름은 대소문자를 구분하지 않으며 앞뒤 공백을 제거한 뒤 비교합니다.

```text
ADPS Launcher의 HDMI 타일
→ 설치된 TV 앱 검색
→ 기존 TV 앱 실행
→ TV 앱에 설정된 외부입력 표시
```

사용하기 전에 기존 런처 또는 `All Apps`에서 `TV` 앱을 열고 외부입력을 HDMI로 설정해야 합니다.

TV 앱을 찾지 못하거나 실행하지 못한 경우에는 원인을 구분한 안내창이 표시됩니다.

현재 구현은 보드에 사전 설치된 TV 앱의 HDMI 전환 기능을 재사용하는 방식입니다. 제조사 API 또는 Android TV Input Framework를 통한 직접 HDMI 전환은 아직 구현되지 않았습니다.

### Weather

도시명을 입력하면 Open-Meteo 서비스를 통해 현재 날씨를 조회합니다.

입력한 도시는 앱 설정에 저장되며, 날씨를 조회하려면 인터넷 연결이 필요합니다.

### YouTube

YouTube 앱이 설치되어 있으면 앱 실행을 먼저 시도합니다.

YouTube 앱이 없으면 설치된 웹 브라우저를 통해 YouTube 웹사이트를 엽니다.

### Browser

기본 웹 브라우저에서 Google 웹사이트를 엽니다.

### Maps

설치된 지도 앱을 먼저 실행합니다.

지도 앱을 실행할 수 없으면 웹 브라우저에서 네이버 지도를 엽니다.

### Settings

Android 시스템 설정 화면을 엽니다.

### AI Assistant

텍스트 질문을 입력해 Gemini의 답변을 받을 수 있습니다.

AI Assistant를 사용하려면 인터넷 연결과 Gemini API 키가 필요합니다. 프로젝트의 `local.properties`에 다음 항목을 추가합니다.

```properties
GEMINI_API_KEY=your_api_key
```

`local.properties`는 Git 추적 대상에서 제외되어 있으므로 API 키를 소스 코드나 GitHub 저장소에 직접 작성하지 않습니다.

### Screen Saver

홈 화면에서 5분 동안 입력이 없으면 화면 보호기가 자동으로 실행됩니다.

OLED 번인 위험을 줄이기 위해 시계와 날짜의 표시 위치가 20초마다 이동합니다. 화면을 터치하면 홈 화면으로 돌아갑니다.

홈 화면의 Screen Saver 타일을 눌러 직접 실행할 수도 있습니다.

### Gallery와 Media Show

Android MediaStore가 색인한 로컬 저장소와 USB 저장장치의 사진 및 동영상을 함께 표시합니다.

* `All`, `Local`, `USB` 출처 필터
* 사진 크게 보기
* Media3 ExoPlayer 기반 동영상 재생
* 사진과 동영상 다중 선택
* 선택한 순서대로 Media Show 반복 재생
* 사진 표시 간격 3초·5초·10초 설정
* USB 장착·제거 시 목록 및 선택 항목 갱신

처음 사용할 때 Android 버전에 맞는 사진 및 동영상 접근 권한을 허용해야 합니다.

### Smartphone Remote Control

Gallery 화면에 진입하면 안드로이드 기기에서 로컬 원격 제어 서버가 시작됩니다.

1. 안드로이드 기기와 스마트폰을 같은 Wi-Fi에 연결합니다.
2. Gallery 상단의 `Remote` 버튼을 누릅니다.
3. 화면에 표시된 QR 코드를 스캔하거나 IP 주소를 스마트폰 브라우저에 입력합니다.
4. 주소를 직접 입력했다면 화면에 표시된 6자리 PIN을 입력합니다.
5. 디스플레이에서 콘텐츠를 선택한 뒤 스마트폰에서 Media Show를 제어합니다.

스마트폰에서 다음 기능을 사용할 수 있습니다.

* Media Show 시작 및 종료
* 재생 및 일시정지
* 이전 및 다음 콘텐츠 이동
* 사진 표시 간격 변경
* 현재 재생 상태 확인

기본 포트는 `8765`입니다. Gallery 화면을 벗어나면 원격 제어 서버가 종료됩니다.

인터넷 연결은 필요하지 않지만 두 장치가 서로 통신할 수 있는 동일한 로컬 네트워크에 연결되어야 합니다. 공유기의 AP Isolation 또는 Client Isolation 기능이 활성화되어 있으면 접속이 차단될 수 있습니다.

### All Apps

기기에 설치된 실행 가능한 애플리케이션을 조회해 목록으로 표시합니다.

에뮬레이터와 실제 장비의 설치 앱 구성이 다르므로 표시되는 목록도 달라질 수 있습니다.

## 빌드 및 실행

1. Android Studio에서 프로젝트 최상위 폴더를 엽니다.
2. Gradle Sync를 완료합니다.
3. 필요한 경우 `local.properties`에 Gemini API 키를 설정합니다.
4. Android 9 이상 에뮬레이터 또는 실제 장비를 선택합니다.
5. `app` 실행 구성을 빌드하고 설치합니다.
6. Android에서 기본 홈 앱을 묻는 경우 ADPS Launcher를 선택합니다.

Windows에서 디버그 APK를 빌드하려면 다음 명령을 사용할 수 있습니다.

```powershell
.\gradlew.bat assembleDebug
```

macOS 또는 Linux에서는 다음 명령을 사용합니다.

```bash
./gradlew assembleDebug
```

생성된 APK 경로는 다음과 같습니다.

```text
app/build/outputs/apk/debug/app-debug.apk
```

## HDMI 테스트 방법

1. 기존 `TV` 앱에서 외부입력을 HDMI로 설정합니다.
2. PC의 HDMI OUT을 안드로이드 보드의 HDMI IN에 연결합니다.
3. ADPS Launcher에서 HDMI 타일을 누릅니다.
4. 기존 TV 앱이 실행되는지 확인합니다.
5. PC 화면이 디스플레이에 표시되는지 확인합니다.
6. 뒤로가기 또는 홈 동작으로 ADPS Launcher에 복귀되는지 확인합니다.

## 현재 제한 사항

* HDMI 기능은 표시 이름이 `TV`인 기존 보드 앱에 의존합니다.
* ADPS Launcher가 HDMI 입력을 직접 선택하거나 제어하지는 않습니다.
* UI에는 화면 크기별 조정 기능이 적용되어 있지만 기준 해상도는 1366 × 768입니다.
* 모든 제품 해상도와 글꼴 배율에 대한 검증은 아직 완료되지 않았습니다.
* AI Assistant는 현재 텍스트 입력만 지원합니다.
* 날씨, AI Assistant 및 웹 기반 기능은 인터넷 연결이 필요합니다.
* Google Play Store가 없는 장비에서는 YouTube 등 일부 앱이 제공되지 않을 수 있습니다.
* 스마트폰 리모컨은 두 장치 간 통신이 가능한 같은 로컬 네트워크가 필요합니다.

## 향후 개선 계획

* 실제 보드에서 TV 앱 실행 및 런처 복귀 동작 검증
* 기존 TV 앱에 의존하지 않는 HDMI 직접 전환
* 음성 입력 및 음성 기반 AI 제어
* 제품 해상도별 UI 검증과 최적화
* 앱 검색과 표시·숨김 기능
* 제품용 날씨 및 AI 서비스 구성
* 회사 로고와 최종 제품 디자인 적용

## 프로젝트 주요 구조

```text
app/src/main/
├─ AndroidManifest.xml
├─ java/fumi/day/literallauncher/
│  ├─ MainActivity.kt
│  ├─ AdpsLauncherApp.kt
│  ├─ GeminiApiClient.kt
│  ├─ MediaGalleryScreen.kt
│  └─ RemoteControl.kt
└─ res/
   ├─ drawable/
   ├─ mipmap-*/
   └─ values/
```

* `MainActivity.kt`: 전체 화면 런처 Activity
* `AdpsLauncherApp.kt`: 홈 화면과 주요 기능 연결
* `GeminiApiClient.kt`: Gemini API 요청 및 응답 처리
* `MediaGalleryScreen.kt`: Gallery, 동영상 재생 및 Media Show
* `RemoteControl.kt`: 스마트폰용 로컬 웹 리모컨 서버

## 기반 프로젝트 및 라이선스

이 프로젝트는 Literal Launcher를 기반으로 수정되었습니다.

* 원본 저장소: https://github.com/256x/launcher
* 원본 라이선스: MIT License

원본 프로젝트의 저작권 및 라이선스 고지는 유지해야 합니다.

## 개발 상태

현재 개발 및 실제 장비 검증을 진행 중인 프로토타입입니다.
