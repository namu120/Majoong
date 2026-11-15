# 마중 (Majoong)

가족이나 지인을 데리러 갈 때, 기다리는 사람이 고른 만남 장소를 차량 내비게이션에 반영하고 준비 상태는 운전자에게 음성으로, 차의 위치와 접근 방향은 기다리는 사람에게 알려 주는 Pleos Connect 픽업 앱입니다.

## 구성과 현재 상태

| 폴더 | 내용 | 상태 |
| --- | --- | --- |
| `server/` | 동기화 서버 (Node 한 파일, 외부 의존성 없음) | 동작. 계약 테스트 5개 통과 |
| `server/public/index.html` | 탑승자 모바일 웹 (설치 없이 초대 링크로 열림) | 동작 |
| `server/public/car.html`, `demo.html` | 차량 화면 **웹 프로토타입**과 시연 페이지 | 동작. 내비게이션·위치·기어·비상등은 모의 |
| `vehicle-app/` | Pleos Connect 차량 앱 (Kotlin, Jetpack Compose) | 개발 중. 아래 "차량 앱" 참고 |
| `apk/` | 빌드된 APK | 아래 "APK" 참고 |

시연 영상과 `demo.html`에서 보이는 차량 화면은 웹으로 만든 프로토타입입니다. 서버와 탑승자 웹, 그리고 만남 장소 변경 규칙(운전자 동의 → 내비 반영 성공 확인 → 양쪽 확정)은 실제로 동작하며, 내비게이션·차량 위치·기어·비상등은 모의 데이터입니다. 음성 안내는 소리 대신 화면 아래 자막으로 표시합니다.

## 실행 방법

### 시연 페이지 (Node 20 이상만 있으면 됨)

```bash
cd server
node server.mjs
```

브라우저에서 `http://localhost:8787/demo` 를 엽니다. 왼쪽이 차량 화면, 오른쪽이 탑승자 휴대폰입니다.

1. 오른쪽 "QR 찍기" → 이름을 넣고 동의 후 연결
2. 차량 화면에서 "픽업" → 만남 지점 선택 → "안내 시작"
3. 아래 버추얼 콘솔(모의)에서 기어 `D` → 탑승자 지도에서 차량이 움직임
4. 탑승자가 "만남 장소 변경" → 차량이 묻고 "네" → 내비 목적지가 바뀐 뒤 양쪽에 확정 표시
5. 탑승자가 "나와 있어요" / "늦어요" → 차량 화면과 자막에 전달
6. 도착 근처에서 "탑승 종료" → 위치 공유 종료

### 서버 테스트

```bash
cd server
node --test test.mjs
```

### 차량 앱

```bash
cd vehicle-app
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease -Pmajung.server=https://<서버 주소>
```

Pleos Connect SDK는 `https://nexus-playground.pleos.ai/repository/maven-releases/` 에서 받습니다(별도 인증 없음). 에뮬레이터에서 개발 PC의 로컬 서버는 `http://10.0.2.2:8787` 입니다.

## 테스트 환경과 필요 설정

- 서버·탑승자 웹·시연 페이지: macOS, Node 22, Chrome에서 확인
- 차량 앱 대상 환경: Pleos Connect Emulator v2.1.0 이상 (Android API 34 시스템 이미지), 버추얼 콘솔
- 화면 규격: 세로 한 창(split view) 840 × 1240을 기준으로 설계했고, 창 너비에 비례해 배치합니다. 예시 폼팩터는 2560 × 1440 화면에서 내비게이션과 나란히 놓인 형태입니다.
- 사용 SDK와 권한: NaviHelper 3.0.5 (`NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`), FusedLocation 1.1.2 (`FUSED_LOCATION`), TextToSpeech 3.3.1 (`TTS_SERVICE`), SpeechToText 3.3.1 (`STT_SERVICE`), Vehicle 2.3.2 (`CAR_LIGHTS`, `CAR_DRIVE`)
- **차량 앱은 아직 Pleos Connect 에뮬레이터에서 실행해 검증하지 못했습니다.** 에뮬레이터 검증(내비 반영 콜백, 주행 중 음성, 차량 신호 지원 여부)은 2차 개발 기간의 첫 과제입니다.

## APK

`apk/` 폴더를 확인해 주세요. 파일이 있다면 빌드만 통과한 상태이며 에뮬레이터 실행 검증 전입니다.

## 동작 규칙

만남 장소 변경이 이 앱의 핵심입니다.

1. 내비게이션 반영 성공을 확인하기 전에는 새 지점을 확정하지 않습니다. 운전자가 동의해도 반영에 실패하면 기존 지점을 유지합니다.
2. 거절, 무응답, 인식 불명확은 모두 기존 지점 유지입니다.
3. 가장 최근 요청만 유효합니다. 늦게 도착한 응답은 현재 목적지를 덮어쓰지 못합니다.
4. 같은 안내는 한 번만 합니다.

차량 쪽 로직은 `vehicle-app/app/src/main/java/app/majung/car/core/PickupMachine.kt`, 서버 쪽은 `server/server.mjs`에 있고, 각각 `PickupMachineTest.kt`와 `server/test.mjs`가 위 규칙을 확인합니다.

## 위치 정보

- 차량 위치는 진행 중인 픽업의 탑승자에게만 보입니다.
- 서버는 최신 좌표 하나만 메모리에 두고, 종료·취소·만료 시 지웁니다. 이동 이력을 저장하지 않습니다.
- 탑승자의 위치는 수집하지 않습니다. 탑승자가 고른 만남 지점과 준비 상태만 전달합니다.
- 초대 수락 화면에서 공유 범위와 기간을 알리고 동의를 받습니다.
