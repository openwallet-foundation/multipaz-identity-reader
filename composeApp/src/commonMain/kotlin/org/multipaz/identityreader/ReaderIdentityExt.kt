package org.multipaz.identityreader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.multipaz.compose.decodeImage

@Composable
fun ReaderIdentity.Icon(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    if (displayIcon != null) {
        val bitmap = remember { decodeImage(displayIcon!!.toByteArray()) }
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                modifier = modifier.size(size),
                bitmap = bitmap,
                contentDescription = null,
            )
        }
    } else {
        val name = displayName
        val initials = name.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .take(2)
            .joinToString("")

        val color = Color(name.hashCode().toLong() or 0xFF000000) // Ensure alpha is not zero

        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                style = TextStyle(color = Color.White, fontSize = (size.value / 2).sp)
            )
        }
    }
}
