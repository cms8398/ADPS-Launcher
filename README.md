# ADPS Launcher

ADPS 디스플레이 제품을 위한 가로형 Android 런처 프로토타입입니다.

이 프로젝트는 [Literal Launcher](https://github.com/256x/launcher)를 기반으로 하며, 터치 기반 산업용 디스플레이 환경에 맞게 수정되었습니다.

## 대상 환경

- Android 9.0
- Minimum SDK: API 28
- 가로 화면 고정
- 기준 해상도: 1366 × 768
- 직접 터치 입력
- 검은색 배경
- Google Play Store가 없는 장비 환경 고려

## 현재 구현 기능

- 기본 Android 홈 런처 기능
- 설치된 애플리케이션 목록 표시
- 앱 터치 실행
- 런처 설정 저장
- 홈 화면 고정 바로가기
- 시계 및 날짜 표시
- 도시명 기반 날씨 조회
- HDMI 임시 버튼
- YouTube 앱 실행 및 웹 대체
- 브라우저 바로가기
- 웹 지도 바로가기
- Android 시스템 설정 바로가기
- 로컬·USB 사진 및 동영상 통합 Gallery
- 선택형 Media Show
- 같은 Wi-Fi에서 사용하는 스마트폰 웹 리모컨

## 홈 화면 구성

현재 홈 화면에는 다음 메뉴가 표시됩니다.

- Weather
- HDMI
- YouTube
- Browser
- Maps
- Settings
- All Apps

## 기능별 동작

### Weather

사용자가 도시명을 입력하면 웹 API를 통해 해당 도시의 날씨 정보를 조회합니다.

현재 프로토타입은 도시 검색과 날씨 데이터 조회에 Open-Meteo 서비스를 사용합니다. 인터넷 연결이 필요합니다.

### HDMI

현재 HDMI 메뉴는 버튼과 안내창만 구현된 상태입니다.

실제 HDMI 입력 전환을 위해서는 보드 제조사가 제공하는 API, 시스템 서비스, Broadcast Intent, UART 명령, 전용 SDK 등의 하드웨어 제어 인터페이스가 필요합니다.

### YouTube

기기에 YouTube 앱이 설치되어 있으면 앱 실행을 먼저 시도합니다.

YouTube 앱이 없으면 설치된 웹 브라우저를 통해 YouTube 웹사이트를 엽니다.

### Maps

Maps 버튼은 설치된 웹 브라우저에서 웹 기반 지도 서비스를 엽니다.

지도 주소는 다음 파일에서 변경할 수 있습니다.

```text
app/src/main/java/fumi/day/literallauncher/AdpsLauncherApp.kt
```

### All Apps

현재 기기에 설치된 실행 가능한 애플리케이션 목록을 읽어 앱 목록 화면에 표시합니다.

따라서 에뮬레이터에 표시되는 앱 목록과 실제 ADPS 제품에 표시되는 앱 목록은 다를 수 있습니다.

### Gallery와 Media Show

Android MediaStore가 색인한 로컬 저장소와 USB 저장장치의 사진·동영상을 함께 표시합니다.

- `All`·`Local`·`USB` 출처 필터
- 사진 크게 보기와 동영상 재생
- 사진·동영상 다중 선택
- 선택한 순서대로 Media Show 반복 재생
- 사진 표시 간격 3초·5초·10초 설정
- USB 장착·제거 시 목록 및 선택 항목 갱신

### Smartphone Remote Control

Gallery 상단의 `Remote` 버튼을 누르면 안드로이드 보드에서 로컬 원격 제어 서버가 실행됩니다.

1. 안드로이드 보드와 스마트폰을 같은 Wi-Fi에 연결합니다.
2. Gallery의 `Remote` 버튼을 누릅니다.
3. 표시된 QR 코드를 스마트폰으로 스캔하거나 IP 주소를 브라우저에 입력합니다.
4. 직접 주소를 입력한 경우 화면에 표시된 6자리 PIN으로 연결합니다.
5. 디스플레이에서 Media Show 콘텐츠를 선택한 뒤 스마트폰에서 재생을 제어합니다.

스마트폰에서는 Media Show 시작·재생·일시정지·이전·다음·종료와 사진 표시 간격을 제어할 수 있습니다. 서버는 Gallery 또는 Media Show 화면이 열려 있는 동안에만 동작하며 인터넷 연결은 필요하지 않습니다.

기본 포트는 `8765`입니다. 공유기의 AP Isolation 또는 Client Isolation 기능이 켜져 있으면 같은 Wi-Fi에서도 기기 간 연결이 차단될 수 있습니다.

## 빌드 및 실행 방법

1. Android Studio에서 프로젝트 최상위 폴더를 엽니다.
2. Gradle Sync가 완료될 때까지 기다립니다.
3. Android 9 호환 에뮬레이터 또는 실제 장비를 선택합니다.
4. `app` 실행 구성을 선택합니다.
5. Android에서 기본 홈 앱을 묻는 경우 ADPS Launcher를 선택합니다.

## 현재 제한 사항

- UI는 1366 × 768 가로 화면을 기준으로 설계되었습니다.
- 일부 여백과 글자 크기는 고정된 `dp`, `sp` 값을 사용합니다.
- 여러 해상도에 대한 완전한 반응형 UI는 아직 적용되지 않았습니다.
- HDMI 입력 전환은 실제 하드웨어와 연결되지 않았습니다.
- 웹 기반 기능은 브라우저와 인터넷 연결이 필요합니다.
- 비GMS 장비에서는 Google 애플리케이션이 제공되지 않을 수 있습니다.
- 스마트폰 원격 제어는 보드와 스마트폰이 서로 통신할 수 있는 같은 로컬 네트워크가 필요합니다.

## 향후 개선 계획

- 화면 크기에 따른 반응형 레이아웃
- 홈 화면 바로가기 설정 기능
- 전체 앱 목록 관리자 전용 접근
- 앱 검색 기능
- 앱 표시 및 숨김 설정
- 제품용 날씨 API 구성
- 보드 전용 HDMI 입력 전환
- 회사 로고 및 최종 디자인 적용

## 프로젝트 주요 구조

```text
app/
└─ src/
   └─ main/
      ├─ AndroidManifest.xml
      └─ java/
         └─ fumi/
            └─ day/
               └─ literallauncher/
                  ├─ MainActivity.kt
                  ├─ AdpsLauncherApp.kt
                  ├─ MediaGalleryScreen.kt
                  └─ RemoteControl.kt
```

## 기반 프로젝트 및 라이선스

이 프로젝트는 Literal Launcher를 기반으로 제작되었습니다.

- 원본 저장소: https://github.com/256x/launcher
- 원본 라이선스: MIT License

원본 프로젝트의 저작권 및 라이선스 고지는 유지해야 합니다.

## 개발 상태

현재 개발 중인 프로토타입입니다.
