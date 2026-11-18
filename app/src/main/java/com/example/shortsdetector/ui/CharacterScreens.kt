package com.example.shortsdetector.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.shortsdetector.R
import com.example.shortsdetector.intervention.character.CharacterSpec
import com.example.shortsdetector.intervention.character.Characters
import com.example.shortsdetector.ui.theme.WenitTokens

/**
 * 캐릭터 고르기(16단계). 온보딩의 "함께할 친구 고르기" 단계와 설정의 "캐릭터 바꾸기" 가 같은 화면을 쓴다.
 * 카드를 누르면 바로 저장되고, 그 캐릭터가 통통 튀며 자기 대사 하나를 말풍선으로 보여 준다.
 *
 * @param onboarding true 면 "다음" 버튼으로 넘어가고, false 면 위쪽 "뒤로" 로 돌아간다
 */
@Composable
fun CharacterPickScreen(vm: CharacterPickViewModel, onboarding: Boolean, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    OnResume(vm::refresh)
    ScreenColumn(Modifier.fillMaxSize()) {
        if (!onboarding) BackRow(onDone)
        Text(
            stringResource(if (onboarding) R.string.character_pick_title else R.string.character_change_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Caption(stringResource(R.string.character_pick_body))
        Characters.ALL.chunked(CARDS_PER_ROW).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WenitTokens.SpaceM)) {
                row.forEach { spec ->
                    CharacterCard(
                        spec, selected = s.selectedId == spec.id,
                        line = s.line.takeIf { s.speakingId == spec.id }, tick = if (s.speakingId == spec.id) s.tick else 0,
                        modifier = Modifier.weight(1f)
                    ) { vm.select(spec.id) }
                }
                // 홀수 개일 때 마지막 줄의 빈칸
                repeat(CARDS_PER_ROW - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (onboarding) PrimaryButton(stringResource(R.string.action_next), onClick = onDone)
    }
}

@Composable
private fun CharacterCard(
    spec: CharacterSpec,
    selected: Boolean,
    line: String?,
    tick: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // 누를 때마다 위로 튀었다가 통통 내려앉는다
    val hop = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick > 0) {
            hop.snapTo(0f)
            hop.animateTo(-HOP_DP, tween(HOP_UP_MS))
            hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected; role = Role.RadioButton },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (selected) colors.primaryContainer else colors.surface),
        border = if (selected) BorderStroke(SELECTED_BORDER, colors.primary) else null
    ) {
        Column(
            Modifier.fillMaxWidth().padding(WenitTokens.SpaceM),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WenitTokens.SpaceS)
        ) {
            // 말풍선 자리는 늘 차지한다(대사가 나올 때 카드 높이가 바뀌지 않게)
            Box(Modifier.fillMaxWidth().heightIn(min = BUBBLE_MIN_HEIGHT), contentAlignment = Alignment.Center) {
                if (line != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceVariant) {
                        Text(
                            line, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = WenitTokens.SpaceS, vertical = WenitTokens.SpaceXs)
                        )
                    }
                }
            }
            Image(
                painterResource(spec.baseArt),
                contentDescription = null,
                modifier = Modifier.size(WenitTokens.CharacterPickSize).offset(y = hop.value.dp)
            )
            Text(
                spec.name, style = MaterialTheme.typography.titleMedium,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface
            )
            Text(
                spec.personality, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
            )
            Text(
                stringResource(if (selected) R.string.character_selected else R.string.character_not_selected),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.primary else colors.onSurfaceVariant
            )
        }
    }
}

private const val CARDS_PER_ROW = 2
private const val HOP_DP = 14f
private const val HOP_UP_MS = 110
private val SELECTED_BORDER = 2.dp

/** 25자 대사가 두 줄로 들어가는 높이 */
private val BUBBLE_MIN_HEIGHT = 52.dp
