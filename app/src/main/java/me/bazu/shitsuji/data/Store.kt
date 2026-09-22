package me.bazu.shitsuji.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences as DsPreferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import me.bazu.shitsuji.agent.Budget

private val Context.dataStore by preferencesDataStore(name = "shitsuji")

/**
 * 端末内にすべて置く。サーバーは無い。
 *
 * これは設計上の都合ではなく選択で、サーバー代を 0 円にするのと同時に、
 * 体格・所持品・APIキーといった情報を自分の端末から出さないためでもある。
 */
class Store(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ---- APIキー: 端末の Keystore で暗号化して保存する ----

    private val secrets: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun apiKey(): String? = secrets.getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(value: String) {
        secrets.edit().putString(KEY_API, value.trim()).apply()
    }

    fun hasApiKey(): Boolean = apiKey() != null

    // ---- プロフィール ----

    val profileFlow: Flow<Profile> = context.dataStore.data.map { prefs ->
        prefs[PROFILE_JSON]?.let { raw ->
            runCatching { json.decodeFromString<Profile>(raw) }.getOrDefault(Profile())
        } ?: Profile()
    }

    suspend fun profile(): Profile = profileFlow.first()

    suspend fun saveProfile(profile: Profile) {
        context.dataStore.edit { it[PROFILE_JSON] = json.encodeToString(Profile.serializer(), profile) }
    }

    /** 設定画面でそのまま編集できるように JSON テキストとしても出し入れできる。 */
    suspend fun profileJson(): String = json.encodeToString(Profile.serializer(), profile())

    suspend fun saveProfileJson(raw: String): Result<Unit> = runCatching {
        val parsed = json.decodeFromString<Profile>(raw)
        saveProfile(parsed)
    }

    // ---- 設定 ----

    val settingsFlow: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            capJpy = prefs[CAP_JPY] ?: DEFAULT_CAP_JPY,
            usdJpy = prefs[USD_JPY] ?: DEFAULT_USD_JPY,
            defaultModelId = prefs[DEFAULT_MODEL] ?: "claude-haiku-4-5",
            listJsOverride = prefs[LIST_JS_OVERRIDE].orEmpty(),
            detailJsOverride = prefs[DETAIL_JS_OVERRIDE].orEmpty(),
        )
    }

    suspend fun settings(): Settings = settingsFlow.first()

    suspend fun saveSettings(s: Settings) {
        context.dataStore.edit { prefs ->
            prefs[CAP_JPY] = s.capJpy
            prefs[USD_JPY] = s.usdJpy
            prefs[DEFAULT_MODEL] = s.defaultModelId
            prefs[LIST_JS_OVERRIDE] = s.listJsOverride
            prefs[DETAIL_JS_OVERRIDE] = s.detailJsOverride
        }
    }

    // ---- 予算 ----

    val budgetStore: Budget.BudgetStore = object : Budget.BudgetStore {
        override suspend fun read(monthKey: String): Pair<Double, Int> {
            val prefs = context.dataStore.data.first()
            return (prefs[spentKey(monthKey)] ?: 0.0) to (prefs[callsKey(monthKey)] ?: 0)
        }

        override suspend fun write(monthKey: String, spentUsd: Double, calls: Int) {
            context.dataStore.edit {
                it[spentKey(monthKey)] = spentUsd
                it[callsKey(monthKey)] = calls
            }
        }

        override suspend fun capJpy(): Int = settings().capJpy
        override suspend fun usdJpy(): Double = settings().usdJpy
    }

    companion object {
        const val KEY_API = "anthropic_api_key"
        const val DEFAULT_CAP_JPY = 500
        const val DEFAULT_USD_JPY = 155.0

        private val PROFILE_JSON = stringPreferencesKey("profile_json")
        private val CAP_JPY = intPreferencesKey("cap_jpy")
        private val USD_JPY = doublePreferencesKey("usd_jpy")
        private val DEFAULT_MODEL = stringPreferencesKey("default_model")
        private val LIST_JS_OVERRIDE = stringPreferencesKey("list_js_override")
        private val DETAIL_JS_OVERRIDE = stringPreferencesKey("detail_js_override")

        private fun spentKey(month: String): DsPreferences.Key<Double> =
            doublePreferencesKey("spent_usd_$month")

        private fun callsKey(month: String): DsPreferences.Key<Int> =
            intPreferencesKey("calls_$month")
    }
}

data class Settings(
    val capJpy: Int = Store.DEFAULT_CAP_JPY,
    val usdJpy: Double = Store.DEFAULT_USD_JPY,
    val defaultModelId: String = "claude-haiku-4-5",
    /**
     * メルカリのページ構造が変わったとき、APK を作り直さずに直せる逃げ道。
     * 空なら組み込みのスクリプトを使う。
     */
    val listJsOverride: String = "",
    val detailJsOverride: String = "",
)
