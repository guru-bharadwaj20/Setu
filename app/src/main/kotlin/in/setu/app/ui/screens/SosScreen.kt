package `in`.setu.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.setu.app.R
import `in`.setu.app.ui.SosUiState
import `in`.setu.app.ui.theme.SafeGreen
import `in`.setu.app.ui.theme.SosRed
import `in`.setu.protocol.Needs
import `in`.setu.protocol.Protocol
import java.text.DateFormat
import java.util.Date

/** The one screen that matters: compose and send an SOS, or watch its state. */
@Composable
fun SosScreen(
    state: SosUiState,
    onSend: (headcount: Int, needs: Int, note: String) -> Unit,
    onSafe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.active) ActiveSos(state, onSafe) else ComposeSos(state, onSend)
        if (state.shortCode.isNotEmpty()) {
            Text(
                stringResource(R.string.sos_your_code, state.shortCode),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ComposeSos(state: SosUiState, onSend: (Int, Int, String) -> Unit) {
    var headcount by rememberSaveable { mutableIntStateOf(1) }
    var needs by rememberSaveable { mutableIntStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }

    Text(stringResource(R.string.sos_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.sos_hint), style = MaterialTheme.typography.bodyMedium)

    Text(stringResource(R.string.sos_headcount), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = { if (headcount > 1) headcount-- }) { Text("−", fontSize = 22.sp) }
        Text("$headcount", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = { if (headcount < Protocol.MAX_HEADCOUNT) headcount++ }) { Text("+", fontSize = 22.sp) }
    }

    NeedsChips(needs) { needs = it }

    OutlinedTextField(
        value = note,
        onValueChange = { if (it.toByteArray().size <= Protocol.MAX_NOTE_BYTES) note = it },
        label = { Text(stringResource(R.string.sos_note)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    Button(
        onClick = { onSend(headcount, needs, note) },
        enabled = !state.sending,
        colors = ButtonDefaults.buttonColors(containerColor = SosRed),
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        if (state.sending) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.sos_sending), fontSize = 20.sp)
        } else {
            Text(stringResource(R.string.sos_send), fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun NeedsChips(selected: Int, onChange: (Int) -> Unit) {
    val options = listOf(
        Needs.WATER to R.string.need_water,
        Needs.FOOD to R.string.need_food,
        Needs.MEDICAL to R.string.need_medical,
        Needs.TRAPPED to R.string.need_trapped,
        Needs.EVACUATE to R.string.need_evacuate,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.take(3).forEach { (flag, label) -> NeedChip(flag, label, selected, onChange) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.drop(3).forEach { (flag, label) -> NeedChip(flag, label, selected, onChange) }
    }
}

@Composable
private fun NeedChip(flag: Int, label: Int, selected: Int, onChange: (Int) -> Unit) {
    val on = selected and flag != 0
    FilterChip(
        selected = on,
        onClick = { onChange(if (on) selected and flag.inv() else selected or flag) },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun ActiveSos(state: SosUiState, onSafe: () -> Unit) {
    val since = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.since * 1000))
    Card(
        colors = CardDefaults.cardColors(containerColor = if (state.acked) SafeGreen else SosRed),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sos_active, since),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Text(
                stringResource(if (state.acked) R.string.sos_acked else R.string.sos_waiting),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = onSafe, modifier = Modifier.fillMaxWidth().height(64.dp)) {
        Text(stringResource(R.string.sos_cancel), fontSize = 18.sp)
    }
}
