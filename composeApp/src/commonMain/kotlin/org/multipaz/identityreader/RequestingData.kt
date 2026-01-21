package org.multipaz.identityreader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun RequestingData(
    settingsModel: SettingsModel,
    onClicked: () -> Unit,
) {
    val method = settingsModel.readerAuthMethod.collectAsState()
    val googleIdentity = settingsModel.readerAuthMethodGoogleIdentity.collectAsState()
    val (text, iconFn) = when (method.value) {
        ReaderAuthMethod.NO_READER_AUTH -> {
            Pair(
                "Anonymous reader",
                @Composable {
                    Icon(
                        modifier = Modifier.size(32.dp),
                        imageVector = Icons.Outlined.Block,
                        contentDescription = null
                    )
                }
            )
        }
        ReaderAuthMethod.CUSTOM_KEY -> {
            Pair(
                "Using custom reader key",
                @Composable {
                    Icon(
                        modifier = Modifier.size(32.dp),
                        imageVector = Icons.Outlined.Badge,
                        contentDescription = null
                    )
                }
            )
        }
        ReaderAuthMethod.STANDARD_READER_AUTH -> {
            Pair(
                "Multipaz Identity Reader",
                @Composable {
                    Icon(
                        modifier = Modifier.size(32.dp),
                        painter = painterResource(Res.drawable.app_icon),
                        tint = Color.Unspecified,
                        contentDescription = null
                    )
                }
            )
        }
        ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS -> {
            Pair(
                settingsModel.signedIn.value?.id ?: "",
                @Composable {
                    settingsModel.signedIn.value?.ProfilePicture(
                        size = 32.dp
                    )
                }
            )
        }
        ReaderAuthMethod.IDENTITY_FROM_GOOGLE_ACCOUNT -> {
            Pair(
                googleIdentity.value!!.displayName,
                @Composable { googleIdentity.value!!.Icon() }
            )
        }
    }

    Column(
        modifier = Modifier
            .clip(shape = RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(8.dp)
            .clickable { onClicked() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Requesting data as",
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
            verticalAlignment = Alignment.CenterVertically
        ) {
            iconFn()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

}