package com.imtusharahmed.smartsidebar

import android.app.Application
import com.imtusharahmed.smartsidebar.di.sidebarModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class SideBarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SideBarApplication)
            modules(sidebarModule)
        }
    }
}

