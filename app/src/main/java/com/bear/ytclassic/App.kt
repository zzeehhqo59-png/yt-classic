package com.bear.ytclassic

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(DownloaderImpl.init(), Localization("th", "TH"), ContentCountry("TH"))
        Prefs.init(this)
        Prefs.applyTheme()
        Db.init(this)
        Playback.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: android.app.Activity) { if (started++ == 0) Playback.onAppVisible(true) }
            override fun onActivityStopped(a: android.app.Activity) { if (--started == 0 && !a.isChangingConfigurations) Playback.onAppVisible(false) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { DownloaderImpl.instance.client })) }
            .build()
}
