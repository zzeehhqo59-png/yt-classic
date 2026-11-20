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
        Subs.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { DownloaderImpl.instance.client })) }
            .build()
}
