package org.multipaz.identityreader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import multipazidentityreader.composeapp.generated.resources.Res

@Composable
fun TransferScreen(
    readerModel: ReaderModel,
    onBackPressed: () -> Unit,
    onTransferComplete: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val waitingComposition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/waiting_animation.json").decodeToString()
        )
    }
    val waitingProgressState = animateLottieCompositionAsState(
        composition = waitingComposition,
        iterations = Compottie.IterateForever
    )

    when (val state = readerModel.state.collectAsState().value) {
        ReaderModel.State.IDLE,
        ReaderModel.State.WAITING_FOR_DEVICE_REQUEST -> {
            throw IllegalStateException("Unexpected state $state")
        }
        ReaderModel.State.WAITING_FOR_START -> {
            readerModel.start(coroutineScope)
        }
        ReaderModel.State.CONNECTING -> {}
        ReaderModel.State.COMPLETED -> {
            onTransferComplete()
        }
    }

    Scaffold(
        topBar = {
            AppBar(
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
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = rememberLottiePainter(
                        composition = waitingComposition,
                        progress = { waitingProgressState.value },
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(300.dp)
                )

                Text(
                    text = "Waiting for Presenter",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
