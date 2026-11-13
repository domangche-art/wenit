package com.example.shortsdetector.data.rewards

/**
 * 하루 결과·배지 저장소. 구현은 사용 기록과 같은 기기 내 SQLite([com.example.shortsdetector.data.usage.UsageDatabase])이고,
 * 테스트는 [InMemoryRewardStore] 를 쓴다. 동기식이라 메인 스레드에서 부르지 않는다.
 *
 * 삭제: "기록 전체 삭제"([com.example.shortsdetector.data.usage.UsageStore.deleteAll])와 시드 삭제(deleteSeedRecords)가
 * 이 표도 함께 지운다. 보관 기간 정리(deleteBefore)는 지우지 않는다(누적 배지·연속 기록의 근거).
 */
interface RewardStore {
    /** 같은 (날짜, 시드 여부) 행이 있으면 바꾼다. */
    fun upsertDayResult(result: DayResult)

    /** 날짜순. 실제·시드 행 모두. */
    fun dayResults(): List<DayResult>

    /** 같은 (id, 등급, 시드 여부) 가 이미 있으면 넣지 않고 false. 동시에 판정해도 한 번만 들어간다. */
    fun insertBadge(badge: EarnedBadge): Boolean

    /** 얻은 시각 순. 실제·시드 모두. */
    fun badges(): List<EarnedBadge>

    fun markBadgesSeen()
    fun markBadgeCelebrated(id: String, tier: Int)
    fun markBadgeNotified(id: String, tier: Int)
}

/** JVM 테스트용 메모리 저장소. 스레드 안전. */
class InMemoryRewardStore : RewardStore {
    private val days = LinkedHashMap<Pair<java.time.LocalDate, Boolean>, DayResult>()
    private val earned = LinkedHashMap<Triple<String, Int, Boolean>, EarnedBadge>()

    @Synchronized override fun upsertDayResult(result: DayResult) { days[result.date to result.isSeed] = result }

    @Synchronized override fun dayResults() = days.values.sortedBy { it.date }

    @Synchronized override fun insertBadge(badge: EarnedBadge): Boolean {
        val key = Triple(badge.id, badge.tier, badge.isSeed)
        if (key in earned) return false
        earned[key] = badge
        return true
    }

    @Synchronized override fun badges() = earned.values.sortedBy { it.earnedAtMs }

    @Synchronized override fun markBadgesSeen() = update { it.copy(seen = true) }

    @Synchronized override fun markBadgeCelebrated(id: String, tier: Int) =
        update { if (it.id == id && it.tier == tier && !it.isSeed) it.copy(celebrated = true) else it }

    @Synchronized override fun markBadgeNotified(id: String, tier: Int) =
        update { if (it.id == id && it.tier == tier && !it.isSeed) it.copy(notified = true) else it }

    @Synchronized fun deleteSeed() {
        days.keys.removeAll { it.second }
        earned.keys.removeAll { it.third }
    }

    @Synchronized fun deleteAll() {
        days.clear()
        earned.clear()
    }

    private fun update(change: (EarnedBadge) -> EarnedBadge) {
        earned.replaceAll { _, v -> change(v) }
    }
}
