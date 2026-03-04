package org.multipaz.identityreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.trusted_issuers_screen_title
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.pickers.FilePicker
import org.multipaz.compose.pickers.rememberFilePicker
import org.multipaz.compose.trustmanagement.TrustManagerList
import org.multipaz.compose.trustmanagement.TrustManagerModel
import org.multipaz.crypto.X509Cert
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.trustmanagement.TrustEntry
import org.multipaz.trustmanagement.TrustEntryAlreadyExistsException
import org.multipaz.trustmanagement.TrustMetadata

@Composable
private fun FloatingActionButtonMenu(
    importCertificateFilePicker: FilePicker,
    importVicalFilePicker: FilePicker,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(horizontalAlignment = Alignment.End) {
            AnimatedVisibility(
                visible = isMenuExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    ExtendedFloatingActionButton(
                        text = { Text("Import Certificate") },
                        onClick = {
                            isMenuExpanded = false
                            importCertificateFilePicker.launch()
                        },
                        icon = { Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null
                        ) },
                        elevation = FloatingActionButtonDefaults.elevation(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ExtendedFloatingActionButton(
                        text = { Text("Import VICAL") },
                        onClick = {
                            isMenuExpanded = false
                            importVicalFilePicker.launch()
                        },
                        icon = { Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null
                        ) },
                        elevation = FloatingActionButtonDefaults.elevation(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            FloatingActionButton(
                onClick = { isMenuExpanded = !isMenuExpanded },
                elevation = FloatingActionButtonDefaults.elevation(8.dp),
                content = {
                    Icon(
                        imageVector = if (isMenuExpanded) Icons.Filled.Menu else Icons.Filled.Add,
                        contentDescription = null,
                    )
                }
            )
        }
    }
}

@Composable
fun TrustedIssuersScreen(
    builtInTrustManagerModel: TrustManagerModel,
    userTrustManagerModel: TrustManagerModel,
    settingsModel: SettingsModel,
    imageLoader: ImageLoader,
    onBackPressed: () -> Unit,
    onTrustEntryClicked: (trustManagerId: String, trustEntryId: String, justImported: Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val showImportErrorDialog = remember { mutableStateOf<String?>(null) }

    val importCertificateFilePicker = rememberFilePicker(
        types = listOf(
            "application/x-pem-file",
            "application/x-x509-key; format=pem",
            "application/x-x509-cert; format=pem",
            "application/x-x509-ca-cert",
            "application/x-x509-ca-cert; format=der",
            "application/pkix-cert",
            "application/pkix-crl",
        ),
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                coroutineScope.launch {
                    try {
                        val cert = X509Cert.fromPem(pemEncoding = files[0].toByteArray().decodeToString())
                        val entry = userTrustManagerModel.trustManager.addX509Cert(
                            certificate = cert,
                            metadata = TrustMetadata()
                        )
                        onTrustEntryClicked(TRUST_MANAGER_ID_USER, entry.identifier, true)
                    } catch (_: TrustEntryAlreadyExistsException) {
                        showImportErrorDialog.value = "A certificate with this Subject Key Identifier already exists"
                    } catch (e: Throwable) {
                        showImportErrorDialog.value = "Importing certificate failed: $e"
                    }
                }
            }
        }
    )

    val importVicalFilePicker = rememberFilePicker(
        // Unfortunately there's no well-defined MIME type for a VICAL.
        types = listOf(
            "*/*",
        ),
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                coroutineScope.launch {
                    try {
                        val encodedSignedVical = files[0]
                        // Parse it once, to check the signature is good
                        val signedVical = SignedVical.parse(
                            encodedSignedVical = encodedSignedVical.toByteArray(),
                            disableSignatureVerification = false
                        )
                        val entry = userTrustManagerModel.trustManager.addVical(
                            encodedSignedVical = encodedSignedVical,
                            metadata = TrustMetadata()
                        )
                        onTrustEntryClicked(TRUST_MANAGER_ID_USER, entry.identifier, true)
                    } catch (e: Throwable) {
                        showImportErrorDialog.value = "Importing VICAL failed: $e"
                    }
                }
            }
        }
    )

    showImportErrorDialog.value?.let {
        AlertDialog(
            onDismissRequest = { showImportErrorDialog.value = null },
            confirmButton = {
                TextButton(
                    onClick = { showImportErrorDialog.value = null }
                ) {
                    Text(text = "Close")
                }
            },
            title = {
                Text(text = "Error importing certificate")
            },
            text = {
                Text(text = it)
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButtonMenu(
                importCertificateFilePicker = importCertificateFilePicker,
                importVicalFilePicker = importVicalFilePicker
            )
        },
        topBar = {
            AppBar(
                title = AnnotatedString(stringResource(Res.string.trusted_issuers_screen_title)),
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
Identity issuers are identified using IACA certificates (Issuing Authority Certificate Authority) which may be 
distributed in lists from a trusted provider, called a VICAL (Verified Issuer Certificate Authority List)
                    """.trimIndent().replace("\n", " ").trim(),
                )

                TrustManagerList(
                    trustManagerModel = builtInTrustManagerModel,
                    title = "Built-in",
                    imageLoader = imageLoader,
                    noItems = {
                        Text(
                            text = "No built-in trusted issuers",
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary,
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center
                        )
                    },
                    onTrustEntryClicked = { info ->
                        onTrustEntryClicked(info.manager.identifier, info.entry.identifier, false)
                    }
                )

                TrustManagerList(
                    trustManagerModel = userTrustManagerModel,
                    title = "Manually imported",
                    imageLoader = imageLoader,
                    noItems = {
                        Text(
                            text = "IACA certificates and VICALs manually imported will appear in this list",
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondary,
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center
                        )
                    },
                    onTrustEntryClicked = { info ->
                        onTrustEntryClicked(info.manager.identifier, info.entry.identifier, false)
                    }
                )
            }
        }
    }
}
