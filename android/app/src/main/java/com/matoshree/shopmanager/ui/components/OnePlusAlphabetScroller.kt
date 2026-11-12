package com.matoshree.shopmanager.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matoshree.shopmanager.ui.theme.DeepCharcoal
import com.matoshree.shopmanager.ui.theme.DeepEmerald
import com.matoshree.shopmanager.ui.theme.MutedCharcoal
import com.matoshree.shopmanager.utils.AlphabetUtils
import kotlin.math.roundToInt

/**
 * OnePlus / ColorOS styled Alphabet Fast Scroller with floating circular bubble indicator
 * and tactile haptic feedback.
 */
@Composable
fun OnePlusAlphabetScroller(
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    alphabetList: List<String> = AlphabetUtils.ALPHABET_LIST,
    activeLetters: Set<String> = emptySet(),
    activeColor: Color = DeepEmerald,
    inactiveColor: Color = MutedCharcoal
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var totalHeightPx by remember { mutableFloatStateOf(1f) }
    var touchYPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var activeLetter by remember { mutableStateOf<String?>(null) }

    val bubbleSize = 56.dp
    val bubbleSizePx = with(density) { bubbleSize.toPx() }

    val bubbleAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "bubbleAlpha"
    )

    val bubbleScale by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bubbleScale"
    )

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                totalHeightPx = coordinates.size.height.toFloat().coerceAtLeast(1f)
            }
    ) {
        // Floating Bubble (OnePlus Style White Circle to the left of alphabet strip)
        if (bubbleAlpha > 0.01f && activeLetter != null) {
            val clampedY = (touchYPx - (bubbleSizePx / 2f))
                .coerceIn(0f, (totalHeightPx - bubbleSizePx).coerceAtLeast(0f))

            Surface(
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 8.dp,
                border = BorderStroke(0.5.dp, Color(0x18000000)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = -with(density) { 68.dp.roundToPx() },
                            y = clampedY.roundToInt()
                        )
                    }
                    .size(bubbleSize)
                    .graphicsLayer {
                        alpha = bubbleAlpha
                        scaleX = bubbleScale
                        scaleY = bubbleScale
                    }
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = activeLetter ?: "",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeepCharcoal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Vertical Alphabet Strip
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(22.dp)
                .fillMaxHeight()
                .pointerInput(alphabetList) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touchYPx = down.position.y.coerceIn(0f, totalHeightPx)
                        val fraction = (touchYPx / totalHeightPx).coerceIn(0f, 0.999f)
                        val index = (fraction * alphabetList.size).toInt().coerceIn(0, alphabetList.size - 1)
                        val initialLetter = alphabetList[index]

                        if (initialLetter != activeLetter) {
                            activeLetter = initialLetter
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onLetterSelected(initialLetter)
                        }
                        isDragging = true

                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (pointer.pressed) {
                                touchYPx = pointer.position.y.coerceIn(0f, totalHeightPx)
                                val currentFraction = (touchYPx / totalHeightPx).coerceIn(0f, 0.999f)
                                val currentIndex = (currentFraction * alphabetList.size).toInt().coerceIn(0, alphabetList.size - 1)
                                val currentLetter = alphabetList[currentIndex]

                                if (currentLetter != activeLetter) {
                                    activeLetter = currentLetter
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onLetterSelected(currentLetter)
                                }
                                pointer.consume()
                            }
                        } while (event.changes.any { it.pressed })

                        isDragging = false
                    }
                },
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            alphabetList.forEach { letter ->
                val isHovered = isDragging && letter == activeLetter
                val hasData = activeLetters.isEmpty() || activeLetters.contains(letter)

                Text(
                    text = letter,
                    fontSize = if (isHovered) 12.sp else 9.5.sp,
                    fontWeight = if (isHovered) FontWeight.ExtraBold else if (hasData) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        isHovered -> activeColor
                        hasData -> DeepCharcoal.copy(alpha = 0.75f)
                        else -> inactiveColor.copy(alpha = 0.35f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 0.5.dp)
                )
            }
        }
    }
}
