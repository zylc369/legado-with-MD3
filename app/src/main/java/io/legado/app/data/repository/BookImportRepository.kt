package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.ImportFileOutcome
import io.legado.app.domain.gateway.LocalBookImportGateway
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.canSafelyRebindTo
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.FileDoc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class BookImportRepository(
    private val appDb: AppDatabase,
) : LocalBookImportGateway {

    override fun flowLocalBookUrls(): Flow<Set<String>> {
        return appDb.bookDao.flowLocal()
            .map { books -> books.mapTo(hashSetOf()) { it.bookUrl } }
            .flowOn(Dispatchers.IO)
    }

    suspend fun findByFileName(fileName: String): Book? = withContext(Dispatchers.IO) {
        appDb.bookDao.getBookByFileName(fileName)
    }

    suspend fun findAndRebind(fileName: String, filePath: String): Book? =
        withContext(Dispatchers.IO) {
            val book = appDb.bookDao.getBookByFileName(fileName) ?: return@withContext null
            if (book.bookUrl == filePath || !book.canSafelyRebindTo(filePath)) {
                return@withContext book
            }

            val reboundBook = book.copy(bookUrl = filePath)
            appDb.runInTransaction {
                appDb.bookDao.replace(book, reboundBook)
                BookHelp.updateCacheFolder(book, reboundBook)
            }
            reboundBook
        }

    override fun importUrisFlow(uris: List<Uri>): Flow<ImportFileOutcome> = flow {
        uris.forEach { uri ->
            val error = try {
                LocalBook.importFiles(uri, LocalBook.privateImportDir)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.localizedMessage ?: e.javaClass.simpleName
            }
            emit(
                ImportFileOutcome(
                    id = uri.toString(),
                    name = resolveName(uri),
                    error = error,
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun importMangaDirectory(treeUri: Uri) {
        withContext(Dispatchers.IO) {
            LocalBook.importMangaDirectory(FileDoc.fromUri(treeUri, true))
        }
    }

    private fun resolveName(uri: Uri): String {
        return runCatching { FileDoc.fromUri(uri, false).name }.getOrDefault("")
    }
}
