package com.fanhuatv.core.di

import android.content.Context
import androidx.room.Room
import com.fanhuatv.core.storage.AppDatabase
import com.fanhuatv.core.storage.dao.FavoriteDao
import com.fanhuatv.core.storage.dao.HistoryDao
import com.fanhuatv.core.storage.dao.SavedScriptDao
import com.fanhuatv.core.storage.dao.SearchHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "fanhua.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideHistoryDao(db: AppDatabase): HistoryDao = db.historyDao()
    @Provides fun provideFavoriteDao(db: AppDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideSearchDao(db: AppDatabase): SearchHistoryDao = db.searchHistoryDao()
    @Provides fun provideScriptDao(db: AppDatabase): SavedScriptDao = db.savedScriptDao()
}
