package `in`.setu.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.setu.app.R

/** Shown until the mesh permissions are granted. Keeps the explanation short. */
@Composable
fun PermissionsScreen(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.perm_body), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.perm_grant))
        }
    }
}
