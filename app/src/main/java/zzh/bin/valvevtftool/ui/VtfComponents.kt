package zzh.bin.valvevtftool.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile

@Composable
fun DirectoryPicker(
    label: String,
    selectedDir: DocumentFile?,
    onSelect: (() -> Unit)? = null
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = selectedDir?.name ?: "None selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectedDir == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
            if (onSelect != null) {
                IconButton(onClick = onSelect) {
                    Icon(imageVector = Icons.Default.Folder, contentDescription = "Select Directory")
                }
            }
        }
    }
}

@Composable
fun FileListItem(
    file: DocumentFile,
    isSelected: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onConvert: () -> Unit,
    onPreview: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isSelected, onCheckedChange = onCheckedChange)
            
            val icon = if (file.name?.endsWith(".png", ignoreCase = true) == true) Icons.Default.Image else Icons.Default.Description
            Icon(imageVector = icon, contentDescription = null)
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Text(
                text = file.name ?: "Unknown",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            
            IconButton(onClick = onConvert) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Convert")
            }
        }
    }
}
