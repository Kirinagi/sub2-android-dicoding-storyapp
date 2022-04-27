package com.example.myapplication.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.example.myapplication.api.ApiService
import com.example.myapplication.api.Keys
import com.example.myapplication.api.ListStory
import com.example.myapplication.database.StoryDatabase

@ExperimentalPagingApi
class RemoteMediator(
    private val database: StoryDatabase,
    private val apiService: ApiService,
    private val token: String
) : RemoteMediator<Int, ListStory>() {

    private companion object {
        const val INITIAL_PAGE_INDEX = 1
    }


    override suspend fun initialize(): InitializeAction {
        return InitializeAction.LAUNCH_INITIAL_REFRESH
    }


    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, ListStory>
    ): MediatorResult {
        val page = when (loadType) {
            LoadType.REFRESH -> {
                val remoteKeys = getRemoteKeyClosestToCurrentPosition(state)
                remoteKeys?.nextKey?.minus(1) ?: INITIAL_PAGE_INDEX
            }
            LoadType.PREPEND -> {
                val remoteKeys = getRemoteKeyForFirstItem(state)
                val prevKey = remoteKeys?.prevKey
                    ?: return MediatorResult.Success(endOfPaginationReached = remoteKeys != null)
                prevKey
            }
            LoadType.APPEND -> {
                val remoteKeys = getRemoteKeyForLastItem(state)
                val nextKey = remoteKeys?.nextKey
                    ?: return MediatorResult.Success(endOfPaginationReached = remoteKeys != null)
                nextKey
            }
        }


        try {
            val responseData = apiService.getStories(token, page, state.config.pageSize)
            val endOfPaginationReached = responseData.listStory.isEmpty()

            database.withTransaction {
                if (loadType == LoadType.REFRESH) {
                    database.StoryDao().deleteAll()
                    database.KeysDao().deleteKeys()
                }
                val prevKey = if (page == 1) null else page - 1
                val nextKey = if (endOfPaginationReached) null else page + 1
                val keys = responseData.listStory.map { keys ->
                    Keys(id = keys.id, prevKey = prevKey, nextKey = nextKey)
                }
                database.KeysDao().insertAll(keys)
                database.StoryDao().insertStory(responseData.listStory)

            }
            return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
        } catch (exception: Exception) {
            return MediatorResult.Error(exception)
        }
    }


    private suspend fun getRemoteKeyForLastItem(state: PagingState<Int, ListStory>): Keys? {
        return state.pages.lastOrNull { it.data.isNotEmpty() }?.data?.lastOrNull()?.let { data ->
            database.KeysDao().getRemoteKeysId(data.id)
        }
    }


    private suspend fun getRemoteKeyForFirstItem(state: PagingState<Int, ListStory>): Keys? {
        return state.pages.firstOrNull { it.data.isNotEmpty() }?.data?.firstOrNull()?.let { data ->
            database.KeysDao().getRemoteKeysId(data.id)
        }
    }


    private suspend fun getRemoteKeyClosestToCurrentPosition(state: PagingState<Int, ListStory>): Keys? {
        return state.anchorPosition?.let { position ->
            state.closestItemToPosition(position)?.id?.let { id ->
                database.KeysDao().getRemoteKeysId(id)

            }
        }
    }
}