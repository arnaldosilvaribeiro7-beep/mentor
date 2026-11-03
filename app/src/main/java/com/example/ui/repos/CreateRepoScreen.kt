package com.example.ui.repos

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import com.example.data.repository.GitHubRepository
import com.example.data.repository.ZipInspectionResult
import com.example.data.repository.ZipManager
import com.example.data.repository.ZipUploadProgress
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class CreateRepoMode(val label: String) {
    FROM_ZIP("A partir de ZIP"),
    BLANK("Em Branco")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRepoScreen(
    repository: GitHubRepository,
    zipManager: ZipManager,
    onNavigateBack: () -> Unit,
    onRepoCreated: (GitHubRepo) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedMode by remember { mutableStateOf(CreateRepoMode.FROM_ZIP) }

    // Common fields
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    // Blank repo specific
    var autoInit by remember { mutableStateOf(true) }
    var isCreatingBlank by remember { mutableStateOf(false) }
    var blankErrorMessage by remember { mutableStateOf<String?>(null) }

    // ZIP specific
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedZipName by remember { mutableStateOf<String?>(null) }
    var inspectionResult by remember { mutableStateOf<ZipInspectionResult?>(null) }
    var isAnalyzingZip by remember { mutableStateOf(false) }
    var zipAnalyzeError by remember { mutableStateOf<String?>(null) }
    var showFileList by remember { mutableStateOf(false) }
    var stripRootFolder by remember { mutableStateOf(true) }

    var isUploadingZip by remember { mutableStateOf(false) }
    var zipStatusMsg by remember { mutableStateOf("") }
    var zipFileProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var currentZipFileName by remember { mutableStateOf("") }
    var zipUploadError by remember { mutableStateOf<String?>(null) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            isAnalyzingZip = true
            zipAnalyzeError = null
            inspectionResult = null
            showFileList = false

            val fileName = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip") ?: ""
            selectedZipName = uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo.zip"
            if (fileName.isNotBlank()) {
                val clean = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "-").trim('-')
                if (name.isBlank() || name == "meu-repositorio") {
                    name = clean
                }
            }

            coroutineScope.launch {
                val res = zipManager.inspectAndCacheZip(uri)
                isAnalyzingZip = false
                if (res.isSuccess) {
                    inspectionResult = res.getOrNull()
                } else {
                    zipAnalyzeError = res.exceptionOrNull()?.message ?: "Erro ao analisar arquivo ZIP."
                }
            }
        }
    }

    BackHandler {
        if (!isCreatingBlank && !isUploadingZip) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Novo Repositório", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !isCreatingBlank && !isUploadingZip) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
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
            // Segmented mode selector
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CreateRepoMode.values().forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = CreateRepoMode.values().size)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (mode == CreateRepoMode.FROM_ZIP) Icons.Default.Archive else Icons.Default.AddBox,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(mode.label)
                        }
                    }
                }
            }

            // Mode 1: FROM ZIP
            if (selectedMode == CreateRepoMode.FROM_ZIP) {
                if (isUploadingZip) {
                    // Uploading In-Progress View
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                Text("Publicando arquivos no GitHub...", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }

                            if (zipFileProgress != null) {
                                val (curr, total) = zipFileProgress!!
                                val progress = if (total > 0) curr.toFloat() / total.toFloat() else 0f
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                                Text(
                                    text = "Arquivo $curr de $total (${(progress * 100).toInt()}%): $currentZipFileName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                            }

                            Text(text = zipStatusMsg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    // Step 1: Pick ZIP file
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Upload do arquivo ZIP", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = "Escolha um arquivo .zip. O aplicativo entrará no arquivo e pegará todas as pastas e arquivos contidos dentro dele.",
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

                            if (isAnalyzingZip) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("Lendo pastas e arquivos dentro do ZIP...", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            if (zipAnalyzeError != null) {
                                Text(text = zipAnalyzeError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }

                            if (inspectionResult != null) {
                                val r = inspectionResult!!
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Conteúdo extraído do ZIP:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Text("• Total de arquivos: ${r.totalFiles}", style = MaterialTheme.typography.bodySmall)
                                        Text("• Tamanho total: ${formatFileSize(r.totalBytes)}", style = MaterialTheme.typography.bodySmall)

                                        if (r.commonRootFolder != null) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("Extrair na raiz", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                                    Text("Remove a pasta '${r.commonRootFolder}'", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Switch(checked = stripRootFolder, onCheckedChange = { stripRootFolder = it })
                                            }
                                        }

                                        TextButton(onClick = { showFileList = !showFileList }, contentPadding = PaddingValues(0.dp)) {
                                            Text(if (showFileList) "Ocultar arquivos" else "Ver lista de arquivos (${r.entries.count { !it.isDirectory }})")
                                        }

                                        AnimatedVisibility(visible = showFileList) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 180.dp)
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

                    // Step 2: Form & Publish
                    if (inspectionResult != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Detalhes do Repositório", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = { Text("Nome do repositório *") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("repo_name_input")
                                )

                                OutlinedTextField(
                                    value = description,
                                    onValueChange = { description = it },
                                    label = { Text("Descrição (opcional)") },
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

                                if (zipUploadError != null) {
                                    Text(text = zipUploadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }

                                Button(
                                    onClick = {
                                        val cleanName = name.trim()
                                        if (cleanName.isNotBlank() && selectedUri != null) {
                                            isUploadingZip = true
                                            zipUploadError = null
                                            coroutineScope.launch {
                                                zipManager.uploadZipToNewRepo(
                                                    repoName = cleanName,
                                                    repoDescription = description.ifBlank { null },
                                                    isPrivate = isPrivate,
                                                    stripRootFolder = stripRootFolder
                                                ).collect { progress ->
                                                    when (progress) {
                                                        is ZipUploadProgress.Status -> zipStatusMsg = progress.message
                                                        is ZipUploadProgress.FileProgress -> {
                                                            zipFileProgress = progress.current to progress.total
                                                            currentZipFileName = progress.fileName
                                                        }
                                                        is ZipUploadProgress.Success -> {
                                                            isUploadingZip = false
                                                            Toast.makeText(context, "Repositório criado com sucesso!", Toast.LENGTH_SHORT).show()
                                                            onRepoCreated(progress.repo)
                                                        }
                                                        is ZipUploadProgress.Error -> {
                                                            isUploadingZip = false
                                                            zipUploadError = progress.error
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = name.isNotBlank() && !isUploadingZip,
                                    modifier = Modifier.fillMaxWidth().testTag("submit_create_repo_button")
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Criar e Publicar com Todo o Código")
                                }
                            }
                        }
                    }
                }
            } else {
                // Mode 2: BLANK REPO
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Detalhes do Repositório em Branco", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                blankErrorMessage = null
                            },
                            label = { Text("Nome do repositório *") },
                            placeholder = { Text("meu-novo-projeto") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("blank_repo_name_input")
                        )

                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Descrição (opcional)") },
                            placeholder = { Text("Breve resumo sobre o que este projeto faz...") },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Repositório Privado", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("Apenas você e colaboradores podem visualizar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Inicializar com README", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("Cria a branch principal imediatamente", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = autoInit, onCheckedChange = { autoInit = it })
                        }

                        if (blankErrorMessage != null) {
                            Text(text = blankErrorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        Button(
                            onClick = {
                                val cleanName = name.trim()
                                if (cleanName.isNotBlank()) {
                                    isCreatingBlank = true
                                    blankErrorMessage = null
                                    coroutineScope.launch {
                                        val res = repository.createRepo(
                                            name = cleanName,
                                            description = description.ifBlank { null },
                                            isPrivate = isPrivate,
                                            autoInit = autoInit
                                        )
                                        isCreatingBlank = false
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Repositório criado!", Toast.LENGTH_SHORT).show()
                                            onRepoCreated(res.getOrNull()!!)
                                        } else {
                                            blankErrorMessage = res.exceptionOrNull()?.message ?: "Falha ao criar repositório"
                                        }
                                    }
                                }
                            },
                            enabled = name.isNotBlank() && !isCreatingBlank,
                            modifier = Modifier.fillMaxWidth().testTag("submit_create_blank_button")
                        ) {
                            if (isCreatingBlank) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Criando no GitHub...")
                            } else {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Criar Repositório")
                            }
                        }
                    }
                }
            }
        }
    }
}
