package com.example.ui.repos

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GitHubRepo
import com.example.data.repository.ZipInspectionResult
import com.example.data.repository.ZipManager
import com.example.data.repository.ZipUploadProgress
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZipImportScreen(
    zipManager: ZipManager,
    onNavigateBack: () -> Unit,
    onRepoCreated: (GitHubRepo) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedZipName by remember { mutableStateOf<String?>(null) }
    var inspectionResult by remember { mutableStateOf<ZipInspectionResult?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analyzeError by remember { mutableStateOf<String?>(null) }
    var showFileList by remember { mutableStateOf(false) }
    var stripRootFolder by remember { mutableStateOf(true) }

    var repoName by remember { mutableStateOf("") }
    var repoDescription by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    var isUploading by remember { mutableStateOf(false) }
    var currentStatusMsg by remember { mutableStateOf("") }
    var fileProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var currentFileName by remember { mutableStateOf("") }
    var uploadSuccessRepo by remember { mutableStateOf<GitHubRepo?>(null) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (!isUploading) onNavigateBack()
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            isAnalyzing = true
            analyzeError = null
            inspectionResult = null
            showFileList = false

            // Suggest repo name from zip file name
            val fileName = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip") ?: ""
            selectedZipName = uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo.zip"
            if (fileName.isNotBlank()) {
                val clean = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "-").trim('-')
                if (repoName.isBlank() || repoName == "meu-repositorio") {
                    repoName = clean
                }
            }

            coroutineScope.launch {
                val res = zipManager.inspectAndCacheZip(uri)
                isAnalyzing = false
                if (res.isSuccess) {
                    inspectionResult = res.getOrNull()
                } else {
                    analyzeError = res.exceptionOrNull()?.message ?: "Erro ao analisar arquivo ZIP."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Criar Repo via Upload de ZIP", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (!isUploading) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uploadSuccessRepo != null) {
                // Success View
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "Repositório publicado com sucesso!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Todos os arquivos e pastas de dentro do arquivo ZIP foram criados e comitados no GitHub.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uploadSuccessRepo!!.htmlUrl ?: "https://github.com"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Abrir no GitHub")
                        }
                        Button(
                            onClick = { onRepoCreated(uploadSuccessRepo!!) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Acessar Repositório no App")
                        }
                    }
                }
            } else if (isUploading) {
                // Uploading In-Progress View
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Text(
                                text = "Enviando conteúdo para o GitHub...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (fileProgress != null) {
                            val (curr, total) = fileProgress!!
                            val progress = if (total > 0) curr.toFloat() / total.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                            )
                            Text(
                                text = "Arquivo $curr de $total (${(progress * 100).toInt()}%): $currentFileName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                        }

                        Text(
                            text = currentStatusMsg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                // Step 1: Select ZIP
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("1. Selecione o arquivo .ZIP do seu projeto", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            text = "O aplicativo irá extrair todas as pastas e arquivos contidos no .zip para montar o repositório completo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = { zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                            modifier = Modifier.fillMaxWidth().testTag("select_zip_button")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (selectedUri != null) "Trocar arquivo ZIP" else "Selecionar arquivo .ZIP")
                        }

                        if (selectedZipName != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                Text(selectedZipName!!, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                            }
                        }

                        if (isAnalyzing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Lendo arquivos e estrutura do ZIP...", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        if (analyzeError != null) {
                            Text(
                                text = analyzeError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (inspectionResult != null) {
                            val r = inspectionResult!!
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Conteúdo identificado dentro do ZIP:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Text("• Total de arquivos: ${r.totalFiles}", style = MaterialTheme.typography.bodySmall)
                                    Text("• Tamanho total descompactado: ${formatFileSize(r.totalBytes)}", style = MaterialTheme.typography.bodySmall)

                                    if (r.commonRootFolder != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Extrair na raiz do repo", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                                Text("Remove a pasta '${r.commonRootFolder}' para os arquivos ficarem na raiz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Switch(checked = stripRootFolder, onCheckedChange = { stripRootFolder = it })
                                        }
                                    }

                                    TextButton(
                                        onClick = { showFileList = !showFileList },
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(if (showFileList) "Ocultar lista de arquivos" else "Ver lista de arquivos extraídos (${r.entries.count { !it.isDirectory }})")
                                    }

                                    AnimatedVisibility(visible = showFileList) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 200.dp)
                                                .verticalScroll(rememberScrollState())
                                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp))
                                                .padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            r.entries.filter { !it.isDirectory }.forEach { entry ->
                                                Text(
                                                    text = if (stripRootFolder && r.commonRootFolder != null) entry.displayPath else entry.path,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Step 2: Configure Repository
                if (inspectionResult != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("2. Configurar e Publicar no GitHub", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }

                            OutlinedTextField(
                                value = repoName,
                                onValueChange = { repoName = it },
                                label = { Text("Nome do novo repositório *") },
                                placeholder = { Text("meu-projeto") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("repo_name_input")
                            )

                            OutlinedTextField(
                                value = repoDescription,
                                onValueChange = { repoDescription = it },
                                label = { Text("Descrição (opcional)") },
                                placeholder = { Text("Repositório criado a partir de upload de arquivo ZIP") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Repositório Privado", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("Visível somente para você e colaboradores", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                            }

                            if (uploadError != null) {
                                Text(text = uploadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }

                            Button(
                                onClick = {
                                    val cleanName = repoName.trim()
                                    if (cleanName.isNotBlank() && selectedUri != null) {
                                        isUploading = true
                                        uploadError = null
                                        coroutineScope.launch {
                                            zipManager.uploadZipToNewRepo(
                                                repoName = cleanName,
                                                repoDescription = repoDescription.ifBlank { null },
                                                isPrivate = isPrivate,
                                                stripRootFolder = stripRootFolder
                                            ).collect { progress ->
                                                when (progress) {
                                                    is ZipUploadProgress.Status -> currentStatusMsg = progress.message
                                                    is ZipUploadProgress.FileProgress -> {
                                                        fileProgress = progress.current to progress.total
                                                        currentFileName = progress.fileName
                                                    }
                                                    is ZipUploadProgress.Success -> {
                                                        isUploading = false
                                                        uploadSuccessRepo = progress.repo
                                                    }
                                                    is ZipUploadProgress.Error -> {
                                                        isUploading = false
                                                        uploadError = progress.error
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = repoName.isNotBlank() && !isUploading,
                                modifier = Modifier.fillMaxWidth().testTag("start_zip_import_button")
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Criar e Publicar com Todo o Código")
                            }
                        }
                    }
                }
            }
        }
    }
}
