package zzh.bin.valvevtftool
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zzh.bin.valvevtftool.ui.*
import zzh.bin.valvevtftool.ui.theme.MyComposeApplicationTheme
import java.io.File

import android.Manifest
import android.os.Build
import android.widget.Toast

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Dynamic permission request
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val requestPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (!isGranted) {
                    Toast.makeText(this, "Permission required for file access", Toast.LENGTH_SHORT).show()
                }
            }
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        setContent {
            MyComposeApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VtfToolScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VtfToolScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var inputDir by remember { mutableStateOf<DocumentFile?>(null) }
    var outputDir by remember { mutableStateOf<DocumentFile?>(null) }
    val files = remember { mutableStateListOf<DocumentFile>() }
    
    // Automatically update the files list when inputDir changes
    LaunchedEffect(inputDir) {
        files.clear()
        inputDir?.listFiles()?.forEach { file ->
            if (file.isFile && (file.name?.endsWith(".vtf", ignoreCase = true) == true || file.name?.endsWith(".png", ignoreCase = true) == true))
                files.add(file)
        }
    }
    
    val selectedFiles = remember { mutableStateListOf<DocumentFile>() }
    var status by remember { mutableStateOf("Ready") }
    var isConverting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("vtftool_prefs", android.content.Context.MODE_PRIVATE)
        
        // Helper to load and validate URI
        fun loadDir(key: String): DocumentFile? {
            val uriString = prefs.getString(key, null) ?: return null
            val uri = android.net.Uri.parse(uriString)
            
            // Verify we still have permission
            val hasPermission = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
            return if (hasPermission) DocumentFile.fromTreeUri(context, uri) else null
        }

        inputDir = loadDir("input_uri")
        outputDir = loadDir("output_uri")
    }

    val inputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            
            context.getSharedPreferences("vtftool_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("input_uri", it.toString()).apply()

            inputDir = DocumentFile.fromTreeUri(context, it)
        }
    }

    val singleFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val file = DocumentFile.fromSingleUri(context, it)
            file?.let {
                files.clear()
                files.add(it)
                inputDir = null // Clear directory selection
                // Clear input_uri in prefs
                context.getSharedPreferences("vtftool_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().remove("input_uri").apply()
            }
        }
    }

    val outputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            
            context.getSharedPreferences("vtftool_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putString("output_uri", it.toString()).apply()
            
            outputDir = DocumentFile.fromTreeUri(context, it)
        }
    }

    suspend fun convertFile(file: DocumentFile, outputDir: DocumentFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, file.name ?: "temp")
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            val outName = if (file.name?.endsWith(".png") == true) file.name!!.replace(".png", ".vtf") else file.name!!.replace(".vtf", ".png")
            val outFile = File(context.cacheDir, outName)

            val success = if (file.name?.endsWith(".png") == true) {
                VtfLib.pngToVtf(tempFile.absolutePath, outFile.absolutePath)
            } else {
                VtfLib.vtfToPng(tempFile.absolutePath, outFile.absolutePath)
            }

            if (success && outFile.exists()) {
                outputDir.createFile("*/*", outName)?.uri?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        outFile.inputStream().use { input -> input.copyTo(output) }
                    }
                }
                return@withContext true
            } else {
                return@withContext false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    if (previewBitmap != null) {
        Dialog(onDismissRequest = { previewBitmap = null }) {
            Surface {
                Image(bitmap = previewBitmap!!.asImageBitmap(), contentDescription = "VTF Preview")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("VTF Tool") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { inputLauncher.launch(null) }, modifier = Modifier.weight(1f)) { Text("Select Directory") }
                OutlinedButton(onClick = { singleFileLauncher.launch(arrayOf("image/*", "*/*")) }, modifier = Modifier.weight(1f)) { Text("Select File") }
            }
            DirectoryPicker("Input Selection", inputDir) {} // Using it as display only now
            DirectoryPicker("Output Directory", outputDir) { outputLauncher.launch(null) }
            
            Text("Status: $status", style = MaterialTheme.typography.bodyMedium)
            if (isConverting) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files) { file ->
                    FileListItem(
                        file = file,
                        isSelected = selectedFiles.contains(file),
                        onCheckedChange = { isSelected ->
                            if (isSelected) selectedFiles.add(file) else selectedFiles.remove(file)
                        },
                        onConvert = {
                            if (outputDir == null) {
                                scope.launch { snackbarHostState.showSnackbar("Please select an output directory first") }
                                return@FileListItem
                            }
                            scope.launch {
                                isConverting = true
                                status = "Converting ${file.name}..."
                                val success = convertFile(file, outputDir!!)
                                status = if (success) "Converted ${file.name}" else "Failed ${file.name}"
                                snackbarHostState.showSnackbar(status)
                                isConverting = false
                            }
                        },
                        onPreview = {
                            val name = file.name?.lowercase() ?: ""
                            scope.launch {
                                if (name.endsWith(".vtf")) {
                                    val tempFile = File(context.cacheDir, file.name ?: "temp.vtf")
                                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                                        tempFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                    previewBitmap = VtfLib.getVtfBitmap(tempFile.absolutePath)
                                } else if (name.endsWith(".png")) {
                                    val inputStream = context.contentResolver.openInputStream(file.uri)
                                    previewBitmap = BitmapFactory.decodeStream(inputStream)
                                }
                            }
                        }
                    )
                }
            }

            Button(
                onClick = {
                    if (outputDir == null) {
                        scope.launch { snackbarHostState.showSnackbar("Please select an output directory") }
                        return@Button
                    }
                    scope.launch {
                        isConverting = true
                        status = "Converting..."
                        var successCount = 0
                        selectedFiles.forEachIndexed { index, file ->
                            progress = index.toFloat() / selectedFiles.size
                            if (convertFile(file, outputDir!!)) successCount++
                        }
                        status = "Converted $successCount/${selectedFiles.size} files"
                        snackbarHostState.showSnackbar(status)
                        isConverting = false
                        progress = 0f
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedFiles.isNotEmpty() && !isConverting
            ) {
                Text("Convert Selected")
            }
        }
    }
}
