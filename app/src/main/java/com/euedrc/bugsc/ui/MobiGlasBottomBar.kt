package com.euedrc.bugsc.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
// 透明：让根布局的 HUD 背景透过底栏，无缝衔接内容区
private val BarBg = Color.Transparent
private val Divider = Color(0x332A4862)

/**
 * mobiGlas 风格底部栏：整条后仰透视，选中项图标以底边为铰链立起并顶出固定边框。
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

    // 只有图标会动：未选中斜躺 38°，选中时以底边为铰链立起 (0°) 并上移顶出边框上沿。
    val tilt by animateFloatAsState(
        targetValue = if (selected) 0f else 38f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "tilt",
    )
    val lift by animateDpAsState(
        targetValue = if (selected) (-20).dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "lift",
    )
    val glow by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "glow",
    )
    val iconColor = lerpColor(IconIdle, Accent, glow)

    // 固定圆角边框：框住整个选项，恒定不变 (不裁剪，图标可溢出顶部)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(92.dp)
            .height(60.dp)
            .background(FrameFill, RoundedCornerShape(14.dp))
            .border(1.dp, FrameLine, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = iconColor,
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    rotationX = tilt
                    translationY = lift.toPx()
                    cameraDistance = 4.5f * density.density
                },
        )
        Text(
            text = item.label,
            color = iconColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier
                .padding(top = 6.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    rotationX = tilt
                    cameraDistance = 4.5f * density.density
                },
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
