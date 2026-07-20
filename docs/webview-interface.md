# MyRainAssist WebView 인터페이스 규격

`https://www.ribs.kr/rain-assist`가 Android 앱의 WebView 안에서 로드될 때 앱과 맺는 계약을 정의합니다. 이 페이지는 일반 브라우저에서도 단독으로 동작해야 하므로, 아래 내용은 **`?webview=true`로 접속했을 때만** 적용됩니다.

## 1. 웹뷰 모드 감지

앱은 항상 다음 URL로 로드합니다:

```
https://www.ribs.kr/rain-assist?webview=true
```

쿼리스트링은 페이지의 다른 스크립트가 실행되기 전에 이미 존재하므로(반면 JS 인터페이스 호출은 로드 후 비동기로 도착), 초기화 타이밍 경쟁 없이 모드를 감지할 수 있는 가장 이른 시점입니다.

**`webview=true`일 때 웹이 해야 할 일:**

- 자체 레이더 수신·강수 예측 로직을 실행하지 않습니다. 예보 데이터는 전부 `window.RainAssistBridge.applyForecast(...)`로 앱이 주입합니다.
- 강수 관련 안내 문구(예: "20분 뒤 비가 옵니다")를 직접 생성하지 않습니다. `window.RainAssistBridge.showNotification(...)`으로 전달되는 `message`를 그대로 표시합니다. 앱과 웹이 각자 다른 튜닝 로직으로 문구를 따로 만들면 실제 OS 알림에 뜬 문구와 화면 문구가 어긋나는 문제가 있었고, 이를 없애는 것이 이번 변경의 목적입니다.
- `navigator.geolocation`은 평소처럼 호출하면 됩니다 (아래 2번 참고 — 이미 네이티브로 라우팅됩니다). 별도 처리 불필요.

일반 브라우저(쿼리스트링 없음)에서는 기존처럼 자체 로직으로 동작합니다.

## 2. 위치 — `navigator.geolocation` (기존 구현, 변경 없음)

앱은 페이지 로드 시작 시점에 `navigator.geolocation.getCurrentPosition`/`watchPosition`/`clearWatch`를 오버라이드하는 스크립트를 주입합니다. 웹 코드는 **표준 Geolocation API를 그대로 호출**하면 되고, 내부적으로 앱의 위치 정보(FusedLocationProvider, 실패 시 최근 캐시 위치로 폴백)를 반환합니다.

```js
navigator.geolocation.getCurrentPosition(
  (pos) => { pos.coords.latitude, pos.coords.longitude, pos.coords.accuracy /* 항상 50 (고정값) */ },
  (err) => { err.code /* 1 = PERMISSION_DENIED, 2 = POSITION_UNAVAILABLE */, err.message },
);
```

`watchPosition`은 60초 간격으로 재조회합니다. 일반 브라우저의 GPS 워칭과 완전히 동일하게 동작하지 않는다는 점만 유의하세요 (배터리 절약을 위한 폴링 방식).

## 3. 네이티브 → 웹 푸시 인터페이스 (신규)

페이지는 로드 시 아래 전역 객체를 정의해야 합니다. 앱은 각 호출 전에 `typeof` 체크를 하므로, 아직 정의되지 않았거나 일부 메서드가 없어도 앱이 죽거나 에러를 던지지 않습니다 (조용히 무시됨) — 단, 그동안은 화면에 아무 반응도 없습니다.

```js
window.RainAssistBridge = {
  applyForecast(forecast) { /* ... */ },
  showNotification(notification) { /* ... */ },
  refreshPosition() { /* ... */ },
};
```

### 3.1 `applyForecast(forecast)`

강수 예보의 기하/상태 데이터. 백그라운드 폴링 주기(~5.5분)마다, **페이지 로드 완료 직후(이미 계산된 값이 있으면)**, 그리고 **앱이 포그라운드로 돌아올 때 마지막 값을 즉시 재전송**합니다. 텍스트 문구는 포함하지 않습니다 — 지도 위 강수 경로(특히 `blobs`) 렌더링용입니다. 내 위치에 비 예보가 없어도(`state: "NONE"`) `blobs`는 채워질 수 있으니, 주변 강수 셀 경로 표시에는 `forecast.state`가 아니라 `blobs` 존재 여부를 보세요.

> ⚠️ **알려진 레이스**: 페이지 로드 직후 `applyForecast`와 `showNotification`이 같은 tick에 연달아 호출되면(둘 다 hydration이 안 끝난 시점) `showNotification`의 문구가 사라지는 현상이 관찰됐습니다. 페이지가 이미 안정된 상태에서 각각 단독으로 호출하면 재현되지 않습니다 — 초기 렌더링 시점의 상태 업데이트 경쟁(race)으로 보입니다. 네이티브 쪽에서 두 호출 사이에 300ms 지연을 넣어 우회했지만, 근본 원인은 웹 쪽 초기 hydration 로직에 있을 가능성이 높습니다.

> 기존 `requestDrawRainPathVector(json)` 전역 함수 호출을 대체합니다. 페이로드 스키마는 100% 동일하고 `intensityMmh`/`peakMmh` 필드만 추가됐습니다 — `requestDrawRainPathVector`를 이미 구현했다면 함수를 `window.RainAssistBridge.applyForecast`로 옮기기만 하면 됩니다.

```jsonc
{
  "generatedAtEpochMs": 1783497113036,
  "radarFrameEpochMs": 1783496700000, // 이 예보가 근거한 실제 레이더 프레임 관측 시각(신규)
  "userLocation": { "lat": 37.6210271, "lon": 127.1571408 },
  "forecast": {
    "willArrive": true,
    "etaMinutes": 20,          // null이면 다가오는 비 없음
    "state": "INCOMING",       // "NONE" | "INCOMING" | "ACTIVE"
    "intensityMmh": 6.0        // null 가능. state의 강수 강도 추정치(mm/h)
  },
  "blobs": [
    {
      "id": "blob-3",
      "sizeCells": 42,
      "centroid": { "lat": 37.63, "lon": 127.20 },
      "observedCentroid": { "lat": 37.635, "lon": 127.205 }, // 신규, 아래 3.1.1 참고
      "headingDeg": 135.2,      // 0=북, 시계방향
      "speedKmh": 18.4,
      "peakMmh": 15.0,          // 이 강수 셀 내 최대 강도(mm/h)
      "path": [
        { "minutesFromNow": -22, "lat": 37.655, "lon": 127.24 },
        { "minutesFromNow": -10, "lat": 37.64, "lon": 127.22 },
        { "minutesFromNow": 0, "lat": 37.63, "lon": 127.20 },
        { "minutesFromNow": 15, "lat": 37.625, "lon": 127.185 }
      ]
    }
  ]
}
```

#### 3.1.1 `path`의 과거/미래 구분 — 이동선을 하나의 실선/화살표로만 그리면 안 되는 이유

`path`는 이제 `minutesFromNow`가 **음수인 과거 관측 구간**과 **0 이상인 미래 예측 구간**을 함께 담습니다. 이 둘은 성격이 다른 데이터이므로 시각적으로도 구분해서 그려야 합니다 — 그렇지 않으면 사용자가 "이 선이 지나온 길인지 앞으로 갈 길인지" 판단할 근거가 없습니다 (태풍 진로도의 "실선=관측, 점선=예보" 관례와 동일한 이유).

| `minutesFromNow` | 의미 | 권장 렌더링 |
| --- | --- | --- |
| `< 0` | 과거 레이더 프레임들과의 실측 block-matching 결과 — **실제 관측값**, 직선 모델로 추정한 값이 아님 | 실선, 불투명 |
| `0` | lag 보정된 "지금" 위치 (`centroid`와 동일 지점) | 관측/예측의 경계 |
| `> 0` | 등속 직선 모델로 외삽한 **예측값** | 점선 또는 옅은 색, 화살표는 끝점에만 |

- 과거 구간의 포인트 개수와 시간 폭(몇 분 전까지)은 blob마다 다릅니다 — 최근 몇 개의 레이더 프레임과 실제로 매칭됐는지에 따라 달라지며(보통 15~25분, 프레임 누락 시 더 짧을 수 있음), 항상 고정된 개수가 오는 게 아닙니다. 배열이 비어 있을 수도 있습니다(과거 프레임과의 매칭에 전부 실패한 경우 — 이런 blob 자체는 `blobs`에 포함되지 않으므로 실제로는 발생하지 않지만, 방어적으로 처리 권장).
- 미래 구간의 길이(몇 분 뒤까지 그려지는지)도 blob마다 다릅니다. 작은 blob은 실제로 추적된 과거 구간과 대칭적인 길이만큼만(짧게는 15분) 미래를 그리고, 규모가 큰 blob은 최대 30분까지 그립니다 — 작은 비구름은 이동 중 소멸하는 경우가 많아 먼 미래까지 직선으로 그으면 실제로는 신뢰할 수 없는 긴 선이 나오는 문제가 있었습니다(레이더 API의 과거 프레임이 보통 20~25분치뿐이라 90분 예측은 실측 구간보다 몇 배나 길어 보이는 문제도 있었음). `DEVELOPMENT.md` 4.5절 참고.

#### 3.1.2 `centroid` vs `observedCentroid` — 이동선이 레이더 이미지 위에서 구름 중심이 아니라 가장자리에서 시작하는 것처럼 보이는 문제

레이더 원본 프레임(`radarFrameEpochMs` 시점)은 실시간이 아니라 최대 60분까지 뒤처질 수 있습니다(`DEVELOPMENT.md` 4.5절 lag 보정 참고). `centroid`(= `path`에서 `minutesFromNow: 0`인 점)는 이 지연을 보정해 "지금 이 순간"으로 앞당겨 외삽한 위치라서, 실제 레이더 프레임에서 관측된 위치(`observedCentroid`)보다 이동 방향으로 더 나아가 있습니다.

레이더 이미지 자체는 여전히 `radarFrameEpochMs` 시점 기준으로 그려지므로, `centroid`를 기준으로 그리면 화면에 보이는 구름 덩어리보다 진행 방향 쪽으로 앞서 있어 어긋나 보일 수 있습니다. 레이더 이미지와의 정합이 중요한 렌더링(예: 이미지 위에 딱 겹쳐 그리는 마커)에는 `observedCentroid`를 쓰고, 도착 예보(ETA)·사용자 위치와의 비교·`path` 자체는 기존처럼 `centroid` 기준을 그대로 쓰면 됩니다.

### 3.2 `showNotification(notification)`

**OS 알림이 실제로 뜬 시점에만** 호출됩니다 (매 폴링 주기가 아님 — 중복 억제 로직을 통과해 실제로 알림이 발생한 경우만). `message`는 알림 문구를 그대로 담고 있으니 **그대로 표시**하세요. 재가공·재번역·재작성 금지.

```jsonc
{
  "state": "INCOMING",              // "INCOMING" | "ACTIVE" | "STOPPED"
  "message": "20분 뒤 비가 옵니다",
  "etaMinutes": 20,                 // null 가능
  "intensityMmh": 6.0,              // null 가능
  "timestampEpochMs": 1783496406887
}
```

`state` 값의 의미:

| state | 의미 |
| --- | --- |
| `IDLE` | 아직 실제 알림이 한 번도 안 뜬 초기 상태 (아래 3.2.1 참고) |
| `INCOMING` | N분 뒤 비 예보 |
| `ACTIVE` | 지금 비가 오고 있음 |
| `STOPPED` | 실제로 내리던 비가 그침 |

> INCOMING 예보 후 실제로는 비가 오지 않고 지나간 경우("예보 취소")는 사용자에게 알릴 만큼
> 유익하지 않다고 판단해 알림을 띄우지 않는다 — 네이티브가 조용히 `IDLE` 상태로 되돌아가므로
> 이 케이스에서는 `showNotification`이 아예 호출되지 않는다.

#### 3.2.1 페이지 로드 시 기본 문구 (`state: "IDLE"`)

실제 첫 알림은 다음 비가 올 때까지, 심지어 안 올 수도 있어 언제 도착할지 알 수 없습니다. 그래서 **페이지 로드가 끝나는 즉시(`onPageFinished`)** 앱이 `showNotification`을 한 번 호출해서 문구 표시 영역이 흰 화면으로 비어있지 않도록 합니다:

```jsonc
{ "state": "IDLE", "message": "주변 비구름을 감지하고 있어요", "etaMinutes": null, "intensityMmh": null, "timestampEpochMs": 1783497113036 }
```

실제 알림이 뜨면 동일한 `showNotification` 경로로 이 기본 문구를 덮어씁니다. `IDLE`을 별도 상태로 구분해두었으니, 알림 이력에 남기거나 다른 스타일(예: 회색 톤)을 주고 싶다면 그렇게 하셔도 됩니다.

### 3.3 `refreshPosition()`

앱이 백그라운드에서 포그라운드로 돌아올 때 호출됩니다(알림창을 내렸다 올리는 정도의 짧은 pause에는 호출되지 않음). **페이지를 새로고침하지 말고** 다음 두 가지를 함께 처리해야 합니다:

1. `navigator.geolocation.getCurrentPosition`을 다시 호출해 현재 위치 마커/중심을 갱신
2. **웹이 자체적으로 그리고 있는 레이더 이미지/타일 레이어를 강제로 다시 받아와 다시 그리기** (신규 요구사항)

2번이 필요한 이유: 앱이 오래 백그라운드에 있다가 돌아오면 알림 문구(`showNotification`)나 `applyForecast`는 항상 최신값으로 갱신되지만, 화면에 남아있던 레이더 이미지는 웹 페이지의 자체 갱신 주기에 따라 갱신되지 않고 이전(백그라운드 진입 시점) 상태 그대로일 수 있습니다. 그 결과 "비가 온다고 알림/문구는 뜨는데 레이더 이미지상으로는 아직 비구름이 안 보이는" 것처럼 보이는 불일치가 발생합니다. `refreshPosition()`이 호출되는 시점에는 레이더 이미지도 함께 최신 상태로 다시 받아와야 이 불일치가 없어집니다.

직후 `applyForecast`가 마지막 예보값으로 다시 호출되니, 예보 데이터(`blobs`/`forecast`) 자체는 별도로 재요청할 필요 없습니다 — 오직 레이더 배경 이미지 레이어만 별도로 갱신하면 됩니다.

## 4. 호출 시점 요약

| 시점 | 호출 |
| --- | --- |
| 페이지 로드 완료 직후 | `applyForecast`(계산된 값이 있으면) → 300ms 뒤 `showNotification`(캐시된 알림, 없으면 `state: "IDLE"` 기본 문구) |
| ~5.5분마다 (백그라운드 폴링) | `applyForecast` |
| 알림 발생 시 | `applyForecast` → `showNotification` (같은 주기 내 순서 보장) |
| 앱 포그라운드 복귀 | `refreshPosition` (위치 마커 **+ 레이더 이미지 레이어** 갱신) → (구독 재개로) `applyForecast` 마지막 값 재전송 |

## 5. 참고: 서버 사이드 변경 없음

이 문서는 WebView 임베딩 시나리오만 다룹니다. `?webview=true`가 없는 일반 브라우저 접속은 기존 동작(자체 예측, 자체 문구 생성)을 그대로 유지해야 합니다.
