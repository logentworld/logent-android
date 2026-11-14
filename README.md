# Android for Logent

Logent에 Android 공통 기기 기능을 제공하는 독립 `.lpg` 플러그인이에요.
삼성·Google 전용 기능은 각각 다른 플러그인이 맡고, 여기서는 Android 공개 API만 써요.

- 저장소: <https://github.com/logentworld/logent-android> (`logent` 브랜치)
- 플러그인 id: `com.sleepysoong.logent.android`
- 플러그인 key: `sleepysoong:android`
- 구현 클래스: `com.sleepysoong.logent.android.AndroidPlugin`
- 공개 API: `com.sleepysoong.logent:logent-plugin-api`의 Android Context·권한 계약(4.5.0 기준)
- `logent-plugin.json`의 `entry`와 실제 구현 클래스 이름이 같아야 해요.

## 지원 기능

| 도구 | 접근 | 하위 에이전트 | 설명 |
|---|---|---|---|
| `android_device` | `READ_ONLY` | 공개 | 기기·배터리·저장소·네트워크·센서·음량·방해 금지 상태를 읽어요. |
| `android_control` | `WRITE` | 비공개 | 손전등·음량·클립보드·진동·배경화면·밝기·회전·설정 화면을 다뤄요. |
| `android_apps` | `WRITE` | 비공개 | 설치 앱 목록·실행·앱 설정·제거 확인·Play 스토어를 열어요. |
| `android_contacts` | `WRITE` | 비공개 | 연락처 검색·편집 화면·연락처 앱 열기. |
| `android_calendar` | `WRITE` | 비공개 | 일정 조회·편집 화면·캘린더 앱 열기. |
| `android_sms` | `WRITE` | 비공개 | 문자 조회·전송·작성 화면·기본 문자 앱 열기. |
| `android_location` | `READ_ONLY` | 비공개 | 마지막으로 알려진 위치·위치 설정 화면. |
| `android_media` | `WRITE` | 비공개 | 사진·동영상·오디오 조회, 갤러리·카메라·미디어 열기. |
| `android_notifications` | `READ_ONLY` | 비공개 | 방해 금지 접근 상태·앱 알림 설정·DND 설정 화면. |

모든 도구는 `action`을 필수로 받아요. 변경 동작은 `WRITE`로 선언하고, 파일·연락처·문자처럼
개인 데이터를 다루는 도구는 서브에이전트에 노출하지 않아요(`childVisible = false`).

### `android_device`

- `info`: 제조사·브랜드·모델·기기 코드·Android 버전·SDK·보안 패치·가동 시간·언어·시간대·
  화면 크기(px/dp)·밀도
- `battery`: 잔량 %, 충전 여부, 상태, 연결 방식, 온도(°C), 전압(mV), 건강 상태, 배터리 기술
- `storage`: 내부 저장소와 외부 저장소의 전체·사용 가능 바이트
- `network`: 전송 계층(Wi-Fi·셀룰러·이더넷·블루투스·VPN), 검증됨, Metered, VPN, 대역폭
- `sensors`: 센서 이름·제조사·타입·버전·전력·범위·해상도
- `volume_get`: 음악·알람·알림·통화 스트림의 현재/최대 음량
- `dnd_status`: 방해 금지 접근 권한 보유 여부와 현재 필터

배터리는 `ACTION_BATTERY_CHANGED` 스티키 브로드캐스트, 저장소는 `StatFs`, 센서는
`SensorManager`, 네트워크는 `ConnectivityManager`를 써요. 별도 런타임 권한은 필요 없어요.

### `android_control`

- `flashlight`: `on`/`off`/`toggle`. `CameraManager.setTorchMode`로 플래시가 있는 후면 카메라를 켜요.
  `FLASHLIGHT`는 설치 시 부여되는 일반 권한이라 실행 중 요청하지 않아요.
- `volume_set`: `stream`(music/alarm/notification/call)과 `percent`(0~100).
- `clipboard_get` / `clipboard_set`: 클립보드 텍스트를 읽거나 복사해요. 최대 20,000자예요.
  Android 10 이상에서는 앱이 화면에 없을 때 다른 앱이 복사한 내용을 읽지 못할 수 있어요.
- `vibrate`: `milliseconds`(1~5000, 기본 300).
- `wallpaper_color`: `#RGB`, `#RRGGBB`, `#AARRGGBB` 단색 배경화면.
- `brightness_set`: `percent`(0~100). `WRITE_SETTINGS` 특수 권한이 필요해요.
- `rotation_set`: `auto`/`portrait`/`landscape`. `WRITE_SETTINGS` 특수 권한이 필요해요.
- `open_settings`: `settings`에 `sound`, `display`, `battery`, `security`, `notifications`,
  `location`, `wifi`, `bluetooth`, `apps`, `dnd`, `accessibility`, `developer`, `storage`.

밝기·회전은 `Settings.System.canWrite`를 확인해요. 없으면 값을 바꾸지 않고
`설정 → 앱 → 특수 권한 → 시스템 설정 변경` 안내와 `android.settings.action.MANAGE_WRITE_SETTINGS`
액션을 오류로 돌려줘요. 몰래 우회하지 않아요.

### `android_apps`

- `list`: 패키지·라벨·버전·시스템 여부. `limit` 1~200(기본 50).
- `open`: 런치 액티비티가 있는 앱을 열어요.
- `info`: 앱 설정 화면(`ACTION_APPLICATION_DETAILS_SETTINGS`).
- `uninstall`: 패키지 제거 확인 화면(`ACTION_DELETE`). 실제 삭제는 사용자가 확인해요.
- `store`: `market://details?id=…`. Play 스토어가 없으면 웹 페이지로 폴백해요.
- `search`: `market://search?q=…`. Play 스토어가 없으면 웹 검색으로 폴백해요.

설치 앱 목록은 `PackageManager.getInstalledApplications(0)`을 써요. Android 11 이상의 패키지
가시성은 코어 앱이 선언한 `QUERY_ALL_PACKAGES`에 기대요.

### `android_contacts`

- `search`: `name` 또는 `phone`(하나 이상 필수)과 `limit`(1~50, 기본 20). `READ_CONTACTS`가 필요해요.
- `add`: `ACTION_INSERT`로 연락처 편집 화면을 열어요. `name`·`phone`·`email`을 extra로 넘기고
  저장은 사용자가 확인해요. 권한이 필요 없어요.
- `open`: 연락처 앱을 열어요.

### `android_calendar`

- `list`: `from`/`to` epoch 밀리초(기본 지금부터 7일), `limit`(1~100, 기본 20).
  `CalendarContract.Instances`를 조회하며 `READ_CALENDAR`가 필요해요.
- `add`: `ACTION_INSERT`로 일정 편집 화면을 열어요. `title`(필수), `start`/`end`(기본 지금부터
  1시간), `description`. 권한이 필요 없어요.
- `open`: 캘린더 앱을 오늘 날짜로 열어요.

조회 기간은 366일을 넘을 수 없어요.

### `android_sms`

- `list`: `thread`·`address`·`limit`(1~50, 기본 20). `READ_SMS`가 필요해요. 최근 문자를 최대 50개
  반환하고 본문은 300자에서 잘라요. 주소·시각·방향을 구조화하고 본문은 잘린 값만 줘요.
- `send`: `to`·`body`. `SEND_SMS`가 필요해요. 번호는 숫자와 앞자리 `+`만 남겨 검증하고,
  본문은 1~1000자만 허용해요. 160자 초과 본문은 `divideMessage`로 나눠 보내요.
  전송은 되돌릴 수 없으며 결과에 전송 완료를 명시해요.
- `compose`: 권한 없이 `ACTION_SENDTO sms:`로 작성 화면만 열어요.
- `open`: 기본 문자 앱을 열어요.

### `android_location`

- `last`: `LocationManager.getLastKnownLocation`으로 GPS·Network·Passive 중 가장 최근 위치를 읽어요.
  `ACCESS_FINE_LOCATION`을 먼저 요청하고, 거부되면 `ACCESS_COARSE_LOCATION`을 요청해요.
  실시간 측위나 FusedLocationProvider는 쓰지 않아요.
- `open_settings`: 위치 설정 화면을 열어요.

마지막 위치가 없으면 가짜 좌표를 만들지 않고, 지도 앱에서 위치를 갱신하라는 오류를 돌려줘요.

### `android_media`

- `images` / `videos` / `audio`: MediaStore 최근 항목의 이름·크기·MIME·추가 시각·content URI.
  `limit` 1~100(기본 30).
- `open_gallery` / `open_camera`: 갤러리·카메라 앱을 열어요.
- `play`: `content://` URI만 `ACTION_VIEW`로 열어요. `file://` 경로는 FileProvider가 없어
  지원하지 않으며 오류로 알려 줘요.

권한은 Android 13(API 33) 이상에서 `READ_MEDIA_IMAGES`·`READ_MEDIA_VIDEO`·`READ_MEDIA_AUDIO`를
유형별로 요청하고, Android 12 이하에서는 `READ_EXTERNAL_STORAGE`를 요청해요.

### `android_notifications`

- `status`: 방해 금지 접근 권한 보유 여부와 현재 필터(모든 알림·우선순위·방해 금지·알람만).
  권한이 없으면 `accessGranted=false`와 설정 안내를 돌려주고, 필터를 추측하지 않아요.
- `open_settings`: `target=app`이면 이 앱의 알림 설정, `target=dnd`이면 방해 금지 접근 설정.

## 런타임 권한과 거부 시 동작

권한 부여는 코어 앱이 선언하고 요청해요. 플러그인 모듈 매니페스트에도 lint 검사를 위해 같은 권한을 적어 두지만, 실제 런타임 요청은 코어의 투명 화면이 맡아요. 플러그인은 도구 실행 시점에
`PluginHost.requestAndroidPermission`으로 요청하고, 이미 허용됐으면 요청하지 않아요.

| 권한 | 쓰는 도구 | 거부 시 동작 |
|---|---|---|
| `READ_CONTACTS` | `android_contacts search` | 연락처 설정 안내를 오류로 반환하고 연락처를 읽지 않아요. |
| `READ_CALENDAR` | `android_calendar list` | 캘린더 권한 설정 안내를 오류로 반환해요. |
| `READ_SMS` | `android_sms list` | 문자 권한 설정 안내와 기본 문자 앱 제한을 함께 알려 줘요. |
| `SEND_SMS` | `android_sms send` | 문자 전송 권한 안내를 오류로 반환하고 전송하지 않아요. |
| `READ_MEDIA_IMAGES` | `android_media images` | 사진 접근 설정 안내를 오류로 반환해요. |
| `READ_MEDIA_VIDEO` | `android_media videos` | 동영상 접근 설정 안내를 오류로 반환해요. |
| `READ_MEDIA_AUDIO` | `android_media audio` | 오디오 접근 설정 안내를 오류로 반환해요. |
| `READ_EXTERNAL_STORAGE` | Android 12 이하 미디어 조회 | 저장소 읽기 설정 안내를 오류로 반환해요. |
| `ACCESS_FINE_LOCATION` | `android_location last` | 대략적 위치 권한을 이어서 요청하고, 둘 다 거부되면 위치 설정 안내를 반환해요. |
| `ACCESS_COARSE_LOCATION` | `android_location last` | 위치 설정 안내를 오류로 반환하고 좌표를 만들지 않아요. |

특수 권한과 일반 권한은 따로 안내해요.

- `WRITE_SETTINGS`: 밝기·회전 변경에 필요해요. `Settings.System.canWrite`가 false면
  `설정 → 앱 → 특수 권한 → 시스템 설정 변경`과 `ACTION_MANAGE_WRITE_SETTINGS`를 안내해요.
- 방해 금지 접근: `NotificationManager.isNotificationPolicyAccessGranted`가 false면 DND 필터를
  읽지 않고 설정 안내만 돌려줘요.
- `SET_WALLPAPER`·`VIBRATE`·`FLASHLIGHT`: 설치 시 부여되는 일반 권한이에요. 기기 정책이 막으면
  `SecurityException`을 해요체 오류로 바꿔요.
- `QUERY_ALL_PACKAGES`: 앱 목록 가시성을 위해 코어가 선언해요. 플러그인은 선언하지 않아요.

권한 요청은 사용자 화면에서 이뤄지며, 플러그인이 권한을 스스로 부여하거나 거부를 우회하지 않아요.

## 지원하지 않는 기능

- 다른 앱의 알림 목록 읽기: NotificationListener를 등록하지 않아요. `android_notifications`는
  방해 금지 상태와 설정 화면만 다뤄요.
- 시스템 전체 화면 캡처·녹화: MediaProjection 동의 흐름과 포그라운드 서비스를 구현하지 않아요.
- 기본 문자 앱 전용 기능: Android와 Google Play는 기본 문자 앱이 아닌 앱의 `READ_SMS`·`SEND_SMS`를
  제한할 수 있어요. 권한이 거부되면 플러그인이 우회하지 않고 기본 문자 앱 제한을 안내해요.
- FusedLocationProvider 기반 실시간 측위·위치 업데이트: 마지막으로 알려진 위치만 읽어요.
- `file://` 미디어 재생: FileProvider가 없으므로 `content://` URI만 허용해요.
- 삼성·Google 전용 기능(예: Samsung 기기 설정, Google 서비스 연동): 다른 플러그인이 맡아요.
- 앱 강제 중지·시스템 설정 값 임의 변경·시스템 앱 제거: 공개 API로 안전하게 할 수 없어요.

## 한도

- 문자 목록 50개, 본문 300자 / 문자 전송 본문 1,000자
- 연락처 50개, 캘린더 일정 100개, 미디어 100개, 앱 200개
- 캘린더 조회·일정 기간 366일
- 클립보드 20,000자
- 진동 1~5,000ms

## 개발

```bash
# JVM 단위 테스트와 Android lint
./scripts/test.sh
```

테스트는 Android 프레임워크를 실행하지 않아요. 권한 분기, 문자 번호·본문 검증, 색상 파싱,
시간 범위, Intent 액션·데이터·extra 구성 같은 순수 함수만 검증해요.

소스는 다음으로 나뉘어 있어요.

- `AndroidPlugin.kt`: 도구 스키마와 `LogentPlugin` 구현
- `AndroidTools.kt`: Context·권한 요청·Intent 실행 공통 처리
- `AndroidDeviceTools.kt`, `AndroidControlTools.kt`, `AndroidAppsTools.kt`,
  `AndroidPersonalTools.kt`, `AndroidMediaTools.kt`: 도구별 Android 호출
- `AndroidConstraints.kt`, `AndroidIntentPlans.kt`: Android 없이 테스트하는 순수 helper

`.lpg` 패키지는 다음 명령으로 만들어요.

```bash
./scripts/build.sh -PlogentPluginApiMavenLocal=true
```

산출물은 `build/dist/logent-android-v<version>.lpg` 하나예요.

## 보안

- 비밀값·권한 토큰·클립보드 내용을 로그에 남기지 않아요.
- 문자 전송은 실제 전송이며 되돌릴 수 없어요. 결과에 전송 완료를 명시해요.
- 연락처·일정 추가, 앱 제거는 편집·확인 화면만 열고 사용자의 최종 확인을 거쳐요.
- 권한이 없거나 기기가 지원하지 않으면 빈 성공·가짜 값을 돌려주지 않고 이유와 설정 경로를 알려 줘요.

## 라이선스

GNU General Public License v3.0이에요. 전문은 `plugin/LICENSE`에 있어요.
출처와 의존성 안내는 `NOTICE`에 있어요.
