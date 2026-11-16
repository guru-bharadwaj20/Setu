package `in`.setu.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.app.R
import `in`.setu.app.mesh.MeshState

/** One switch. Courier mode is on by default; this is where people turn it off. */
@Composable
fun CourierScreen(
    enabled: Boolean,
    mesh: MeshState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.courier_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.courier_body), style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (enabled && mesh.serviceRunning) R.string.courier_on else R.string.courier_off),
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Text(
            stringResource(R.string.courier_battery_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) {
            Text(
                stringResource(R.string.status_duty, mesh.duty.discoverySeconds, mesh.duty.sleepSeconds),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
