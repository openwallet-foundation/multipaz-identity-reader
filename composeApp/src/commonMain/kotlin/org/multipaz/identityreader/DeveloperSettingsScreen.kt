package org.multipaz.identityreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DoorBack
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.developer_settings_screen_title
import org.jetbrains.compose.resources.stringResource

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

                val entries = mutableListOf<@Composable () -> Unit>()
                entries.add {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            modifier = Modifier.size(32.dp),
                            imageVector = Icons.Outlined.Bluetooth,
                            contentDescription = null
                        )
                        EntryItem(
                            modifier = Modifier.weight(1.0f),
                            key = "Use L2CAP",
                            valueText = "If enabled, L2CAP will be enabled for Bluetooth Low Energy connections"
                        )
                        Checkbox(
                            checked = settingsModel.bleL2capEnabled.collectAsState().value,
                            onCheckedChange = { value ->
                                settingsModel.bleL2capEnabled.value = value
                            },
                        )
                    }
                }
                entries.add {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            modifier = Modifier.size(32.dp),
                            imageVector = Icons.Outlined.Bluetooth,
                            contentDescription = null
                        )
                        EntryItem(
                            modifier = Modifier.weight(1.0f),
                            key = "Use L2CAP in engagement",
                            valueText = "If enabled, L2CAP will be enabled for Bluetooth Low Energy connections " +
                                    "but only during device engagement"
                        )
                        Checkbox(
                            checked = settingsModel.bleL2capInEngagementEnabled.collectAsState().value,
                            onCheckedChange = { value ->
                                settingsModel.bleL2capInEngagementEnabled.value = value
                            },
                        )
                    }
                }

                entries.add {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            modifier = Modifier.size(32.dp),
                            imageVector = Icons.Outlined.Nfc,
                            contentDescription = null
                        )
                        EntryItem(
                            modifier = Modifier.weight(1.0f),
                            key = "Insert extra frames in NFC polling loop",
                            valueText = "If enabled, extra frames will be inserted " +
                                    "to enable the wallet to detect this is an Identity Reader"
                        )
                        Checkbox(
                            enabled = getPlatformUtils().nfcPollingFramesInsertionSupported,
                            checked = settingsModel.insertNfcPollingFrames.collectAsState().value &&
                                    getPlatformUtils().nfcPollingFramesInsertionSupported,
                            onCheckedChange = { value ->
                                settingsModel.insertNfcPollingFrames.value = value
                            },
                        )
                    }
                }

                entries.add {
                    Row(
                        modifier = Modifier.clickable {
                            settingsModel.devMode.value = false
                            onBackPressed()
                        },
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            modifier = Modifier.size(32.dp),
                            imageVector = Icons.Outlined.DoorBack,
                            contentDescription = null
                        )
                        EntryItem(
                            modifier = Modifier.weight(1.0f),
                            key = "Exit developer mode",
                            valueText = "You can reenter developer mode by tapping the title on the main screen five times"
                        )
                    }
                }

                EntryList(
                    title = null,
                    entries = entries
                )
            }
        }
    }
}
