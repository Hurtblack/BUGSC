package com.euedrc.bugsc.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 底部栏的一个选项数据 */
data class MobiGlasItem(val label: String, val icon: ImageVector)

// 与 demo 一致的最终配色 / 参数
private val Accent = Color(0xFFFFFFFF)
private val IconIdle = Color(0xFF7A7A7A)
private val FrameLine = Color(0x335A8CB0)
private val FrameFill = Color(0x0D5A8CB0)
private val AccentLine = Color(0xFFFF9A3C)
// 透明：让根布局的 HUD 背景透过底栏，无缝衔接内容区
private val BarBg = Color.Transparent
private val Divider = Color(0x332A4862)

/**
 * mobiGlas 风格底部栏：整条后仰透视，选中项图标以底边为铰链立起，
 * 同时联动自绘框体的轻微上抬、顶部拱起和底部凹口。
 * 纯 Compose，可被任意 Activity / Fragment 通过 ComposeView 使用。
 */
@Composable
fun MobiGlasBottomBar(
    items: List<MobiGlasItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Surface(color = BarBg, tonalElevation = 0.dp, modifier = modifier) {
        Column {
            // 台面边缘：一条细分隔线，把底栏与内容区做轻微区隔
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Divider),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(108.dp)
                    // 整条 bar 后仰透视，像低头看手腕上的手环 (顶边后退)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        rotationX = 26f
                        cameraDistance = 9f * density.density
                    }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    MobiGlasTab(
                        item = item,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MobiGlasTab(item: MobiGlasItem, selected: Boolean, onClick: () -> Unit) {
    val density = LocalDensity.current

    // 选中时内容更接近“竖立”状态，并带一点手环风格的放大。
    val tilt by animateFloatAsState(
        targetValue = if (selected) -10f else 45f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "tilt",
    )
    val lift by animateDpAsState(
        targetValue = if (selected) (-18).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "lift",
    )
    val frameLift by animateDpAsState(
        targetValue = if (selected) (-12).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "frameLift",
    )
    val glow by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "glow",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow),
        label = "scale",
    )
    val contentRestOffset by animateDpAsState(
        targetValue = if (selected) 0.dp else (-8).dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "contentRestOffset",
    )
    val iconColor = lerpColor(IconIdle, Accent, glow)

    Box(
        modifier = Modifier
            .width(92.dp)
            .height(60.dp)
            .graphicsLayer {
                translationY = frameLift.toPx()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            SelectedTabFrame()
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(FrameFill, RoundedCornerShape(14.dp))
                    .border(1.dp, FrameLine, RoundedCornerShape(14.dp)),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    rotationX = tilt
                    translationY = lift.toPx() + contentRestOffset.toPx()
                    scaleX = scale
                    scaleY = scale
                    cameraDistance = 4.5f * density.density
                }
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = iconColor,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = item.label,
                color = iconColor,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SelectedTabFrame(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(60.dp)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val radius = with(density) { 14.dp.toPx() }
        val notchDepth = with(density) { 6.dp.toPx() }
        val notchWidth = with(density) { 28.dp.toPx() }
        val accentWidth = with(density) { 20.dp.toPx() }
        val accentGap = with(density) { 4.dp.toPx() }
        val strokeWidth = with(density) { 1.dp.toPx() }

        Canvas(modifier = Modifier.matchParentSize()) {
            val framePath = braceletFramePath(
                width = widthPx,
                height = heightPx,
                radius = radius,
                notchWidth = notchWidth,
                notchDepth = notchDepth,
            )
            drawPath(path = framePath, color = FrameFill)
            drawPath(path = framePath, color = FrameLine, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))

            val center = widthPx / 2f
            val accentStart = center - (accentWidth / 2f)
            val accentEnd = center + (accentWidth / 2f)
            val notchLeft = center - (notchWidth / 2f)
            val notchRight = center + (notchWidth / 2f)
            val accentY = heightPx - (notchDepth * 0.55f)

            drawLine(
                color = AccentLine,
                start = androidx.compose.ui.geometry.Offset(
                    x = maxOf(accentStart, notchLeft + accentGap),
                    y = accentY,
                ),
                end = androidx.compose.ui.geometry.Offset(
                    x = minOf(accentEnd, notchRight - accentGap),
                    y = accentY,
                ),
                strokeWidth = strokeWidth * 1.8f,
            )
        }
    }
}

private fun braceletFramePath(
    width: Float,
    height: Float,
    radius: Float,
    notchWidth: Float,
    notchDepth: Float,
): Path {
    val path = Path()
    val left = 0f
    val right = width
    val bottom = height
    // 顶边为平直线；「弹起」的空间感由整框 frameLift 上移动画体现
    val top = 0f
    val center = width / 2f
    val notchLeft = center - (notchWidth / 2f)
    val notchRight = center + (notchWidth / 2f)

    path.moveTo(left + radius, top)
    path.lineTo(right - radius, top)
    path.quadraticTo(right, top, right, top + radius)
    path.lineTo(right, bottom - radius)
    path.quadraticTo(right, bottom, right - radius, bottom)
    path.lineTo(notchRight, bottom)
    path.quadraticTo(center + notchWidth * 0.28f, bottom, center, bottom - notchDepth)
    path.quadraticTo(center - notchWidth * 0.28f, bottom, notchLeft, bottom)
    path.lineTo(left + radius, bottom)
    path.quadraticTo(left, bottom, left, bottom - radius)
    path.lineTo(left, top + radius)
    path.quadraticTo(left, top, left + radius, top)
    path.close()
    return path
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
