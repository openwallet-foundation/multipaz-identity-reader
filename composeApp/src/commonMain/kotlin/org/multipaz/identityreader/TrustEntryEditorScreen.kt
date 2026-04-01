package org.multipaz.identityreader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import org.multipaz.compose.trustmanagement.TrustEntryEditor
import org.multipaz.compose.trustmanagement.TrustManagerModel
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustMetadata

@Composable
fun TrustEntryEditorScreen(
    trustManagerModel: TrustManagerModel,
    trustEntryId: String,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var showConfirmationBeforeExiting by remember { mutableStateOf(false) }

    val info = trustManagerModel.trustManagerInfos.value.find {
        it.entry.identifier == trustEntryId
    } ?: return
    val newMetadata = remember { mutableStateOf(info.entry.metadata) }

    if (showConfirmationBeforeExiting) {
        AlertDialog(
            onDismissRequest = { showConfirmationBeforeExiting = false },
            dismissButton = {
                TextButton(
                    onClick = { showConfirmationBeforeExiting = false }
                ) {
                    Text(text = "Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            showConfirmationBeforeExiting = false
                            onBack()
                        }
                    }
                ) {
                    Text(text = "Discard changes")
                }
            },
            title = {
                Text(text = "Discard unsaved changes?")
            },
            text = {
                Text(text = "You have unsaved changes that will be lost if you leave this page")
            }
        )
    }

    Scaffold(
        topBar = {
            AppBar(
                title = AnnotatedString(
                    text = when (info.entry) {
                        is TrustEntryX509Cert -> "Edit IACA certificate"
                        is TrustEntryVical -> "Edit VICAL"
                        else -> "Edit entry"
                    }
                ),
                onBackPressed = {
                    if (newMetadata.value != info.entry.metadata) {
                        showConfirmationBeforeExiting = true
                    } else {
                        onBack()
                    }
                },
                actions = {
                    val contentChanged = (newMetadata.value != info.entry.metadata)
                    Button(
                        enabled = contentChanged,
                        onClick = {
                            coroutineScope.launch {
                                trustManagerModel.trustManager.updateMetadata(info.entry, newMetadata.value)
                                onBack()
                            }
                        }
                    ) {
                        Text(text = "Save")
                    }
                }
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(8.dp),
            ) {
                TrustEntryEditor(
                    trustEntryInfo = info,
                    imageLoader = imageLoader,
                    newMetadata = newMetadata
                )
            }
        }
    }
}