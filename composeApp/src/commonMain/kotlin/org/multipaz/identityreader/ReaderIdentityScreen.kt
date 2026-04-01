package org.multipaz.identityreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.app_icon
import multipazidentityreader.composeapp.generated.resources.reader_identity_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.compose.pickers.rememberFilePicker
import org.multipaz.crypto.X509CertChain
import org.multipaz.prompt.PassphraseEvaluation
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.requestPassphrase
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.util.Logger

private const val TAG = "ReaderIdentityScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderIdentityScreen(
    promptModel: PromptModel,
    readerBackendClient: ReaderBackendClient,
    settingsModel: SettingsModel,
    onBackPressed: () -> Unit,
    onShowCertificateChain: (certChain: X509CertChain) -> Unit
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    val showImportErrorDialog = remember { mutableStateOf<String?>(null) }
    val availableReaderIdentities = mutableStateOf<List<ReaderIdentity>?>(null)

    val importReaderKeyFilePicker = rememberFilePicker(
        types = listOf(
            "application/x-pkcs12",
        ),
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                val pkcs12Contents = files[0]
                coroutineScope.launch {
                    try {
                        val passphrase = promptModel.requestPassphrase(
                            title = "Import reader certificate",
                            subtitle = "The PKCS#12 file is protected by a passphrase which have " +
                                    "should have been shared with you. Enter the passphrase to continue",
                            passphraseConstraints = PassphraseConstraints.NONE,
                            passphraseEvaluator = { passphrase ->
                                try {
                                    parsePkcs12(pkcs12Contents, passphrase)
                                    PassphraseEvaluation.OK
                                } catch (e: WrongPassphraseException) {
                                    Logger.w(TAG, "Wrong passphrase", e)
                                    "Wrong passphrase. Try again"
                                    PassphraseEvaluation.TryAgain
                                } catch (_: Throwable) {
                                    // If parsing fails for reasons other than the wrong passphrase
                                    // supplied, just pretend the passphrase worked and we'll catch
                                    // the error below and show it to the user
                                    PassphraseEvaluation.OK
                                }
                            }
                        )
                        val (privateKey, certChain) = parsePkcs12(pkcs12Contents, passphrase)
                        require(privateKey.publicKey == certChain.certificates[0].ecPublicKey) {
                            "First certificate is not for the given key"
                        }
                        certChain.validate()
                        println("first : ${certChain.certificates[0].toPem()}")
                        println("second : ${certChain.certificates[1].toPem()}")
                        // TODO: add a couple of additional checks for example that the leaf certificate
                        //   has the correct keyUsage flags, etc.
                        //
                        settingsModel.customReaderAuthKey.value = privateKey
                        settingsModel.customReaderAuthCertChain.value = certChain
                        settingsModel.readerAuthMethod.value = ReaderAuthMethod.CUSTOM_KEY
                    } catch (_: PromptDismissedException) {
                        /* do nothing */
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        showImportErrorDialog.value = "Importing reader key failed: $e"
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
                Text(text = "Error importing reader key")
            },
            text = {
                Text(text = it)
            }
        )
    }

    LaunchedEffect(Unit) {
        if (settingsModel.signedIn.value != null) {
            coroutineScope.launch {
                try {
                    availableReaderIdentities.value = readerBackendClient.getReaderIdentities()
                    println("num Reader Identities = ${availableReaderIdentities.value?.size}")
                    availableReaderIdentities.value?.forEach {
                        println(it)
                    }
                } catch (e: Throwable) {
                    Logger.i(TAG, "Error loading identities", e)
                    availableReaderIdentities.value = emptyList()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = AnnotatedString(stringResource(Res.string.reader_identity_title)),
                onBackPressed = onBackPressed,
            )
        },
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            val readerAuthMethod = settingsModel.readerAuthMethod.collectAsState()
            val readerAuthMethodGoogleId = settingsModel.readerAuthMethodGoogleIdentity.collectAsState()
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    text = """
When making a request for identity attributes a reader identity may be used to cryptographically
sign the request. The wallet receiving the request may use this to inform the identity holder who
the request is from
                    """.trimIndent().replace("\n", " ").trim(),
                )

                FloatingItemList (
                    title = "Reader identity",
                ) {
                    FloatingItemHeadingTextAndCheckbox(
                        heading = "Don't use reader authentication",
                        text = "The request won't be signed and the receiving wallet " +
                                "won't know who's asking",
                        enabled = true,
                        checked = settingsModel.readerAuthMethod.value == ReaderAuthMethod.NO_READER_AUTH,
                        onCheckedChanged = { newValue ->
                            if (newValue) {
                                settingsModel.readerAuthMethod.value = ReaderAuthMethod.NO_READER_AUTH
                                settingsModel.customReaderAuthKey.value = null
                                settingsModel.customReaderAuthCertChain.value = null
                            }
                        },
                        image = {
                            Icon(
                                modifier = Modifier.size(40.dp),
                                imageVector = Icons.Outlined.Block,
                                contentDescription = null
                            )
                        }
                    )
                    FloatingItemHeadingTextAndCheckbox(
                        heading = "Use reader certificate from PKCS#12 file",
                        text = "Uses a custom key to sign requests. The same key will be " +
                                "used to sign all requests",
                        enabled = true,
                        checked = settingsModel.readerAuthMethod.value == ReaderAuthMethod.CUSTOM_KEY,
                        onCheckedChanged = { newValue ->
                            if (newValue) {
                                importReaderKeyFilePicker.launch()
                            }
                        },
                        image = {
                            Icon(
                                modifier = Modifier.size(40.dp),
                                imageVector = Icons.Outlined.Badge,
                                contentDescription = null
                            )
                        }
                    )
                    FloatingItemHeadingTextAndCheckbox(
                        heading = "Multipaz Identity Reader",
                        text = "The Multipaz Identity Reader CA will be used to " +
                                "certify single-use reader keys",
                        enabled = true,
                        checked = settingsModel.readerAuthMethod.value == ReaderAuthMethod.STANDARD_READER_AUTH,
                        onCheckedChanged = { newValue ->
                            if (newValue) {
                                settingsModel.readerAuthMethod.value = ReaderAuthMethod.STANDARD_READER_AUTH
                                settingsModel.customReaderAuthKey.value = null
                                settingsModel.customReaderAuthCertChain.value = null
                                // Prime the cache
                                coroutineScope.launch {
                                    try {
                                        readerBackendClient.getKey()
                                    } catch (e: Throwable) {
                                        Logger.w(TAG, "Error priming cache for standard reader auth", e)
                                    }
                                }
                            }
                        },
                        image = {
                            Icon(
                                modifier = Modifier.size(40.dp),
                                painter = painterResource(Res.drawable.app_icon),
                                tint = Color.Unspecified,
                                contentDescription = null
                            )
                        }
                    )

                    val signedInData = settingsModel.signedIn.collectAsState()
                    signedInData.value?.let {
                        FloatingItemHeadingTextAndCheckbox(
                            heading = it.id,
                            text = "Information from your Google Account (id, email, and profile picture) " +
                                    "will be included in the request",
                            enabled = true,
                            checked = settingsModel.readerAuthMethod.value == ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS,
                            onCheckedChanged = { newValue ->
                                if (newValue) {
                                    settingsModel.readerAuthMethod.value = ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS
                                    settingsModel.customReaderAuthKey.value = null
                                    settingsModel.customReaderAuthCertChain.value = null
                                    // Prime the cache
                                    coroutineScope.launch {
                                        try {
                                            readerBackendClient.getKey(readerIdentityId = "")
                                        } catch (e: Throwable) {
                                            Logger.w(TAG, "Error priming cache for standard reader auth" +
                                                    " w/ Google Account details", e)
                                        }
                                    }
                                }
                            },
                            image = {
                                it.ProfilePicture(
                                    size = 40.dp
                                )
                            }
                        )
                    }

                    availableReaderIdentities.value?.forEach { readerIdentityFromGoogleAccount ->
                        FloatingItemHeadingTextAndCheckbox(
                            heading = readerIdentityFromGoogleAccount.displayName,
                            text = "Reader identity from your Google account",
                            enabled = true,
                            checked = true,
                            onCheckedChanged = { newValue ->
                                if (newValue) {
                                    settingsModel.readerAuthMethod.value = ReaderAuthMethod.IDENTITY_FROM_GOOGLE_ACCOUNT
                                    settingsModel.readerAuthMethodGoogleIdentity.value = readerIdentityFromGoogleAccount
                                    // Prime the cache
                                    coroutineScope.launch {
                                        try {
                                            readerBackendClient.getKey(
                                                readerIdentityId = settingsModel.readerAuthMethodGoogleIdentity.value!!.id
                                            )
                                        } catch (e: Throwable) {
                                            Logger.w(TAG, "Error priming cache for Google Account reader auth", e)
                                        }
                                    }
                                }
                            },
                            image = {
                                readerIdentityFromGoogleAccount.Icon(size = 40.dp)
                            }
                        )
                    }
                }


                if (readerAuthMethod.value != ReaderAuthMethod.NO_READER_AUTH) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        style = MaterialTheme.typography.bodyMedium,
                        text = buildAnnotatedString {
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "cert",
                                    linkInteractionListener = { link ->
                                        when (readerAuthMethod.value) {
                                            ReaderAuthMethod.NO_READER_AUTH -> {}
                                            ReaderAuthMethod.CUSTOM_KEY -> {
                                                onShowCertificateChain(settingsModel.customReaderAuthCertChain.value!!)
                                            }
                                            ReaderAuthMethod.IDENTITY_FROM_GOOGLE_ACCOUNT -> {
                                                coroutineScope.launch {
                                                    onShowCertificateChain(
                                                        readerBackendClient.getKey(
                                                            readerIdentityId = settingsModel
                                                                .readerAuthMethodGoogleIdentity.value!!.id
                                                        ).second
                                                    )
                                                }
                                            }

                                            ReaderAuthMethod.STANDARD_READER_AUTH -> {
                                                coroutineScope.launch {
                                                    onShowCertificateChain(
                                                        readerBackendClient.getKey(readerIdentityId = null).second
                                                    )
                                                }
                                            }

                                            ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS -> {
                                                coroutineScope.launch {
                                                    onShowCertificateChain(
                                                        readerBackendClient.getKey(readerIdentityId = "").second
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                            ) {
                                withStyle(
                                    style = SpanStyle(
                                        color = Color.Blue,
                                        textDecoration = TextDecoration.Underline
                                    )
                                ) {
                                    append("View certificate chain")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
