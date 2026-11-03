package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.data.api.GitHubApiClient
import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class ZipEntryInfo(
    val path: String,
    val displayPath: String,
    val size: Long,
    val isDirectory: Boolean
)

data class ZipInspectionResult(
    val totalFiles: Int,
    val totalBytes: Long,
    val entries: List<ZipEntryInfo>,
    val commonRootFolder: String?
)

sealed class ZipUploadProgress {
    data class Status(val message: String) : ZipUploadProgress()
    data class FileProgress(val current: Int, val total: Int, val fileName: String) : ZipUploadProgress()
    data class Success(val repo: GitHubRepo) : ZipUploadProgress()
    data class Error(val error: String) : ZipUploadProgress()
}

class ZipManager(
    private val context: Context,
    private val apiClient: GitHubApiClient
) {
    private val cacheZipFile: File
        get() = File(context.cacheDir, "imported_upload.zip")

    suspend fun inspectAndCacheZip(uri: Uri): Result<ZipInspectionResult> = withContext(Dispatchers.IO) {
        try {
            // Copy URI stream to local cache file for resilient multiple access
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheZipFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Não foi possível acessar o arquivo ZIP selecionado."))

            val entries = mutableListOf<ZipEntryInfo>()
            var totalBytes = 0L
            var totalFiles = 0

            val rawPaths = mutableListOf<String>()

            ZipFile(cacheZipFile).use { zip ->
                val zipEntries = zip.entries()
                while (zipEntries.hasMoreElements()) {
                    val entry = zipEntries.nextElement()
                    val name = entry.name.replace('\\', '/')
                    // Filter out internal metadata files
                    if (!name.startsWith("__MACOSX") && !name.contains(".DS_Store") && !name.startsWith(".git/")) {
                        val isDir = entry.isDirectory
                        val size = if (entry.size >= 0) entry.size else 0L
                        if (!isDir) {
                            rawPaths.add(name)
                            totalFiles++
                            totalBytes += size
                        }
                    }
                }
            }

            if (totalFiles == 0) {
                return@withContext Result.failure(Exception("O arquivo ZIP está vazio ou não contém arquivos legíveis."))
            }

            // Determine if there is a single common root folder
            val commonRoot = findCommonRootFolder(rawPaths)

            ZipFile(cacheZipFile).use { zip ->
                val zipEntries = zip.entries()
                while (zipEntries.hasMoreElements()) {
                    val entry = zipEntries.nextElement()
                    val name = entry.name.replace('\\', '/')
                    if (!name.startsWith("__MACOSX") && !name.contains(".DS_Store") && !name.startsWith(".git/")) {
                        val isDir = entry.isDirectory
                        val size = if (entry.size >= 0) entry.size else 0L
                        val display = if (commonRoot != null && name.startsWith(commonRoot)) {
                            name.removePrefix(commonRoot).trimStart('/')
                        } else {
                            name
                        }
                        if (display.isNotBlank()) {
                            entries.add(ZipEntryInfo(path = name, displayPath = display, size = size, isDirectory = isDir))
                        }
                    }
                }
            }

            Result.success(ZipInspectionResult(totalFiles, totalBytes, entries, commonRoot))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findCommonRootFolder(paths: List<String>): String? {
        if (paths.isEmpty()) return null
        val firstSlash = paths.first().indexOf('/')
        if (firstSlash == -1) return null
        val candidate = paths.first().substring(0, firstSlash + 1)
        val allShare = paths.all { it.startsWith(candidate) }
        return if (allShare) candidate else null
    }

    fun uploadZipToNewRepo(
        repoName: String,
        repoDescription: String?,
        isPrivate: Boolean,
        stripRootFolder: Boolean = true
    ): Flow<ZipUploadProgress> = flow {
        if (!cacheZipFile.exists()) {
            emit(ZipUploadProgress.Error("Arquivo ZIP não encontrado no cache. Por favor, selecione-o novamente."))
            return@flow
        }

        emit(ZipUploadProgress.Status("Criando novo repositório @$repoName no GitHub..."))

        // 1. Create repository
        val createRepoReq = CreateRepoRequest(
            name = repoName,
            description = repoDescription,
            `private` = isPrivate,
            autoInit = true
        )
        val createResp = apiClient.service.createRepo(createRepoReq)
        if (!createResp.isSuccessful || createResp.body() == null) {
            val errorMsg = when (createResp.code()) {
                422 -> "Já existe um repositório com o nome '$repoName' na sua conta."
                401 -> "Token não autorizado para criar repositórios."
                else -> "Falha ao criar repositório: HTTP ${createResp.code()}"
            }
            emit(ZipUploadProgress.Error(errorMsg))
            return@flow
        }

        val repo = createResp.body()!!
        val owner = repo.owner?.login ?: ""
        val branch = repo.defaultBranch.ifBlank { "main" }

        emit(ZipUploadProgress.Status("Obtendo referência inicial da branch $branch..."))

        // 2. Fetch head ref sha
        var headSha: String? = null
        for (attempt in 1..6) {
            try {
                delay(1000)
                val refResp = apiClient.service.getGitRef(owner, repoName, "heads/$branch")
                if (refResp.isSuccessful && refResp.body()?.obj?.sha != null) {
                    headSha = refResp.body()!!.obj!!.sha
                    break
                }
            } catch (_: Exception) {}
        }

        // 3. Extract and read all valid files from ZIP
        emit(ZipUploadProgress.Status("Lendo e preparando arquivos do ZIP..."))

        val fileEntries = mutableListOf<Pair<String, ByteArray>>()
        val rawPaths = mutableListOf<String>()

        ZipFile(cacheZipFile).use { zip ->
            val zipEntries = zip.entries()
            while (zipEntries.hasMoreElements()) {
                val entry = zipEntries.nextElement()
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory && !name.startsWith("__MACOSX") && !name.contains(".DS_Store") && !name.startsWith(".git/")) {
                    rawPaths.add(name)
                }
            }
        }

        val commonRoot = if (stripRootFolder) findCommonRootFolder(rawPaths) else null

        ZipFile(cacheZipFile).use { zip ->
            val zipEntries = zip.entries()
            while (zipEntries.hasMoreElements()) {
                val entry = zipEntries.nextElement()
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory && !name.startsWith("__MACOSX") && !name.contains(".DS_Store") && !name.startsWith(".git/")) {
                    val finalPath = if (commonRoot != null && name.startsWith(commonRoot)) {
                        name.removePrefix(commonRoot).trimStart('/')
                    } else {
                        name.trimStart('/')
                    }

                    if (finalPath.isNotBlank()) {
                        val stream = zip.getInputStream(entry)
                        val baos = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            baos.write(buffer, 0, read)
                        }
                        fileEntries.add(finalPath to baos.toByteArray())
                    }
                }
            }
        }

        if (fileEntries.isEmpty()) {
            emit(ZipUploadProgress.Error("Nenhum arquivo encontrado para envio dentro do ZIP."))
            return@flow
        }

        val total = fileEntries.size
        val treeItems = mutableListOf<GitTreeItem>()
        var processedCount = 0

        // 4. Create Git Blobs for each file
        for ((path, bytes) in fileEntries) {
            processedCount++
            emit(ZipUploadProgress.FileProgress(processedCount, total, path))

            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val blobResp = apiClient.service.createGitBlob(owner, repoName, GitBlobRequest(content = base64))
            if (blobResp.isSuccessful && blobResp.body() != null) {
                val sha = blobResp.body()!!.sha
                val mode = if (path.endsWith(".sh")) "100755" else "100644"
                treeItems.add(
                    GitTreeItem(
                        path = path,
                        mode = mode,
                        type = "blob",
                        sha = sha
                    )
                )
            } else {
                emit(ZipUploadProgress.Status("Aviso: Falha ao enviar $path (HTTP ${blobResp.code()})"))
            }
        }

        if (treeItems.isEmpty()) {
            emit(ZipUploadProgress.Error("Nenhum arquivo pôde ser enviado para o GitHub."))
            return@flow
        }

        // 5. Create Git Tree
        emit(ZipUploadProgress.Status("Montando árvore de diretórios (Git Tree) com $total arquivos..."))
        val treeReq = GitTreeRequest(baseTree = headSha, tree = treeItems)
        val treeResp = apiClient.service.createGitTree(owner, repoName, treeReq)
        if (!treeResp.isSuccessful || treeResp.body() == null) {
            emit(ZipUploadProgress.Error("Falha ao criar Git Tree: HTTP ${treeResp.code()}"))
            return@flow
        }
        val newTreeSha = treeResp.body()!!.sha

        // 6. Create Git Commit
        emit(ZipUploadProgress.Status("Finalizando commit com todos os arquivos do ZIP..."))
        val parents = if (headSha != null) listOf(headSha) else emptyList()
        val commitReq = GitCommitRequest(
            message = "Importação de projeto via ZIP (GitHub Manager)",
            tree = newTreeSha,
            parents = parents
        )
        val commitResp = apiClient.service.createGitCommit(owner, repoName, commitReq)
        if (!commitResp.isSuccessful || commitResp.body() == null) {
            emit(ZipUploadProgress.Error("Falha ao criar commit: HTTP ${commitResp.code()}"))
            return@flow
        }
        val newCommitSha = commitResp.body()!!.sha

        // 7. Update branch ref
        emit(ZipUploadProgress.Status("Publicando branch principal ($branch)..."))
        val refReq = GitRefRequest(sha = newCommitSha, force = true)
        val updateRefResp = apiClient.service.updateGitRef(owner, repoName, "heads/$branch", refReq)
        if (!updateRefResp.isSuccessful) {
            emit(ZipUploadProgress.Error("Falha ao atualizar branch $branch: HTTP ${updateRefResp.code()}"))
            return@flow
        }

        emit(ZipUploadProgress.Success(repo))
    }

    suspend fun exportRepoZip(
        owner: String,
        repo: String,
        ref: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val response = apiClient.service.downloadRepoZip(owner, repo, ref)
            if (!response.isSuccessful || response.body() == null) {
                return@withContext Result.failure(Exception("Falha ao baixar ZIP: HTTP ${response.code()}"))
            }

            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val outputFile = File(exportDir, "${repo}-${ref}.zip")

            val body = response.body()!!
            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
