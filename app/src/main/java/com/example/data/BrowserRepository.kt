package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class BrowserRepository(private val dbProvider: () -> AppDatabase) {
    constructor(db: AppDatabase) : this({ db })

    private val historyDao: HistoryDao
        get() = dbProvider().historyDao()

    private val bookmarkDao: BookmarkDao
        get() = dbProvider().bookmarkDao()

    val allHistory: Flow<List<HistoryItem>> = flow {
        emitAll(historyDao.getAllHistory())
    }.flowOn(Dispatchers.IO)

    val allBookmarks: Flow<List<BookmarkItem>> = flow {
        emitAll(bookmarkDao.getAllBookmarks())
    }.flowOn(Dispatchers.IO)

    suspend fun addHistory(title: String, url: String) = withContext(Dispatchers.IO) {
        // Disregard blank URLs
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return@withContext
        historyDao.insertHistory(HistoryItem(title = title, url = url))
    }

    suspend fun deleteHistory(id: Int) = withContext(Dispatchers.IO) {
        historyDao.deleteHistory(id)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        historyDao.clearAllHistory()
    }

    suspend fun addBookmark(title: String, url: String) = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext
        val displayTitle = if (title.isBlank()) url else title
        bookmarkDao.insertBookmark(BookmarkItem(title = displayTitle, url = url))
    }

    suspend fun deleteBookmark(id: Int) = withContext(Dispatchers.IO) {
        bookmarkDao.deleteBookmark(id)
    }

    suspend fun deleteBookmarkByUrl(url: String) = withContext(Dispatchers.IO) {
        bookmarkDao.deleteBookmarkByUrl(url)
    }

    suspend fun isBookmarked(url: String): Boolean = withContext(Dispatchers.IO) {
        bookmarkDao.isBookmarked(url)
    }

    suspend fun clearBookmarks() = withContext(Dispatchers.IO) {
        bookmarkDao.clearAllBookmarks()
    }
}

