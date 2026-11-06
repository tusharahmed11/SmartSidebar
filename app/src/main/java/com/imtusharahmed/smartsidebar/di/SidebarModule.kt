package com.imtusharahmed.smartsidebar.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.imtusharahmed.smartsidebar.data.AppRepository
import com.imtusharahmed.smartsidebar.data.SidebarConfigSerializer
import com.imtusharahmed.smartsidebar.diagnostics.DiagnosticRingBuffer
import com.imtusharahmed.smartsidebar.ui.config.SidebarConfigViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

private const val DATASTORE_FILE_NAME = "sidebar_preferences"

val sidebarModule = module {
    single<DataStore<Preferences>> {
        val context: Context = androidContext()
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(DATASTORE_FILE_NAME) }
        )
    }

    single { DiagnosticRingBuffer() }

    single { SidebarConfigSerializer() }

    single {
        AppRepository(
            context = androidContext(),
            dataStore = get(),
            serializer = get(),
            ringBuffer = get()
        )
    }

    viewModelOf(::SidebarConfigViewModel)
}

