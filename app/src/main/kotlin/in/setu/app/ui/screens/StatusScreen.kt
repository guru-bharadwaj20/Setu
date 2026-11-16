package `in`.setu.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import `in`.setu.app.R
import `in`.setu.app.ui.StatusUiState
import java.text.DateFormat
import java.util.Date

/** Counters for curious users and for field testing. Nothing here is needed to send an SOS. */
@Composable
fun StatusScreen(
    state: StatusUiState,
    onRelayUrl: (String) -> Unit,
    onUploadNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf(state.relayUrl) }
    LaunchedEffect(state.relayUrl) { url = state.relayUrl }
    val m = state.mesh

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.status_title), style = MaterialTheme.typography.headlineMedium)

        StatRow(R.string.status_stored, "${state.stored}")
        StatRow(R.string.status_sos, "${state.sosCarried}")
        StatRow(R.string.status_peers, "${m.peersMet}")
        StatRow(R.string.status_exchanges, "${m.exchanges} (↑${m.packetsSent} ↓${m.packetsReceived})")
        StatRow(R.string.status_radio, if (m.radioOn) "on" else if (m.serviceRunning) "sleeping" else "off")
        StatRow(R.string.status_duty, stringResource(R.string.status_duty, m.duty.discoverySeconds, m.duty.sleepSeconds))
        StatRow(R.string.status_uploaded, "${m.uploaded}")
        StatRow(
            R.string.status_last_upload,
            if (state.lastUploadAt == 0L) stringResource(R.string.status_never)
            else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.lastUploadAt * 1000)),
        )
        m.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()

        Text(stringResource(R.string.status_identity), style = MaterialTheme.typography.titleMedium)
        Text(state.shortCode, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall)

        HorizontalDivider()

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.status_relay_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onRelayUrl(url.trim()) }, enabled = url.trim() != state.relayUrl) { Text("Save") }
            OutlinedButton(onClick = onUploadNow, enabled = m.online) { Text("Upload now") }
        }
    }
}

@Composable
private fun StatRow(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
    }
}
