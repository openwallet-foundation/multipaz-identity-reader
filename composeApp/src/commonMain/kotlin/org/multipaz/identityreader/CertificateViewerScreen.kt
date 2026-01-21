package org.multipaz.identityreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.show_certificate_screen_title
import multipazidentityreader.composeapp.generated.resources.show_certificate_screen_title_for_chain
import org.jetbrains.compose.resources.stringResource
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborArray
import org.multipaz.compose.certificateviewer.X509CertViewer
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.util.fromBase64Url

@Composable
fun CertificateViewerScreen(
    certificateDataBase64: String,
    onBackPressed: () -> Unit
) {
    val certificate = remember { mutableStateOf<X509Cert?>(null) }
    val certificateChain = remember { mutableStateOf<X509CertChain?>(null) }

    LaunchedEffect(Unit) {
        when (val dataItem = Cbor.decode(certificateDataBase64.fromBase64Url())) {
            is CborArray -> {
                certificateChain.value = X509CertChain.fromDataItem(dataItem)
            }
            else -> {
                certificate.value = X509Cert.fromDataItem(dataItem)
            }
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = AnnotatedString(stringResource(
                    if (certificate.value != null) {
                        Res.string.show_certificate_screen_title
                    } else {
                        Res.string.show_certificate_screen_title_for_chain
                    }
                )),
                onBackPressed = onBackPressed,
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            certificate.value?.let {
                CertificateViewer(certificates = listOf(it))
            }
            certificateChain.value?.let {
                CertificateViewer(certificates = it.certificates)
            }
        }
    }
}

private val PAGER_INDICATOR_HEIGHT = 30.dp
private val PAGER_INDICATOR_PADDING = 8.dp

@Composable
private fun CertificateViewer(
    modifier: Modifier = Modifier,
    certificates: List<X509Cert>
) {
    check(certificates.isNotEmpty())
    Box(
        modifier = modifier.fillMaxHeight().padding(start = 16.dp)
    ) {
        val listSize = certificates.size
        val pagerState = rememberPagerState(pageCount = { listSize })

        Column(
            modifier = Modifier.padding(
                bottom = if (listSize > 1) PAGER_INDICATOR_HEIGHT + PAGER_INDICATOR_PADDING else 0.dp
            )
        ) {
            HorizontalPager(
                state = pagerState,
            ) { page ->
                val scrollState = rememberScrollState()
                X509CertViewer(
                    modifier = Modifier.verticalScroll(scrollState),
                    certificate = certificates[page]
                )
            }
        }

        if (listSize > 1) { // Don't show pager for single cert on the list.
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .wrapContentHeight()
                    .fillMaxWidth()
                    .height(PAGER_INDICATOR_HEIGHT)
                    .padding(PAGER_INDICATOR_PADDING),
            ) {
                repeat(pagerState.pageCount) { iteration ->
                    val color =
                        if (pagerState.currentPage == iteration) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = .2f)
                        }
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(color)
                            .size(8.dp)
                    )
                }
            }
        }
    }
}
