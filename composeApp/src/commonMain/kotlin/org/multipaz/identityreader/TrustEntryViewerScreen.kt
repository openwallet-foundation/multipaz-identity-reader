package org.multipaz.identityreader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import org.multipaz.compose.trustmanagement.TrustEntryViewer
import org.multipaz.compose.trustmanagement.TrustManagerModel
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.trustmanagement.TrustEntryRical
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustEntryViewerScreen(
    trustManagerModel: TrustManagerModel,
    trustEntryId: String,
    canEditOrDelete: Boolean,
    justImported: Boolean,
    imageLoader: ImageLoader,
    onViewVicalEntry: (vicalCertNum: Int) -> Unit,
    onViewCertificate: (certificate: X509Cert) -> Unit,
    onViewSignerCertificateChain: (certificateChain: X509CertChain) -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }

    val info = trustManagerModel.trustManagerInfos.value.find {
        it.entry.identifier == trustEntryId
    } ?: return

    if (showDeleteConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmationDialog = false },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmationDialog = false }
                ) {
                    Text(text = "Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            showDeleteConfirmationDialog = false
                            trustManagerModel.trustManager.deleteEntry(info.entry)
                            onBack()
                        }
                    }
                ) {
                    Text(text = "Delete")
                }
            },
            title = {
                Text(
                    text = when (info.entry) {
                        is TrustEntryX509Cert -> "Delete certificate?"
                        is TrustEntryVical -> "Delete VICAL?"
                        is TrustEntryRical -> "Delete RICAL?"
                    }
                )
            },
            text = {
                Text(
                    text = when (info.entry) {
                        is TrustEntryX509Cert -> "The certificate will be permanently deleted. This action cannot be undone"
                        is TrustEntryVical -> "The VICAL will be permanently deleted. This action cannot be undone"
                        is TrustEntryRical -> "The RICAL will be permanently deleted. This action cannot be undone"
                    }
                )
            }
        )
    }

    Scaffold(
        topBar = {
            AppBar(
                title = when (info.entry) {
                    is TrustEntryX509Cert -> AnnotatedString("IACA certificate")
                    is TrustEntryVical -> AnnotatedString("VICAL")
                    is TrustEntryRical -> AnnotatedString("RICAL")
                },
                onBackPressed = onBack,
                actions = {
                    if (info.manager.identifier == TRUST_MANAGER_ID_USER) {
                        IconButton(
                            onClick = { onEdit() }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null
                            )
                        }
                        IconButton(
                            onClick = { showDeleteConfirmationDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxSize()
                    .padding(8.dp),
            ) {
                TrustEntryViewer(
                    trustManagerModel = trustManagerModel,
                    trustEntryId = trustEntryId,
                    justImported = justImported,
                    imageLoader = imageLoader,
                    onViewSignerCertificateChain = onViewSignerCertificateChain,
                    onViewVicalEntry = onViewVicalEntry,
                    onViewRicalEntry = { },
                )
            }
        }
    }
}
