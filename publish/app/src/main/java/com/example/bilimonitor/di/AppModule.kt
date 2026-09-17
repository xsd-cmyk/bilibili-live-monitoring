package com.example.bilimonitor.di

import android.content.Context
import com.example.bilimonitor.core.AndroidAppClock
import com.example.bilimonitor.core.BiliCookieJar
import com.example.bilimonitor.core.CredentialStore
import com.example.bilimonitor.core.NetworkTimeSource
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.db.AppDatabaseFactory
import com.example.bilimonitor.data.remote.bilibili.BiliAccountApi
import com.example.bilimonitor.data.remote.bilibili.BiliLiveApi
import com.example.bilimonitor.data.remote.bilibili.BiliLiveApiDataSource
import com.example.bilimonitor.data.remote.bilibili.BiliLiveDataSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideClock(@ApplicationContext context: Context, networkTimeSource: NetworkTimeSource): AppClock =
        AndroidAppClock(context, networkTimeSource)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabaseFactory.create(context)

    @Provides
    @Singleton
    @Named("appScope")
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideCookieJar(credentialStore: CredentialStore): BiliCookieJar = BiliCookieJar(credentialStore)

    @Provides
    @Singleton
    fun provideBiliAccountApi(okHttpClient: OkHttpClient, json: Json): BiliAccountApi =
        Retrofit.Builder()
            .baseUrl("https://api.bilibili.com/")
            // Cookie 已由 provideOkHttp 的拦截器显式写进请求头，
            // 这里不再重复设置 cookieJar（避免两套机制互相覆盖）。
            .client(
                okHttpClient.newBuilder()
                    .addInterceptor { chain ->
                        val resp = chain.proceed(chain.request())
                        // 记录主站接口的响应码与风控相关响应头（只看头，不看凭证）
                        if (chain.request().url.encodedPath.contains("/x/")) {
                            android.util.Log.i(
                                "BiliHttp",
                                "${chain.request().url.encodedPath} → HTTP ${resp.code}" +
                                    "，bili-status=${resp.header("bili-status-code")}" +
                                    "，gaia-vvoucher=${resp.header("x-bili-gaia-vvoucher") != null}" +
                                    "，set-cookie数=${resp.headers("Set-Cookie").size}"
                            )
                        }
                        resp
                    }
                    .build()
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BiliAccountApi::class.java)

    @Provides
    @Singleton
    fun provideOkHttp(
        networkTimeSource: NetworkTimeSource,
        cookieJar: BiliCookieJar
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            // Referer 必须与接口所属站点一致（B 站对来源不匹配的请求会拒绝）。
            val referer = when {
                host.startsWith("api.live.") -> "https://live.bilibili.com/"
                host.contains("passport") -> "https://passport.bilibili.com/"
                else -> "https://www.bilibili.com/"
            }
            // 显式拼装 Cookie 头。
            // 不依赖 OkHttp 的 BridgeInterceptor 自动附加：实测 CookieJar 的
            // loadForRequest 已返回全部 cookie，但最终请求上并没有 Cookie 头
            // （resp.request 也看不到），导致主站接口被判为无凭证请求而返回 -400。
            // 这里直接取 CookieJar 的结果写进请求头，行为显式可控。
            val builder = request.newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
                .header("Referer", referer)
                .header("Origin", referer.trimEnd('/'))
            val cookies = cookieJar.loadForRequest(request.url)
            if (cookies.isNotEmpty()) {
                builder.header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
            }
            chain.proceed(builder.build())
        }
        // 网络时间校准：从 B 站服务器响应 Date 头持续校准偏移（离线时无请求 → 自动回退本地时间）
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.request.url.host.contains("bilibili")) {
                response.headers.getDate("Date")?.let { date ->
                    networkTimeSource.onServerDate(date.time)
                }
            }
            response
        }
        .apply {
            // 仅 debug 构建打开 HTTP 日志：接口异常（如 code=-400）时需要看到请求与响应原文，
            // 否则只能看到应用层包装后的"请求失败"，无法定位。
            if (com.example.bilimonitor.BuildConfig.DEBUG) {
                addInterceptor(
                    okhttp3.logging.HttpLoggingInterceptor().apply {
                        // BASIC 级别：只记录「方法 + URL + 响应码」。
                        //
                        // 绝不使用 BODY/HEADERS：它们会把请求头（含 Cookie 里的 SESSDATA）
                        // 与响应体写进 logcat，而 logcat 任何应用都能读取 —— 等于泄露登录凭证。
                        // 之前为定位 -400 问题临时开过 BODY，已改回 BASIC。
                        level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                    }
                )
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideBiliLiveApi(okHttpClient: OkHttpClient, json: Json): BiliLiveApi =
        Retrofit.Builder()
            .baseUrl("https://api.live.bilibili.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BiliLiveApi::class.java)

    @Provides
    @Singleton
    fun provideBiliLiveDataSource(api: BiliLiveApi): BiliLiveDataSource = BiliLiveApiDataSource(api)

    // ---- DAOs ----

    @Provides fun streamerDao(db: AppDatabase) = db.streamerDao()
    @Provides fun liveSessionDao(db: AppDatabase) = db.liveSessionDao()
    @Provides fun liveSessionTitleDao(db: AppDatabase) = db.liveSessionTitleDao()
    @Provides fun liveSessionAreaDao(db: AppDatabase) = db.liveSessionAreaDao()
    @Provides fun liveEventDao(db: AppDatabase) = db.liveEventDao()
    @Provides fun statusHistoryDao(db: AppDatabase) = db.statusHistoryDao()
    @Provides fun reliableIntervalDao(db: AppDatabase) = db.reliableIntervalDao()
    @Provides fun monitoringGapDao(db: AppDatabase) = db.monitoringGapDao()
    @Provides fun liveSessionCorrectionDao(db: AppDatabase) = db.liveSessionCorrectionDao()
    @Provides fun notificationOutboxDao(db: AppDatabase) = db.notificationOutboxDao()
    @Provides fun notificationIdRegistryDao(db: AppDatabase) = db.notificationIdRegistryDao()
    @Provides fun notificationAggregateDao(db: AppDatabase) = db.notificationAggregateDao()
    @Provides fun notificationHistoryDao(db: AppDatabase) = db.notificationHistoryDao()
    @Provides fun configDao(db: AppDatabase) = db.configDao()
    @Provides fun runtimeLockDao(db: AppDatabase) = db.runtimeLockDao()
    @Provides fun policyDao(db: AppDatabase) = db.policyDao()
    @Provides fun recoverySessionDao(db: AppDatabase) = db.recoverySessionDao()
    @Provides fun crashRecordDao(db: AppDatabase) = db.crashRecordDao()
    @Provides fun logDao(db: AppDatabase) = db.logDao()
    @Provides fun problemDao(db: AppDatabase) = db.problemDao()
    @Provides fun statisticsCacheDao(db: AppDatabase) = db.statisticsCacheDao()
    @Provides fun statisticsSnapshotDao(db: AppDatabase) = db.statisticsSnapshotDao()
    @Provides fun taxonomyDao(db: AppDatabase) = db.taxonomyDao()
    @Provides fun exportDao(db: AppDatabase) = db.exportDao()
    @Provides fun followImportDao(db: AppDatabase) = db.followImportDao()
    @Provides fun restoreDao(db: AppDatabase) = db.restoreDao()
    @Provides fun authSessionDao(db: AppDatabase) = db.authSessionDao()
}
