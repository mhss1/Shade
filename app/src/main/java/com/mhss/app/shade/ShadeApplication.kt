package com.mhss.app.shade

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.context.GlobalContext.startKoin
import org.koin.ksp.generated.module

class ShadeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidContext(this@ShadeApplication)
            modules(ShadeApplicationModule().module)
        }
    }
}

@Module
@ComponentScan("com.mhss.app.shade")
class ShadeApplicationModule