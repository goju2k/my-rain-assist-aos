# 개발 문서

`README.md`가 다루지 않는 아키텍처, 알고리즘, 웹 연동 규격, 개발 과정에서의 의사결정/이슈를
정리한 문서. 코드를 직접 수정하거나 웹(`ribs.kr/rain-assist`) 쪽 연동을 구현할 때 참고한다.

---

## 1. 전체 구조

```
app/src/main/java/com/goju/ribs/myrainassist/
  RainAssistApp.kt                 Application: 알림 채널 생성
  MainActivity.kt                  온보딩 → WebView 전환, StateFlow 수집 → JS 브릿지 호출

  ui/
    PermissionOnboardingScreen.kt  최초 1회 순차 권한 요청 화면
    RainWebViewScreen.kt           WebView Compose 래퍼

  webview/
    RainWebViewClient.kt           ribs.kr 도메인만 인앱 로드, 외부는 브라우저로
    RainWebChromeClient.kt         WebView 지오로케이션 프롬프트 처리
    WebBridge.kt                   requestDrawRainPathVector 호출 (★ 인터페이스 규격은 6장 참고)

  data/
    LatLon.kt                      위경도 + haversine/bearing 계산
    RadarModels.kt                 RadarFrame / RadarResponse 모델
    RadarApi.kt                    레이더 API 호출 + JSON 파싱
    RadarPngDecoder.kt             프레임 PNG → 강수 유무 바이트 그리드 디코딩

  geo/
    QuadMapper.kt                  위경도 ↔ 격자 좌표 변환 (bilinear + Newton's method 역변환)

  analysis/
    PresenceGrid.kt                바이트 배열 → 강수 유무 판정
    ConnectedComponents.kt         강수 blob(구름 덩어리) 탐지
    BlockMatcher.kt / MotionEstimator.kt   blob 이동 속도 추정
    ForecastEngine.kt              도달 예측 + 결과 조립
    RainForecastResult.kt          결과 모델 + JSON 직렬화
    NotificationDedup.kt           알림 상태머신 (스팸 방지 + 그침 감지)

  service/
    RainMonitorService.kt          포그라운드 서비스, 폴링 루프
    RainForecastBus.kt             서비스 → UI 결과 전달 (StateFlow)
    BootReceiver.kt                재부팅 후 서비스 자동 재시작

  notification/
    NotificationHelper.kt          알림 채널/문구 관리
```

---

## 2. 권한 및 백그라운드 상시 실행

`AndroidManifest.xml`에 선언된 권한:

| 권한 | 용도 |
|---|---|
| `ACCESS_COARSE_LOCATION` | 사용자 위치 확인 (도시 블록 단위 정확도로 충분) |
| `ACCESS_BACKGROUND_LOCATION` | 재부팅 후 등 앱이 백그라운드 상태에서 포그라운드 서비스를 새로 시작할 때 필요 |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | 위치 기반 상시 포그라운드 서비스 선언 (targetSdk 36 요구사항) |
| `POST_NOTIFICATIONS` | 알림 표시 (API 33+) |
| `RECEIVE_BOOT_COMPLETED` | 재부팅 후 서비스 자동 복구 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 배터리 최적화 예외 요청 |

`RainMonitorService`는 `foregroundServiceType="location"`으로만 선언되어 있다 (`dataSync`는
Android 15+에서 24시간당 6시간 누적 실행 제한이 있어 "상시 실행" 요구사항과 충돌하므로 사용하지
않음). `ACCESS_FINE_LOCATION`은 요청하지 않는다 — 격자 셀이 수 km 단위라 정밀 GPS 이득이 없고,
`PRIORITY_BALANCED_POWER_ACCURACY` + coarse 권한으로 충분하다.

**온보딩 순서** (`MainActivity`, 최초 1회): 알림 → 위치 → 백그라운드 위치 → 배터리 최적화 제외 →
서비스 시작 → WebView 표시. 각 단계는 건너뛰기 가능하며, 이미 충족된 단계는 자동으로 건너뛴다.

---

## 3. 포그라운드 서비스 동작

`RainMonitorService`는 앱이 꺼져 있어도 계속 실행되며 약 **5.5분 간격**으로 다음을 반복한다:

1. `FusedLocationProviderClient.getCurrentLocation()`으로 현재 위치 조회
2. `RadarApi.fetchFrames()`로 최근 레이더 프레임 조회 (CloudFront의 `current.json` 최신 스냅샷)
3. `ForecastEngine.computeForecast()`로 강수 도달 예측 계산
4. 결과를 `RainForecastBus`(StateFlow)에 게시 → `MainActivity`가 살아있으면 WebView에 전달
5. `NotificationDedup` 상태머신으로 알림 필요 여부 판단 후 알림 표시

위치 조회 실패, API 실패, 격자 매핑 실패(사용자가 레이더 범위 밖) 시 해당 주기는 조용히
건너뛰고 다음 주기에 재시도한다.

`MainActivity`가 백그라운드에서 포그라운드로 돌아올 때(콜드 스타트 포함)는 이 5.5분 주기를
기다리지 않고 `RainMonitorService.requestImmediateCheck()`로 즉시 한 번 더 체크한다 —
그렇지 않으면 사용자가 앱을 켠 시점과 마지막 주기 사이 최대 5.5분의 시차 때문에 "레이더엔 이미
비가 오는데 알림/문구는 아직 예보 전"처럼 화면과 실제 상태가 어긋나 보일 수 있다. 서비스는
`Channel<Unit>(CONFLATED)`으로 이 요청을 받아 현재 대기 중인 `delay`를 즉시 끊고 다음 주기를
바로 실행하며, 이후 주기는 그 시점부터 다시 5.5분 간격으로 이어진다(스케줄이 당겨질 뿐 누적되지
않음).

### 알림 채널

| 채널 | 중요도 | 용도 |
|---|---|---|
| `rain_monitor_ongoing_v3` | DEFAULT (소리/진동 명시적으로 끔) | "비올까? / 주변 비구름을 감지하고 있어요" 상시 알림 |
| `rain_monitor_alert` | HIGH | 강수 도달/그침 알림 |

채널 중요도는 한번 생성되면 코드로 변경되지 않는 Android 특성이 있어, 중요도를 바꿀 때마다
채널 ID를 새로 발급했다 (`rain_monitor_ongoing` → `_v2` → `_v3`).

`rain_monitor_ongoing_v3`는 포그라운드 서비스가 살아있는 한 Android가 강제하는 필수 알림이라
완전히 없앨 수는 없다(위에서 `IMPORTANCE_MIN`으로 아이콘 자체를 숨겨봤다가 서비스가 죽은 것처럼
보여 폐기한 이력 참고). 대신 문구가 `rain_monitor_alert`와 따로 놀지 않도록, INCOMING 상태의
상시 알림 문구(`ongoingTextFor`)도 알림과 동일한 시간 문구 생성 함수(`incomingRainText`)를
공유해 "N분 뒤 비가 옵니다"처럼 똑같이 구체적으로 뜨게 했다 (이전에는 상시 알림만 "비가 곧 올 것
같아요"로 뭉뚱그려져 있어 알림과 문구가 어긋나 보였다).

- `IMPORTANCE_MIN`: 상태바 아이콘이 아예 안 뜨고 서비스가 죽은 것처럼 보임 → 폐기
- `IMPORTANCE_LOW`: "무음(Silent)" 그룹에 묶여 알림창 하단에 깔림 → 폐기
- `IMPORTANCE_DEFAULT` + `setSound(null,null)` + `enableVibration(false)`: 소리 없이 알림창
  최상단에 즉시 노출 → 최종 채택. 다만 상태바 최상단 "작은 아이콘" 표시 자체는 제조사/OS 정책에
  따라 항상 보장되지는 않음 (실측: Samsung One UI, AOSP 에뮬레이터 둘 다 상시 표시는 아니었음).
  알림창을 내리면 항상 정확하게 표시되는 것은 확인됨.

---

## 4. 레이더 데이터 분석 파이프라인

### 4.1 API 응답 구조 (실측 확인됨)

더 이상 `ribs.kr` 백엔드를 호출하지 않고, CloudFront(S3 오리진)에 정적으로 올라오는 최신 스냅샷
JSON을 파라미터 없이 직접 읽어온다. 응답은 gzip 압축을 지원하므로 `Accept-Encoding: gzip`을
보내고 `Content-Encoding` 응답 헤더가 `gzip`이면 `GZIPInputStream`으로 해제한다 (해상도가
올라가며 응답 크기가 커졌기 때문에 중요).

```
GET https://d8dfs01bak16j.cloudfront.net/rain-assist/current.json

{
  "corners": [[lat,lon], [lat,lon], [lat,lon], [lat,lon]],
  "legend": [...],          // 안드로이드 앱에서는 미사용
  "noDataIndex": 255,        // 안드로이드 앱에서는 미사용
  "rainThresholdIndex": 22,  // 안드로이드 앱에서는 미사용
  "frames": [
    { "tm": "202607041000", "pngBase64": "..." },
    ...  // tm 오름차순(오래된 것 → 최신), tm은 KST(한국 표준시) — "UTC"로 잘못 문서화돼 있었고
         // 실제로 `RadarFrame.epochMinute`도 UTC로 파싱해 lagMinutes가 항상 음수→0으로 clamp되던
         // 버그가 있었다(2026-07-22 발견/수정). 실기기에서 받은 프레임의 tm을 실제 시각과 대조해
         // 검증함: UTC로 해석하면 "최신" 프레임이 미래 시각이 되어버려 모순이었다.
  ]
}
```

- 최상위 응답에 `data` 래퍼가 없다. 처음 구현 시 `{"data": {...}}` 형태로 잘못 가정해서 매 poll
  주기가 조용히 실패하던 실제 버그가 있었음 (수정 완료)
- `pngBase64`는 팔레트(색인) PNG 이미지를 Base64 인코딩한 것. 격자 해상도는 PNG의 실제
  가로/세로 픽셀 수를 그대로 사용한다 (`gridWidth`/`gridHeight` 필드는 더 이상 오지 않음).
- `legend`/`noDataIndex`/`rainThresholdIndex`는 PNG를 렌더링할 때 쓰인 색상표 메타데이터일 뿐,
  강수 유무 판정에는 쓰지 않는다 — **픽셀의 알파 채널**만으로 판정한다: 알파가 0(완전 투명)이면
  강수 없음, 0이 아니면(불투명) 강수 있음. `RadarPngDecoder.kt`가 `BitmapFactory`로 PNG를 디코드한
  뒤 픽셀별 알파를 검사해 `PresenceGrid`용 바이트 그리드(강수 있음=0, 없음=255)로 변환한다.

**이전 포맷과의 차이**: 예전에는 `gridDataBase64`에 `gridWidth*gridHeight` 크기의 원본 바이트
그리드(값 2~250=강수 echo 세기, 255=강수 없음)를 raw 또는 RLE로 압축해 실어 보냈다. 서버가 이를
완전히 원본 PNG 스냅샷으로 교체하면서 세기(intensity) 정보는 더 이상 신뢰할 수 있는 형태로
오지 않고(팔레트 색상은 렌더링용일 뿐 데이터 등급이 아님), 강수 유무만 알파로 판정하도록
단순화했다. 이 때문에 `ConnectedComponents`의 centroid 가중치(`255 - byte`)는 이제 모든 강수
셀에서 값이 같아(항상 255) 사실상 단순 평균과 동일하게 동작한다.

### 4.2 위경도 ↔ 격자 좌표 변환 (`QuadMapper.kt`)

`corners`는 축 정렬 사각형이 아니라 기울어진 사각형(quad)이다. 관측된 좌표 순서로 보아
`corners[0]=남서, [1]=북서, [2]=북동, [3]=남동` (0→1→2→3 반시계)으로 가정하고 bilinear
매핑을 사용한다.

- **정방향** (격자 u,v → 위경도): 표준 bilinear interpolation
- **역방향** (위경도 → 격자 u,v): Newton's method, 8회 반복. 수렴 실패 시 `null` 반환 (해당
  주기 예측 스킵)

> ⚠️ **corner 순서 가정은 검증이 필요하다.** 좌표값 추론일 뿐, 실제 웹 지도 렌더링과 대조
> 확인된 적은 없다. 알려진 도시 좌표를 `inverseMap`에 넣어 나온 위치가 실제 웹 지도상 위치와
> 방향이 맞는지 1회 수동 검증을 권장한다.

### 4.3 강수 blob 탐지 (`ConnectedComponents.kt`)

8-connectivity BFS로 강수 echo 셀들을 묶어 blob(구름 덩어리)으로 추출. 9셀 미만은 노이즈로
버림. Centroid는 `(255 - byte)` 가중 평균.

- **극단 강도(≥70mm/h) 보정**: PNG 안티에일리어싱/알파 블렌딩으로 생긴 애매한 픽셀이
  `RadarLegend.nearestIndex`에서 가장 어두운 팔레트색들(index 0=110mm/h 회색, index 1=90mm/h
  남색, index 2=80mm/h, index 3=70mm/h)에 잘못 매칭되는 사례가 실기기 로그에서 반복 확인됨
  (예: 2026-07-20 05:49 "90mm/h" → 6분 뒤 "0.1mm/h"로 복귀, 2026-07-20 22:26 "80mm/h" 단발성
  ACTIVE 알림). 한국은 태풍이 와도 시간당 70mm를 넘기는 경우가 드물어 `EXTREME_INDEX_THRESHOLD`를
  index 3(70mm/h)까지 낮춰 잡았다. `PresenceGrid.corroboratedValueAt`가 이 등급 이상의 읽기는
  인접 8셀 중 최소 3개가 같은 등급(index<=3)이어야 인정하고, 아니면 주변에서 그다음으로 강한
  등급으로 낮춰 반환한다. `ConnectedComponents`의 `peakMmh`와 `ForecastEngine`의 `isRainingNow`
  직접 픽셀 읽기(`mmhAt(userRow, userCol)`) 둘 다 이 보정된 값을 쓴다. 강수 유무(blob 소속)
  판정 자체는 기존 `isPresent`(알파 기반) 그대로라 영향 없음 — 강도 표시 문구만 보정된다.
- **모션 매칭 실패 시 정지 가정 폴백** (`MotionEstimator.estimate`): 이전 프레임과 겹치는
  셀이 `BlockMatcher.MIN_CONFIDENCE`(30%) 미만이면 매칭 실패로 blob을 통째로 버리던 것을,
  속도=0·신뢰도=0인 `BlobMotion`으로 대체했다. 약한 비는 프레임마다 모양이 흔들려 매칭이
  자주 실패하는데, 예전엔 그 blob이 `blobForecasts`에서 완전히 빠져 도착 예보/알림 계산에서
  누락됐다(예: 2026-07-20 21:52~22:10, 3.9km 근처 비가 최소 10분간 ETA 없이 무시됨). 이제는
  매칭에 실패해도 blob이 이미 도착 임계거리(3km) 안이면 `arrivalMinutes=0`으로 즉시 잡히고,
  멀면 이동한다고 가정하지 않고 제자리 기준으로만 짧게(15분) 예보한다 — 근거 없는 이동
  예측으로 틀린 ETA를 만들지 않으면서도 blob을 완전히 놓치지는 않는다.

### 4.4 이동 속도 추정 (`BlockMatcher.kt`, `MotionEstimator.kt`)

TREC-lite 방식 block matching: 최신 프레임의 각 blob에 대해, 사용 가능한 과거 프레임들과
±(4×경과분/5) 셀 범위에서 최고 overlap을 보이는 이동량(dx, dy)을 탐색. 여러 프레임 쌍의 추정치
중 median을 사용해 노이즈에 강건하게 만든다(속도/방향/ETA 계산에 사용). 프레임 간 실제 `tm`
차이를 사용하므로 프레임 누락에도 안전하다.

median으로 뭉개기 전의 프레임별 개별 매칭 결과(`BlobMotion.matches`)도 함께 보관한다 — 이건
"직선 모델로 추정한 과거"가 아니라 각 과거 프레임과 실제로 매칭된 값 그대로라서,
`ForecastEngine`이 이걸 `path`의 음수 `minutesFromNow` 구간(실측 과거 궤적)으로 그대로 노출한다.
`trackedSpanMinutes`(가장 오래 매칭에 성공한 과거 프레임까지의 시간)는 작은 blob의 미래 예측
지평선을 제한하는 데도 쓰인다 (4.5절 참고).

### 4.5 도달 예측 (`ForecastEngine.kt`)

- blob 위치를 셀당 실제 km(로컬 투영 왜곡 보정, haversine 기반)로 변환해 속도(km/h)·방향(도) 계산
- **관측 지연(lag) 보정**: 마지막 프레임의 `tm`은 실제 현재 시각보다 뒤처져 있을 수 있다(KMA
  원본 갱신 지연 등). `lagMinutes = (현재시각 - tm)`을 0~`MAX_LAG_MINUTES`(90분) 사이로 clamp해
  구하고, blob 위치를 `tm` 기준이 아니라 `tm + lagMinutes`(= 지금) 기준으로 먼저 한 번 외삽한 뒤
  그 지점을 "0분 후(지금)"로 삼아 도달 예측을 계산한다. 이 보정이 없으면 지연된 만큼 ETA가 항상
  과대평가된다. `MAX_LAG_MINUTES`는 프레임 지연 보정용이라 아래 미래 예측 지평선(`MAX_FORECAST_MINUTES`)과
  별개의 상수다 — 둘을 같은 값으로 묶어 쓰다가 예측 지평선을 줄이면서(2026-07-20) 분리했다.
- 5분 단위로(지금 기준) 최대 `MAX_FORECAST_MINUTES`(30분)까지 직선 외삽, 사용자와의 거리가
  `max(3km, 1.5×셀 대각선km)` 이내가 되는 최초 시점을 도달 시간으로 기록 (지금 이미 그 거리
  이내면 0분). 원래 90분이었는데, 레이더 API가 보통 4프레임(~20~25분치) 과거 데이터만 주기 때문에
  실측으로 뒷받침되는 구간(20~25분)보다 미래 예측 구간(90분)이 3~6배 더 길게 그려지는 문제가
  실기기에서 확인되어(웹뷰에 그려진 경로가 과거 구간보다 훨씬 길어 보임) 통상 측정 가능한 범위인
  30분으로 낮췄다.
- **약한+먼 blob 예보 억제**: peakMmh가 "약한 비" 등급(3.0mm/h 미만)이면서 현재("지금") 위치가
  사용자로부터 10km보다 먼 blob은 도달 시간(`arrivalMinutes`) 계산 자체를 건너뛴다(`null` 고정).
  약한 비구름은 이동 중 소멸할 가능성이 높아 장거리 ETA 예보/알림을 낼 근거가 부족하기 때문.
  `path`(지도 표시용)에는 여전히 포함되므로 화면에서는 보임 — 단지 알림/ETA 계산에 안 쓰일 뿐.
  매 주기 그 blob의 "지금" 위치로 다시 판정하므로, 실제로 10km 이내로 들어오면 다음 주기부터
  자동으로 다시 예보 대상에 포함된다.
- **작은 blob의 예측 지평선(horizon) 제한**: 작은 구름(sizeCells < 50)은 대개 수명이 짧아 소멸·분산
  되기 쉬운데, 속도가 빠르면 `MAX_FORECAST_MINUTES`(30분) 직선 외삽 시 실제로는 신뢰할 수 없는
  긴 이동선이 그려지는 문제가 있었다. `MotionEstimator.BlobMotion.trackedSpanMinutes`(이 blob의
  이동을 실제로 확인한 가장 오래된 과거 프레임까지의 시간 — 보통 4프레임/20~25분 안팎)를 그대로
  미래 예측 지평선으로 사용해 "실측으로 뒷받침되는 만큼만" 앞으로 그리도록 대칭적으로 제한한다
  (최소 `PATH_SAMPLE_INTERVAL_MINUTES`=15분은 보장). 큰 blob(sizeCells >= 50)은 기존처럼
  `MAX_FORECAST_MINUTES`(30분)까지 그대로 예측한다 — 규모가 큰 강수 시스템은 상대적으로 지속성이
  있다고 보기 때문. `LARGE_BLOB_MIN_CELLS`=50은 임의로 정한 값이라 실측 후 조정 가능.
- 여러 blob 중 최소 도달 시간을 10분 단위로 반올림해 `etaMinutes`로 사용
- 사용자가 있는 정확한 격자 셀이 현재 강수 상태(`isRainingNow`, 이 값은 lag 보정이 안 된 `tm`
  시점 그리드 기준)이거나, lag 보정된 blob 도달 시간이 0분이면(`activeNow`) `etaMinutes=0`
  (ACTIVE). 아니면 도달 예측값(INCOMING) 또는 `null`(NONE)
- `nearestRainDistanceKm`(그침/지나감 판정에 쓰이는 "근처에 비구름이 있는가" 신호)은 blob의
  **중심점이 아니라 가장 가까운 셀** 기준 거리다. 크고 길쭉한 blob은 중심점이 5km 밖이어도
  가장자리가 사용자 위 또는 바로 근처에 있을 수 있어, 중심점 거리만 쓰면 실제로는 강수 픽셀이
  있는데도 "비가 오지 않고 지나갔어요"가 뜨는 오탐이 있었다 (버그 수정 완료, 아래 8절 참고).

### 4.6 알림 상태머신 (`NotificationDedup.kt`)

```
IDLE ──(도달 예측 발생)──▶ WATCHING ──(비구름이 5km 밖으로 2주기 연속 확인)──┬─ 실제로 비가 왔었다면: "비가 그쳤어요" 알림 ──▶ IDLE
         │                    │                                              └─ INCOMING만 있었고 실제로 안 왔다면: 알림 없이 조용히 IDLE로 리셋
         │                    ├─ 이미 오는 중이면 추가 알림 없음 (스팸 방지)
         │                    ├─ ETA가 20분 이상 바뀌면 갱신 알림
         │                    └─ 처음 실제로 비가 오기 시작하면 "지금 비가 오고 있어요" 1회 알림
         └─ etaMinutes=null이면 아무 것도 안 함
```

- `WATCHING` 상태에서는 계속 비가 오거나 근처(5km 이내)에 있는 한 추가 알림 없음 — 단, ACTIVE
  상태에서 강수 강도가 약한/보통/강한/매우 강한 구간 경계를 넘나들면(`RadarLegend.intensityTier`)
  그때마다 새로 알림을 띄운다. 아니면 소나기 시작 시점 문구("지금 비가 오고 있어요")가 실제로는
  더 강해지거나 약해져도 그대로 굳어 있는 문제가 있었다.
- "그침/지나감" 판정은 2주기(~11분) 연속 확인해야 확정 → blob 경계 노이즈로 인한 오탐 방지
- 두 판정 모두 SharedPreferences를 완전히 초기화해 다음 접근 강수를 처음부터 새로 감지
- "비가 오지 않고 지나갔어요"는 실제 알림(OS 알림/상시 알림 문구/WebView `showNotification`)으로
  내보내지 않는다 — INCOMING 예보가 취소됐다는 정보는 사용자에게 실익이 없고, 도착 직전 blob이
  잠깐 경계를 벗어났다가 곧 다시 들어오는 경우 "지나갔어요" 직후 "비가 오고 있어요"가 번갈아
  뜨는 번복처럼 보이는 문제가 있었다. `RainEventLog`에는 `MISSED`로 계속 기록되어 디버그 화면
  (`RainEventLogScreen`)에서는 확인 가능하다.

---

## 5. 서비스 ↔ UI 통신

`RainForecastBus`(같은 프로세스 내 `object`, `MutableStateFlow<RainForecastResult?>`)를 통해
서비스가 매 주기 결과를 발행하고, `MainActivity`는 `lifecycleScope + repeatOnLifecycle(STARTED)`로
수집해 WebView가 살아있으면 `WebBridge.pushForecastToWebView()`를 호출한다.

---

## 6. 웹 인터페이스 규격 (app → web)

> ⚠️ **이 절은 옛 규격(`requestDrawRainPathVector`)을 설명한다 — 현재는
> `docs/webview-interface.md`의 `window.RainAssistBridge` 규격으로 대체되었다.** 최신 페이로드
> 스키마(`observedCentroid`, `radarFrameEpochMs` 포함)와 호출 시점 계약은 반드시
> `docs/webview-interface.md`를 참고할 것. 이 절은 초기 설계 배경 기록용으로만 남겨둔다.

안드로이드 앱이 계산한 비구름 이동 경로를 웹뷰(`https://www.ribs.kr/rain-assist`)에 그리게 하기 위해,
네이티브에서 웹 페이지의 JS 함수를 직접 호출한다 (`WebView.evaluateJavascript`, 단방향
app→web, `@JavascriptInterface` 아님).

```kotlin
webView.evaluateJavascript("requestDrawRainPathVector(${json})", null)
```

**호출 주기**: 서비스 poll 주기(~5.5분)마다, WebView가 화면에 떠 있을 때만 호출된다.

> ⚠️ **웹 페이지 쪽에 `requestDrawRainPathVector` 함수는 아직 구현되어 있지 않다** (배포된 JS
> 번들 전체를 검색해서 확인함). 아래는 안드로이드가 호출하는 데이터 계약이며, 웹 쪽에서 이
> 함수를 정의해 지도 위에 렌더링해야 한다. 함수가 없어도 안드로이드 쪽 호출 자체는 에러 없이
> 무시된다 (JS 콘솔에러만 발생, 앱에는 영향 없음).

### 6.1 함수 시그니처

```javascript
function requestDrawRainPathVector(payload) {
  // payload: 아래 JSON 스키마 객체 (JS 객체 리터럴로 직접 전달됨, JSON.parse 불필요)
}
```

### 6.2 페이로드 스키마

```json
{
  "generatedAtEpochMs": 1751600000000,
  "userLocation": { "lat": 37.5665, "lon": 126.9780 },
  "forecast": {
    "willArrive": true,
    "etaMinutes": 30,
    "state": "INCOMING"
  },
  "blobs": [
    {
      "id": "blob-3",
      "sizeCells": 142,
      "centroid": { "lat": 37.90, "lon": 126.50 },
      "headingDeg": 132.4,
      "speedKmh": 28.5,
      "path": [
        { "minutesFromNow": 0,  "lat": 37.90, "lon": 126.50 },
        { "minutesFromNow": 15, "lat": 37.88, "lon": 126.60 },
        { "minutesFromNow": 30, "lat": 37.85, "lon": 126.70 },
        { "minutesFromNow": 45, "lat": 37.82, "lon": 126.80 },
        { "minutesFromNow": 60, "lat": 37.80, "lon": 126.90 }
      ]
    }
  ]
}
```

### 6.3 필드 설명

| 필드 | 타입 | 설명 |
|---|---|---|
| `generatedAtEpochMs` | number | 이 예측이 계산된 시각 (epoch ms) |
| `userLocation.lat/lon` | number | 계산에 사용된 사용자 위치 |
| `forecast.willArrive` | boolean | 60분 내 도달 예측 존재 여부 (`etaMinutes != null`) |
| `forecast.etaMinutes` | number \| null | 도달까지 남은 분 (10분 단위 반올림), 이미 오는 중이면 `0`, 예측 없으면 `null` |
| `forecast.state` | `"NONE"` \| `"INCOMING"` \| `"ACTIVE"` | `NONE`=예측 없음, `INCOMING`=다가오는 중, `ACTIVE`=사용자 위치에 현재 강수 |
| `blobs[]` | array | 현재 탐지된 강수 blob 목록 (이동 속도를 추정할 수 있었던 blob만 포함) |
| `blobs[].id` | string | blob 식별자 (poll 주기마다 재계산되므로 프레임 간 동일 blob 추적 불가 — 매 주기 새로 그리는 용도) |
| `blobs[].sizeCells` | number | blob 크기(격자 셀 수), 시각적 굵기/크기 힌트로 활용 가능 |
| `blobs[].centroid.lat/lon` | number | blob 현재 중심 위경도 |
| `blobs[].headingDeg` | number | 이동 방향 (0=북, 90=동, compass bearing) |
| `blobs[].speedKmh` | number | 이동 속도 (km/h) |
| `blobs[].path[]` | array | 0, 15, 30, 45, 60분 후 예상 위치 (직선 외삽). 화살표/폴리라인 렌더링에 사용 |
| `blobs[].path[].minutesFromNow` | number | 몇 분 후 위치인지 |

### 6.4 유효성 관련 참고사항

- `NaN`/`Infinity`가 될 수 있는 blob은 네이티브에서 이미 필터링되어 전달되지 않는다
  (`RainForecastResult.toJson()`의 `isFinite()` 체크)
- 숫자는 항상 `.`을 소수점으로 사용 (로케일 무관, `Double.toString()` 기반)
- `blobs`가 빈 배열일 수 있음 (탐지된 강수 blob이 없거나, 있어도 이동 속도를 추정하지 못한 경우)

---

## 7. 앱 아이콘

`ic_launcher_background.xml`(단색 파란 배경, `#1976D2`) + `ic_launcher_foreground.xml`(흰색
구름 + 빗방울 3개, Material 아이콘 path 재사용)로 구성된 어댑티브 아이콘. 상태바/알림 아이콘도
같은 foreground를 재사용하며, 순수 흰색 실루엣이라 알림 아이콘으로도 깨끗하게 렌더링된다.
`monochrome` 레이어도 동일 파일 사용 (Android 13+ 테마 아이콘 지원).

minSdk 31이라 레거시 래스터 아이콘(mipmap의 `.webp`)은 실제로는 전혀 사용되지 않는다 (API 26+는
항상 벡터 어댑티브 아이콘 사용).

---

## 8. 실기기/에뮬레이터 테스트로 확인한 내용

- 실기기(Samsung SM-S947N, SM-S948N) + 에뮬레이터(`android-34;google_apis;x86_64`)에서 권한
  온보딩, 포그라운드 서비스 상시 실행, 배터리 최적화 예외 정상 동작 확인
- 실시간 레이더 API에서 실제 강수 위치를 계산해 모의 GPS 위치로 주입 → "지금 비가 오고
  있어요" 알림 발생 확인
- 모의 위치를 강수 지역에서 200km 이상 떨어진 곳으로 이동 → 2주기 확인 후 "비가 그쳤어요"
  알림 + 상태 초기화 확인
- (버그 수정) `RadarApi`가 응답을 `{"data": {...}}`로 잘못 감싸 파싱하려 해서 모든 poll 주기가
  조용히 실패하던 문제 발견 및 수정
- (버그 수정) API가 `gridDataBase64`에 RLE 압축을 도입한 뒤에도 정상 동작하도록
  `GridRleDecoder` 추가, 실측 데이터로 포맷 검증
- (개선) 알림 채널 중요도를 `MIN` → `DEFAULT`로 올려 알림이 "무음" 그룹에 묻히지 않고 즉시
  상단에 노출되도록 개선
- (버그 수정, 코드 리뷰로 발견 — 실기기 재검증 필요) `nearestRainDistanceKm`이 blob 중심점
  기준이라 크고 길쭉한 blob은 가장자리가 사용자 위에 있어도 "5km 밖"으로 판정돼 "비가 오지 않고
  지나갔어요"가 오탐되던 문제. 가장 가까운 셀 기준으로 변경 (4.5절 참고)
- (개선) 약한(peakMmh < 3.0mm/h) + 10km 밖 blob은 도달 예보(ETA)/알림 대상에서 제외 (4.5절 참고)
- (개선) 상시 알림의 INCOMING 문구를 알림과 동일한 시간 문구로 통일 (3절 참고)
- (웹 연동 규격 추가) `applyForecast`에 `radarFrameEpochMs`/`blobs[].observedCentroid` 필드 추가,
  `refreshPosition()`에 레이더 이미지 레이어 갱신 요구사항 명시 — 이동선이 레이더 이미지 위에서
  구름 중앙이 아닌 가장자리에서 시작하는 것처럼 보이는 문제 및 백그라운드 복귀 시 레이더 이미지가
  갱신되지 않아 알림 문구와 어긋나는 문제 대응 (`docs/webview-interface.md` 참고, 웹 쪽 반영 필요)
- (웹 연동 규격 추가) `blobs[].path`에 음수 `minutesFromNow`(실측 과거 궤적) 포인트 추가 — 이동선을
  하나의 실선으로만 그리면 과거/미래를 구분할 수 없는 문제 대응. 웹은 음수 구간을 실선(관측),
  0 이상을 점선(예측)으로 구분해 그려야 함 (`docs/webview-interface.md` 3.1.1 참고, 웹 쪽 반영 필요)
- (개선) 작은 blob(`sizeCells < 50`)의 미래 예측 지평선을 실제로 추적된 과거 구간 길이로 제한 —
  빠르게 이동하는 작은(대개 단명하는) 구름이 90분짜리 매우 긴 직선으로 과신되어 그려지던 문제 대응
  (4.5절 참고)
- (버그 수정, 실기기 로그로 확인) `isRainingNow`가 사용자 셀 1개만 `isPresent()`로 검사해,
  9칸 미만 고립 픽셀(압축 노이즈/클러터로 추정)만으로도 "지금 비가 오고 있어요"가 오탐되던 문제.
  다른 모든 강수 판정 경로처럼 `MIN_BLOB_SIZE_CELLS` 이상 뭉친 blob에 속한 셀인지로 변경
  (4.5절 참고)

## 9. 알려진 한계 / 후속 과제

- corner 순서 가정(4.2절) 실측 검증 필요 — 웹 쪽 `requestDrawRainPathVector` 구현 후 실제
  렌더링과 대조 권장
- 웹 페이지(`ribs.kr/rain-assist`)에 `requestDrawRainPathVector` 함수 구현 필요 (6장 스키마 참고)
- 알림 상태바 아이콘이 OS/제조사 정책에 따라 항상 보장되지는 않음 (알림창 내림은 항상 정확)
- OEM(Xiaomi/Huawei/Samsung 등)의 자체 백그라운드 킬 정책은 AOSP API로 해결 불가 — 필요 시
  제조사별 "자동 실행 허용" 설정 화면으로 안내하는 기능 추가 검토 가능
