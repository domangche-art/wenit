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
 * 배지 그림 연결. drawable-nodpi 에 `badge_<배지 id>` 파일(예: badge_steady.webp)을 넣으면 그 배지만 그 그림을 쓴다.
 * 없으면 공통 그림([R.drawable.badge_common], 디자인 팀 시험 그림)을 쓴다. 코드를 바꿀 필요가 없다.
 * 등급은 그림 바깥 고리 색과 작은 등급 표시로 그리므로 그림 파일에는 등급을 넣지 않는다.
 */
object BadgeArt {
    private val cache = HashMap<String, Int>()

    @SuppressLint("DiscouragedApi") // 파일만 넣으면 바뀌게 하려고 이름으로 찾는다. 축소 시 res/raw/keep_badges.xml 로 남긴다.
    @DrawableRes
    fun of(context: Context, id: String): Int = cache.getOrPut(id) {
        context.resources.getIdentifier("badge_$id", "drawable", context.packageName).takeIf { it != 0 } ?: R.drawable.badge_common
    }
}

fun tierColor(tierIndex: Int): Color = when (tierIndex) {
    0 -> WenitTokens.BadgeBronze
    1 -> WenitTokens.BadgeSilver
    2 -> WenitTokens.BadgeGold
    else -> WenitTokens.BadgeLocked
}

fun tierNameRes(tierIndex: Int) = when (BadgeTier.entries.getOrNull(tierIndex)) {
    BadgeTier.SILVER -> R.string.badge_tier_silver
    BadgeTier.GOLD -> R.string.badge_tier_gold
    else -> R.string.badge_tier_bronze
}

private fun tierMarkRes(tierIndex: Int) = when (tierIndex) {
    1 -> R.string.badge_tier_mark_silver
    2 -> R.string.badge_tier_mark_gold
    else -> R.string.badge_tier_mark_bronze
}

private val GRAYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private const val LOCKED_ALPHA = 0.4f

/**
 * 배지 하나. 등급 색 고리 안에 그림, 오른쪽 아래에 작은 등급 표시.
 * 못 얻은 배지([tierIndex] < 0)는 같은 그림을 흑백·반투명으로 그리고 자물쇠를 얹는다.
 */
@Composable
fun BadgeMedal(id: String, tierIndex: Int, size: Dp = WenitTokens.BadgeSize, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locked = tierIndex < 0
    val name = BadgeCatalog.byId(id)?.name.orEmpty()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            painterResource(BadgeArt.of(context, id)),
            contentDescription = stringResource(R.string.badge_image_description, name),
            colorFilter = if (locked) GRAYSCALE else null,
            modifier = Modifier
                .fillMaxSize()
                .border(WenitTokens.BadgeRing, tierColor(tierIndex), CircleShape)
                .padding(WenitTokens.BadgeRing)
                .clip(CircleShape)
                .alpha(if (locked) LOCKED_ALPHA else 1f)
        )
        if (locked) {
            Icon(
                painterResource(R.drawable.ic_lock), contentDescription = stringResource(R.string.badge_locked),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .size(size / 3)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                    .padding(size / 15)
            )
        } else {
            Text(
                stringResource(tierMarkRes(tierIndex)),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(WenitTokens.TierMarkSize)
                    .background(tierColor(tierIndex), CircleShape)
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
            BadgeMedal(b.def.id, b.tierIndex)
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
        } else {
            Text(stringResource(tierNameRes(b.tierIndex)), style = MaterialTheme.typography.labelMedium, color = tierColor(b.tierIndex))
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
            Column(verticalArrangement = Arrangement.spacedBy(WenitTokens.SpaceS)) {
                BadgeMedal(b.def.id, b.tierIndex, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(stringResource(R.string.badge_detail_condition), style = MaterialTheme.typography.titleMedium)
                b.def.thresholds.indices.forEach { i ->
                    val earnedAt = b.earnedAt[i]
                    Caption(
                        "${stringResource(tierNameRes(i))} · ${b.def.conditionAt(i)}" +
                            (earnedAt?.let { "\n" + stringResource(R.string.badge_detail_earned, stringResource(tierNameRes(i)), format.format(Instant.ofEpochMilli(it).atZone(zone))) } ?: ""),
                        if (earnedAt != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val next = b.nextThreshold
                if (next != null) {
                    Text(
                        stringResource(
                            R.string.badge_detail_next, stringResource(tierNameRes(b.tierIndex + 1)), minOf(b.value, next), next, b.def.unit
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    LinearProgressIndicator(
                        progress = { (b.value.toFloat() / next).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(WenitTokens.ProgressHeight).clip(CircleShape)
                    )
                } else {
                    Text(stringResource(R.string.badge_detail_max), style = MaterialTheme.typography.bodyLarge)
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
                    def.id, badge.tier, size = WenitTokens.BadgeCelebrateSize,
                    modifier = Modifier.scale(scale.value).alpha(alpha.value)
                )
                Text(def.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.celebrate_tier, stringResource(tierNameRes(badge.tier))),
                    style = MaterialTheme.typography.bodyLarge, color = tierColor(badge.tier)
                )
                Caption(def.conditionAt(badge.tier.coerceIn(def.thresholds.indices)))
                PrimaryButton(stringResource(R.string.celebrate_close), onClick = onClose)
            }
        }
    }
}

private const val CELEBRATE_FADE_MS = 250
