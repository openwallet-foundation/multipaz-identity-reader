package org.multipaz.identityreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DoorBack
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.developer_settings_screen_title
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.items.FloatingItemHeadingAndText
import org.multipaz.compose.items.FloatingItemList

@Composable
fun DeveloperSettingsScreen(
    settingsModel: SettingsModel,
    onBackPressed: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppBar(
                title = AnnotatedString(stringResource(Res.string.developer_settings_screen_title)),
                onBackPressed = onBackPressed,
            )
        },
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    modifier = Modifier.padding(vertical = 16.dp),
                    text = """
This screen contain settings used for diagnostics and debugging. In developer mode you can
also double-tap the portrait or error icons on the results screen to view more detailed
information
                    """.trimIndent().replace("\n", " ").trim(),
                )

                FloatingItemList {
                    FloatingItemHeadingTextAndCheckbox(
                        heading = "Use L2CAP",
                        text = "If enabled, L2CAP will be enabled for Bluetooth Low Energy connections",
                        enabled = true,
                        checked = settingsModel.bleL2capEnabled.collectAsState().value,
                        onCheckedChanged = { value -> settingsModel.bleL2capEnabled.value = value},
                        image = {
                            Icon(
                                modifier = Modifier.size(40.dp),
                                imageVector = Icons.Outlined.Bluetooth,
                                contentDescription = null
                            )
                        }
                    )
                    FloatingItemHeadingTextAndCheckbox(
                        heading = "Use L2CAP in engagement",
                        text = "If enabled, L2CAP will be enabled for Bluetooth Low Energy connections " +
                                "but only during device engagement",
                        enabled = true,
                        checked = settingsModel.bleL2capInEngagementEnabled.collectAsState().value,
                        onCheckedChanged = { value -> settingsModel.bleL2capInEngagementEnabled.value = value},
                        image = {
                            Icon(
                                modifier = Modifier.size(40.dp),
                                imageVector = Icons.Outlined.Bluetooth,
                                contentDescription = null
                            )
                        }
                    )
                    FloatingItemHeadingTextAndCheckbox(
                        heading = "Frames in NFC polling loop",
                        text = "If enabled, extra frames will be inserted " +
                                "to enable a wallet to detect this is an Identity Reader",
                        enabled = getPlatformUtils().nfcPollingFramesInsertionSupported,
                        checked = settingsModel.insertNfcPollingFrames.collectAsState().value,
                        onCheckedChanged = { value -> settingsModel.insertNfcPollingFrames.value = value},
                        image = {
                            Icon(
                                modifier = Modifier.size(40.dp),
                                imageVector = Icons.Outlined.Nfc,
                                contentDescription = null
                            )
                        }
                    )
                    FloatingItemHeadingAndText(
                        modifier = Modifier.clickable {
                            settingsModel.devMode.value = false
                            onBackPressed()
                        },
                        heading = "Exit developer mode",
                        text = "You can reenter developer mode by tapping the title on the main screen five times",
                        image = {
                            Icon(
                                modifier = Modifier.size(40.dp),
                                imageVector = Icons.Outlined.DoorBack,
                                contentDescription = null
                            )
                        }
                    )
                }
            }
        }
    }
}
