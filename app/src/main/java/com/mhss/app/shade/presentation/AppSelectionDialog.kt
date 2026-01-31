package com.mhss.app.shade.presentation

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.mhss.app.shade.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@Composable
fun AppSelectionDialog(
    selectedApps: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var currentSelection by remember(selectedApps) { mutableStateOf(selectedApps) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter {
                    pm.getLaunchIntentForPackage(it.packageName) != null
                }
                .let {
                    if (selectedApps.isEmpty()) it else it.sortedByDescending { it.packageName in selectedApps }
                }
        }
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_apps),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (isLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                    ) {
                        items(apps, key = { it.packageName }) { appInfo ->
                            AppItem(
                                appInfo = appInfo,
                                isSelected = appInfo.packageName in currentSelection,
                                onSelectionChanged = { isSelected ->
                                    currentSelection = if (isSelected) {
                                        currentSelection + appInfo.packageName
                                    } else {
                                        currentSelection - appInfo.packageName
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onConfirm(currentSelection) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppItem(
    appInfo: ApplicationInfo,
    isSelected: Boolean,
    onSelectionChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var appName by remember { mutableStateOf<String?>(null) }
    var appIcon by remember { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(appInfo) {
        withContext(Dispatchers.IO) {
            appName = context.packageManager.getApplicationLabel(appInfo).toString()
            appIcon = context.packageManager.getApplicationIcon(appInfo)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appIcon != null) {
            Image(
                painter = rememberDrawablePainter(drawable = appIcon),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = appName ?: "",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = isSelected,
            onCheckedChange = onSelectionChanged
        )
    }
}
