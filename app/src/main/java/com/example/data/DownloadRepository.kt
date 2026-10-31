package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class DownloadRepository(private val daoProvider: () -> DownloadDao) {
    constructor(dao: DownloadDao) : this({ dao })

    private val dao: DownloadDao
        get() = daoProvider()

    val allDownloads: Flow<List<DownloadItem>> = flow {
        emitAll(dao.getAllDownloads())
    }.flowOn(Dispatchers.IO)

    val nonIncognitoDownloads: Flow<List<DownloadItem>> = flow {
        emitAll(dao.getNonIncognitoDownloads())
    }.flowOn(Dispatchers.IO)

    suspend fun insertOrUpdate(downloadItem: DownloadItem) = withContext(Dispatchers.IO) {
        dao.insertOrUpdate(downloadItem)
    }

    suspend fun getDownloadById(id: String): DownloadItem? = withContext(Dispatchers.IO) {
        dao.getDownloadById(id)
    }

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    suspend fun clearCompleted() = withContext(Dispatchers.IO) {
        dao.clearCompleted()
    }

    suspend fun clearIncognito() = withContext(Dispatchers.IO) {
        dao.clearIncognito()
    }
}

