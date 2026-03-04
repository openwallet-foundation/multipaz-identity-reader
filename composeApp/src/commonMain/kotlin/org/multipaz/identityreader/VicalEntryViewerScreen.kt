package org.multipaz.identityreader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.multipaz.compose.certificateviewer.X509CertViewer
import org.multipaz.compose.trustmanagement.TrustEntryVicalEntryViewer
import org.multipaz.compose.trustmanagement.TrustManagerModel
import org.multipaz.mdoc.vical.SignedVical
import org.multipaz.mdoc.vical.VicalCertificateInfo
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustManager

@Composable
fun VicalEntryViewerScreen(
    trustManagerModel: TrustManagerModel,
    vicalTrustEntryId: String,
    certNum: Int,
    onBackPressed: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            AppBar(
                title = AnnotatedString("VICAL entry"),
                onBackPressed = onBackPressed,
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
            ) {
                TrustEntryVicalEntryViewer(
                    trustManagerModel = trustManagerModel,
                    vicalTrustEntryId = vicalTrustEntryId,
                    certNum = certNum
                )
            }
        }
    }
}