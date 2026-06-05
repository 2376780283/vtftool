package zzh.bin.valvevtftool

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import zzh.bin.valvevtftool.VtfLib
import zzh.bin.valvevtftool.ui.*
import zzh.bin.valvevtftool.ui.theme.MyComposeApplicationTheme
import java.io.File
import android.Manifest
import android.os.Build
import android.widget.Toast

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
    val selectedFiles = remember { mutableStateListOf<DocumentFile>() }
    
    var status by remember { mutableStateOf("Ready") }
    var isConverting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    val formats = listOf(
        "RGBA8888" to VtfLib.FORMAT_RGBA8888,
        "DXT1" to VtfLib.FORMAT_DXT1,
        "DXT3" to VtfLib.FORMAT_DXT3,
        "DXT5" to VtfLib.FORMAT_DXT5
    )
    var selectedFormat by remember { mutableStateOf(formats[0]) }
    var expanded by remember { mutableStateOf(false) }

    // Load persisted URIs
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("vtftool_prefs", android.content.Context.MODE_PRIVATE)
        fun loadDir(key: String): DocumentFile? {
            val uriString = prefs.getString(key, null) ?: return null
            val uri = android.net.Uri.parse(uriString)
            val hasPermission = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
            return if (hasPermission) DocumentFile.fromTreeUri(context, uri) else null
        }
        inputDir = loadDir("input_uri")
        outputDir = loadDir("output_uri")
    }

    // Refresh file list
    LaunchedEffect(inputDir) {
        files.clear()
        selectedFiles.clear()
        inputDir?.listFiles()?.forEach { file ->
            if (file.isFile && (file.name?.endsWith(".vtf", ignoreCase = true) == true || file.name?.endsWith(".png", ignoreCase = true) == true))
                files.add(file)
        }
    }

    val inputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            context.getSharedPreferences("vtftool_prefs", android.content.Context.MODE_PRIVATE).edit().putString("input_uri", it.toString()).apply()
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
                inputDir = null
                context.getSharedPreferences("vtftool_prefs", android.content.Context.MODE_PRIVATE).edit().remove("input_uri").apply()
            }
        }
    }

    val outputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            context.getSharedPreferences("vtftool_prefs", android.content.Context.MODE_PRIVATE).edit().putString("output_uri", it.toString()).apply()
            outputDir = DocumentFile.fromTreeUri(context, it)
        }
    }

    suspend fun convertFile(file: DocumentFile, outDir: DocumentFile, format: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, file.name ?: "temp")
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            val outName = if (file.name?.endsWith(".png", true) == true) file.name!!.replace(".png", ".vtf") else file.name!!.replace(".vtf", ".png")
            val outFile = File(context.cacheDir, outName)
            val success = if (file.name?.endsWith(".png", true) == true) VtfLib.pngToVtf(tempFile.absolutePath, outFile.absolutePath, format) else VtfLib.vtfToPng(tempFile.absolutePath, outFile.absolutePath)
            if (success && outFile.exists()) {
                outDir.createFile("*/*", outName)?.uri?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { output -> outFile.inputStream().use { input -> input.copyTo(output) } }
                }
                true
            } else false
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    if (previewBitmap != null) {
        Dialog(onDismissRequest = { previewBitmap = null }) {
            Surface(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bitmap = previewBitmap!!.asImageBitmap(), contentDescription = "Preview", modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp))
                    TextButton(onClick = { previewBitmap = null }) { Text("Close") }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VTF Tool") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer, titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                actions = {
                    IconButton(onClick = { singleFileLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Add File") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            AnimatedVisibility(visible = selectedFiles.isNotEmpty() && !isConverting) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (outputDir == null) {
                            scope.launch { snackbarHostState.showSnackbar("Please select an output directory") }
                        } else {
                            scope.launch {
                                isConverting = true
                                var successCount = 0
                                selectedFiles.forEachIndexed { index, file ->
                                    progress = index.toFloat() / selectedFiles.size
                                    if (convertFile(file, outputDir!!, selectedFormat.second)) successCount++
                                }
                                status = "Converted $successCount/${selectedFiles.size} files"
                                snackbarHostState.showSnackbar(status)
                                isConverting = false
                                progress = 0f
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    text = { Text("Convert ${selectedFiles.size} Files") }
                )
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DirectoryPicker("Input Source", inputDir) { inputLauncher.launch(null) }
                    DirectoryPicker("Output Directory", outputDir) { outputLauncher.launch(null) }
                    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { expanded = true }) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Target VTF Format: ", style = MaterialTheme.typography.bodyMedium)
                            Text(selectedFormat.first, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                            Box {
                                Icon(Icons.Default.ArrowDropDown, null)
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    formats.forEach { format ->
                                        DropdownMenuItem(text = { Text(format.first) }, onClick = { selectedFormat = format; expanded = false })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isConverting) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(text = "Processing...", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (files.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files) { file ->
                        FileListItem(
                            file = file,
                            isSelected = selectedFiles.contains(file),
                            onCheckedChange = { isSelected -> if (isSelected) selectedFiles.add(file) else selectedFiles.remove(file) },
                            onConvert = {
                                if (outputDir == null) {
                                    scope.launch { snackbarHostState.showSnackbar("Select output directory first") }
                                } else {
                                    scope.launch {
                                        isConverting = true
                                        status = "Converting..."
                                        val success = convertFile(file, outputDir!!, selectedFormat.second)
                                        snackbarHostState.showSnackbar(if (success) "Converted ${file.name}" else "Failed ${file.name}")
                                        isConverting = false
                                    }
                                }
                            },
                            onPreview = {
                                scope.launch {
                                    val name = file.name?.lowercase() ?: ""
                                    if (name.endsWith(".vtf")) {
                                        val tempFile = File(context.cacheDir, file.name ?: "temp.vtf")
                                        context.contentResolver.openInputStream(file.uri)?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                                        previewBitmap = VtfLib.getVtfBitmap(tempFile.absolutePath)
                                    } else if (name.endsWith(".png")) {
                                        context.contentResolver.openInputStream(file.uri)?.use { previewBitmap = BitmapFactory.decodeStream(it) }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
