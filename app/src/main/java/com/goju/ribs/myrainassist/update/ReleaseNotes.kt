package com.goju.ribs.myrainassist.update

/**
 * What changed in each handed-out build, shown once after the app updates to it (see
 * [WhatsNewDialog] and MainActivity.checkWhatsNew). Add an entry with every version bump — a build
 * without one simply shows nothing. Write for users, short, in 해요체.
 */
object ReleaseNotes {

    class Entry(val versionCode: Int, val versionName: String, val lines: List<String>)

    val ENTRIES: List<Entry> = listOf(
        Entry(
            versionCode = 11,
            versionName = "0.11.0",
            lines = listOf(
                "영상 3도 이하면 '눈', 4도면 '눈/비'로 알려 드려요. 3~4도에서 눈이 오는데 '비'라고 하던 걸 고쳤어요.",
            ),
        ),
        Entry(
            versionCode = 10,
            versionName = "0.10.0",
            lines = listOf(
                "'기록'에서 최근 30일 동안 내 위치에 비·눈이 온 시간을 볼 수 있어요.",
                "비 소식이 없을 때 알림 문구를 '주변 구름을 감지하고 있어요'로 바꿨어요.",
            ),
        ),
        Entry(
            versionCode = 9,
            versionName = "0.9",
            lines = listOf(
                "알림 창 디자인을 새로 다듬었어요.",
            ),
        ),
        Entry(
            versionCode = 8,
            versionName = "0.8",
            lines = listOf(
                "눈이 올 기온(영상 2도 이하)이면 '눈이 옵니다'처럼 눈으로 알려 드려요.",
                "화면 위 카드에 내 위치 기온과 옷차림 추천이 나와요.",
                "업데이트하면 이렇게 바뀐 점을 한 번 보여 드려요.",
            ),
        ),
    )

    /** Entries newer than [lastSeenCode] up to [currentCode], newest first. */
    fun since(lastSeenCode: Long, currentCode: Long): List<Entry> =
        ENTRIES.filter { it.versionCode > lastSeenCode && it.versionCode <= currentCode }
            .sortedByDescending { it.versionCode }
}
