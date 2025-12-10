package org.multipaz.identityreader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.multipaz.compose.cropRotateScaleImage
import org.multipaz.compose.decodeImage
import org.multipaz.compose.encodeImageToPng
import org.multipaz.compose.pickers.rememberImagePicker
import org.multipaz.trustmanagement.TrustEntry
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.math.min

@Composable
fun TrustEntryEditorScreen(
    userTrustManager: TrustManagerLocal,
    entryIndex: Int,
    onBackPressed: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val entry = remember { mutableStateOf<TrustEntry?>(null) }
    var showConfirmationBeforeExiting by remember { mutableStateOf(false) }
    var newMetadata by remember { mutableStateOf<TrustMetadata?>(null) }
    val imagePicker = rememberImagePicker(
        allowMultiple = false,
        onResult = { payloads ->
            if (payloads.isNotEmpty()) {
                // Resize to 512x512 and crop so it fits.
                val image = decodeImage(payloads[0].toByteArray())
                val imageSize = min(image.width, image.height)
                val croppedImage = image.cropRotateScaleImage(
                    cx = image.width.toDouble() / 2,
                    cy = image.height.toDouble() / 2,
                    angleDegrees = 0.0,
                    outputWidthPx = imageSize,
                    outputHeightPx = imageSize,
                    targetWidthPx = 512
                )
                val encodedCroppedImage = encodeImageToPng(croppedImage)
                newMetadata = TrustMetadata(
                    displayName = newMetadata!!.displayName,
                    displayIcon = encodedCroppedImage,
                    privacyPolicyUrl = newMetadata!!.privacyPolicyUrl,
                    testOnly = newMetadata!!.testOnly
                )
            }
        }
    )
    var nameText by remember { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            val e = userTrustManager.getEntries()[entryIndex]
            nameText = TextFieldValue(e.metadata.displayName ?: "")
            newMetadata = e.metadata
            entry.value = e
        }
    }

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
                            onBackPressed()
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
                    text = when (entry.value) {
                        is TrustEntryVical -> "Edit VICAL"
                        is TrustEntryX509Cert -> "Edit IACA certificate"
                        null -> "Edit entry"
                    }
                ),
                onBackPressed = {
                    if (newMetadata != entry.value!!.metadata) {
                        showConfirmationBeforeExiting = true
                    } else {
                        onBackPressed()
                    }
                },
                actions = {
                    val contentChanged = (newMetadata != entry.value?.metadata)
                    Button(
                        enabled = contentChanged,
                        onClick = {
                            coroutineScope.launch {
                                userTrustManager.updateMetadata(entry.value!!, newMetadata!!)
                                onBackPressed()
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
            entry.value?.let { trustEntry ->

                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    newMetadata?.displayIcon.let {
                        Box(
                            modifier = Modifier.size(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (it == null) {
                                trustEntry.RenderIconWithFallback(size = 160.dp, forceFallback = true)
                            } else {
                                Image(
                                    bitmap = decodeImage(it.toByteArray()),
                                    contentDescription = null
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row {
                            TextButton(
                                onClick = { imagePicker.launch() }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Change")
                            }
                            TextButton(
                                onClick = {
                                    newMetadata = TrustMetadata(
                                        displayName = newMetadata!!.displayName,
                                        displayIcon = null,
                                        privacyPolicyUrl = newMetadata!!.privacyPolicyUrl,
                                        testOnly = newMetadata!!.testOnly
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Remove")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        OutlinedTextField(
                            value = nameText,
                            label = { Text(text = "Name") },
                            onValueChange = {
                                nameText = it
                                newMetadata = TrustMetadata(
                                    displayName = if (it.text.isNotEmpty()) it.text else null,
                                    displayIcon = newMetadata!!.displayIcon,
                                    privacyPolicyUrl = newMetadata!!.privacyPolicyUrl,
                                    testOnly = newMetadata!!.testOnly
                                )
                            },
                            singleLine = true,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(
                                8.dp,
                                alignment = Alignment.Start
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = newMetadata?.testOnly ?: false,
                                onCheckedChange = {
                                    newMetadata = TrustMetadata(
                                        displayName = newMetadata!!.displayName,
                                        displayIcon = newMetadata!!.displayIcon,
                                        privacyPolicyUrl = newMetadata!!.privacyPolicyUrl,
                                        testOnly = it
                                    )
                                }
                            )
                            Text(
                                text = when (trustEntry) {
                                    is TrustEntryVical -> "This VICAL is for testing only"
                                    is TrustEntryX509Cert -> "This certificate is for testing only"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}