package org.multipaz.identityreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.multipaz.compose.items.FloatingItemContainer

// TODO: move to multipaz-compose
@Composable
fun FloatingItemHeadingTextAndCheckbox(
    heading: String,
    text: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChanged: (newValue: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    image: @Composable () -> Unit = {}
) {
    FloatingItemContainer(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.Start),
            verticalAlignment = Alignment.CenterVertically
        ) {
            image()
            Column(
                modifier = Modifier.fillMaxWidth().weight(1.0f)
            ) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Checkbox(
                enabled = enabled,
                checked = checked,
                onCheckedChange = onCheckedChanged,
            )
        }
    }
}
