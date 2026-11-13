package com.example.shortsdetector.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.shortsdetector.R
import com.example.shortsdetector.data.rewards.BadgeArtResolver
import com.example.shortsdetector.data.rewards.BadgeArtSpec
import com.example.shortsdetector.data.rewards.BadgeCatalog
import com.example.shortsdetector.data.rewards.BadgeDef
import com.example.shortsdetector.data.rewards.BadgeProgress
import com.example.shortsdetector.data.rewards.BadgeTier
import com.example.shortsdetector.data.rewards.EarnedBadge
import com.example.shortsdetector.ui.theme.WenitTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 배지 그림 연결. 고르는 규칙은 [BadgeArtResolver](배지·등급 전용 → 배지 전용 → 틀+기호 합성 → 공통).
 * 파일만 넣으면 바뀌게 하려고 리소스를 이름으로 찾는다. 리소스 축소 시 res/raw/keep_badges.xml 로 남긴다.
 */
object BadgeArt {
    private val ids = HashMap<String, Int>()

    @SuppressLint("DiscouragedApi")
    @DrawableRes
    fun idOf(context: Context, name: String): Int = ids.getOrPut(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }

    fun spec(context: Context, id: String, tier: Int): BadgeArtSpec =
        BadgeArtResolver.resolve(id, tier) { idOf(context, it) != 0 }
}

/** 등급 색. 등급 없는 배지는 [untieredColor]. */
fun tierColor(tierIndex: Int): Color = when (BadgeTier.entries.getOrNull(tierIndex)) {
    BadgeTier.BRONZE -> WenitTokens.BadgeBronze
    BadgeTier.SILVER -> WenitTokens.BadgeSilver
    BadgeTier.GOLD -> WenitTokens.BadgeGold
    BadgeTier.DIAMOND -> WenitTokens.BadgeDiamond
    BadgeTier.MASTER -> WenitTokens.BadgeMaster
    null -> WenitTokens.BadgeLocked
}

val untieredColor: Color get() = WenitTokens.BadgeUntiered

fun tierNameRes(tierIndex: Int) = when (BadgeTier.entries.getOrNull(tierIndex)) {
    BadgeTier.SILVER -> R.string.badge_tier_silver
    BadgeTier.GOLD -> R.string.badge_tier_gold
    BadgeTier.DIAMOND -> R.string.badge_tier_diamond
    BadgeTier.MASTER -> R.string.badge_tier_master
    else -> R.string.badge_tier_bronze
}

private fun tierMarkRes(tierIndex: Int) = when (BadgeTier.entries.getOrNull(tierIndex)) {
    BadgeTier.SILVER -> R.string.badge_tier_mark_silver
    BadgeTier.GOLD -> R.string.badge_tier_mark_gold
    BadgeTier.DIAMOND -> R.string.badge_tier_mark_diamond
    BadgeTier.MASTER -> R.string.badge_tier_mark_master
    else -> R.string.badge_tier_mark_bronze
}

private val GRAYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private const val LOCKED_ALPHA = 0.4f
private const val SYMBOL_SCALE = 0.56f

/**
 * 배지 한 칸.
 * @param tier 그릴 등급(0~4). 못 얻었으면 보여 줄 등급(보통 다음 등급)
 * @param earned 얻었으면 색, 못 얻었으면 같은 그림을 흑백·반투명으로
 * @param showLock 못 얻은 배지에 자물쇠를 얹는다(상세의 등급 줄에서는 끈다)
 * @param showMark 얻은 등급 표시(B/S/G/D/M). 등급 이름이 따로 보이는 상세의 등급 줄에서는 끈다
 *
 * 공통 그림은 등급 색 고리 안에 둥글게 자른다. 배지·등급 전용 그림과 틀+기호 합성은 모양이 등급을 나타내므로 고리 없이 그린다.
 * 등급 있는 배지를 얻었으면 오른쪽 아래에 작은 등급 표시(B/S/G/D/M)를 붙인다.
 */
@Composable
fun BadgeMedal(
    id: String,
    tier: Int,
    earned: Boolean,
    size: Dp = WenitTokens.BadgeSize,
    modifier: Modifier = Modifier,
    showLock: Boolean = !earned,
    showMark: Boolean = true
) {
    val context = LocalContext.current
    val def = BadgeCatalog.byId(id)
    val tiered = def?.tiered == true
    val t = if (tiered) tier.coerceIn(0, BadgeTier.entries.lastIndex) else 0
    val spec = remember(id, t) { BadgeArt.spec(context, id, t) }
    val filter = if (earned) null else GRAYSCALE
    val fade = Modifier.alpha(if (earned) 1f else LOCKED_ALPHA)
    val description = stringResource(R.string.badge_image_description, def?.name.orEmpty())
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when (spec) {
            is BadgeArtSpec.Image -> Image(
                painterResource(BadgeArt.idOf(context, spec.name)), description,
                colorFilter = filter, modifier = Modifier.fillMaxSize().then(fade)
            )
            is BadgeArtSpec.Composite -> Box(Modifier.fillMaxSize().then(fade), contentAlignment = Alignment.Center) {
                Image(painterResource(BadgeArt.idOf(context, spec.frame)), description, colorFilter = filter, modifier = Modifier.fillMaxSize())
                Image(painterResource(BadgeArt.idOf(context, spec.symbol)), null, colorFilter = filter, modifier = Modifier.size(size * SYMBOL_SCALE))
            }
            is BadgeArtSpec.Common -> Image(
                painterResource(R.drawable.badge_common), description,
                colorFilter = filter,
                modifier = Modifier
                    .fillMaxSize()
                    .border(WenitTokens.BadgeRing, if (!earned) WenitTokens.BadgeLocked else if (tiered) tierColor(t) else untieredColor, CircleShape)
                    .padding(WenitTokens.BadgeRing)
                    .clip(CircleShape)
                    .then(fade)
            )
        }
        if (!earned && showLock) {
            Icon(
                painterResource(R.drawable.ic_lock), contentDescription = stringResource(R.string.badge_locked),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(size / 3)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                    .padding(size / 15)
            )
        } else if (earned && tiered && showMark) {
            Text(
                stringResource(tierMarkRes(t)),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(WenitTokens.TierMarkSize)
                    .background(tierColor(t), CircleShape)
                    .border(WenitTokens.TierMarkBorder, MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(top = WenitTokens.TierMarkTextInset)
            )
        }
    }
}

// --- 진열장 ---

@Composable
fun ShowcaseScreen(vm: ShowcaseViewModel, onSeen: () -> Unit) {
    val s by vm.state.collectAsState()
    OnResume {
        vm.refresh()
        onSeen()
    }
    ScreenColumn(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.tab_showcase), style = MaterialTheme.typography.headlineMedium)
        WenitCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Caption(stringResource(R.string.showcase_streak_title), MaterialTheme.colorScheme.onPrimaryContainer)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(WenitTokens.SpaceM)) {
                Text(
                    stringResource(R.string.showcase_streak_days, s.streak.current),
                    style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    stringResource(R.string.showcase_best, s.streak.best),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = WenitTokens.SpaceXs)
                )
            }
            Caption(stringResource(R.string.showcase_streak_note), MaterialTheme.colorScheme.onPrimaryContainer)
        }
        SectionTitle(stringResource(R.string.showcase_badges))
        if (s.usesSeed) Caption(stringResource(R.string.showcase_seed_note))
        if (s.loading) {
            CircularProgressIndicator()
            return@ScreenColumn
        }
        s.badges.chunked(GRID_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WenitTokens.SpaceS)) {
                row.forEach { b -> BadgeCell(b, Modifier.weight(1f)) { vm.select(b) } }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    s.selected?.let { BadgeDetailDialog(it) { vm.select(null) } }
}

private const val GRID_COLUMNS = 3

@Composable
private fun BadgeCell(b: BadgeProgress, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = WenitTokens.SpaceS),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WenitTokens.SpaceXs)
    ) {
        Box {
            // 얻었으면 가장 높은 등급, 못 얻었으면 첫 등급 그림을 흑백으로
            BadgeMedal(b.def.id, maxOf(b.tierIndex, 0), earned = b.earned)
            if (b.isNew) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(WenitTokens.NewDotSize)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
            }
        }
        Text(
            b.def.name, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = if (b.earned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        val next = b.nextThreshold
        if (next != null) {
            LinearProgressIndicator(
                progress = { (b.value.toFloat() / next).coerceIn(0f, 1f) },
                modifier = Modifier.width(WenitTokens.BadgeSize).height(WenitTokens.ProgressHeight).clip(CircleShape)
            )
            Text(
                stringResource(R.string.badge_progress, minOf(b.value, next), next),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (b.def.tiered) {
            Text(stringResource(tierNameRes(b.tierIndex)), style = MaterialTheme.typography.labelMedium, color = tierColor(b.tierIndex))
        } else {
            Text(stringResource(R.string.badge_untiered_earned), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BadgeDetailDialog(b: BadgeProgress, onDismiss: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val format = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(b.def.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(WenitTokens.SpaceS)
            ) {
                if (b.def.tiered) {
                    // 5등급을 한 줄로. 얻은 등급은 색, 못 얻은 등급은 흑백·반투명
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        b.def.thresholds.indices.forEach { i ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                BadgeMedal(b.def.id, i, earned = i <= b.tierIndex, size = WenitTokens.BadgeTierRowSize, showLock = false, showMark = false)
                                Text(
                                    stringResource(tierNameRes(i)), style = MaterialTheme.typography.labelMedium,
                                    color = if (i <= b.tierIndex) tierColor(i) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        if (b.earned) stringResource(R.string.badge_detail_current, stringResource(tierNameRes(b.tierIndex)))
                        else stringResource(R.string.badge_locked),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (b.earned) tierColor(b.tierIndex) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    BadgeMedal(b.def.id, 0, earned = b.earned, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                Text(stringResource(R.string.badge_detail_condition), style = MaterialTheme.typography.titleMedium)
                b.def.thresholds.indices.forEach { i ->
                    val earnedAt = b.earnedAt[i]
                    val tierName = if (b.def.tiered) stringResource(tierNameRes(i)) + " · " else ""
                    Caption(
                        tierName + b.def.conditionAt(i) +
                            (earnedAt?.let { "\n" + stringResource(R.string.badge_detail_earned_on, format.format(Instant.ofEpochMilli(it).atZone(zone))) } ?: ""),
                        if (earnedAt != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val next = b.nextThreshold
                if (next != null) {
                    Text(
                        if (b.def.tiered) {
                            stringResource(R.string.badge_detail_next, stringResource(tierNameRes(b.tierIndex + 1)), minOf(b.value, next), next, b.def.unit)
                        } else {
                            stringResource(R.string.badge_detail_untiered_progress, minOf(b.value, next), next, b.def.unit)
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    LinearProgressIndicator(
                        progress = { (b.value.toFloat() / next).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(WenitTokens.ProgressHeight).clip(CircleShape)
                    )
                } else {
                    Text(
                        stringResource(if (b.def.tiered) R.string.badge_detail_max else R.string.badge_untiered_earned),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                if (b.fromSeed) Caption(stringResource(R.string.badge_detail_example))
            }
        }
    )
}

// --- 축하 화면 ---

/**
 * 배지를 얻었을 때. 캐릭터는 위, 배지는 아래에 따로 두어 겹치지 않는다. 배지는 작게 시작해 튀어 오르며 커진다.
 * 앱 화면 안에서만 뜬다(쇼츠를 보는 중에는 앱 화면이 보이지 않으므로 뜨지 않는다).
 */
@Composable
fun CelebrationDialog(badge: EarnedBadge, onClose: () -> Unit) {
    val def: BadgeDef = BadgeCatalog.byId(badge.id) ?: run {
        LaunchedEffect(badge) { onClose() }
        return
    }
    val scale = remember(badge) { Animatable(0.3f) }
    val alpha = remember(badge) { Animatable(0f) }
    LaunchedEffect(badge) {
        alpha.animateTo(1f, tween(CELEBRATE_FADE_MS))
    }
    LaunchedEffect(badge) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(WenitTokens.SpaceXl),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.padding(WenitTokens.SpaceXl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WenitTokens.SpaceM)
            ) {
                Image(
                    painterResource(R.drawable.wenit_character),
                    contentDescription = stringResource(R.string.popup_character_description),
                    modifier = Modifier.size(WenitTokens.CharacterSize)
                )
                Text(stringResource(R.string.celebrate_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                BadgeMedal(
                    def.id, badge.tier, earned = true, size = WenitTokens.BadgeCelebrateSize,
                    modifier = Modifier.scale(scale.value).alpha(alpha.value)
                )
                Text(def.name, style = MaterialTheme.typography.titleMedium)
                if (def.tiered) {
                    Text(
                        stringResource(R.string.celebrate_tier, stringResource(tierNameRes(badge.tier))),
                        style = MaterialTheme.typography.bodyLarge, color = tierColor(badge.tier)
                    )
                }
                Caption(def.conditionAt(badge.tier.coerceIn(def.thresholds.indices)))
                PrimaryButton(stringResource(R.string.celebrate_close), onClick = onClose)
            }
        }
    }
}

private const val CELEBRATE_FADE_MS = 250
