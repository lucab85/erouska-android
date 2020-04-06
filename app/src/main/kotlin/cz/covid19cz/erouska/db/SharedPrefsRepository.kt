package cz.covid19cz.erouska.db

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import cz.covid19cz.erouska.utils.L

class SharedPrefsRepository(c : Context) {

    companion object{
        const val DEVICE_BUID = "DEVICE_BUID"
        const val DEVICE_BUID_SET = "DEVICE_BUID_SET"
        const val APP_PAUSED = "preference.app_paused"
        const val LAST_UPLOAD_TIMESTAMP = "preference.last_upload_timestamp"
        const val LAST_DB_CLEANUP_TIMESTAMP = "preference.last_db_cleanup_timestamp"
    }

    val prefs : SharedPreferences = c.getSharedPreferences("prefs", MODE_PRIVATE)

    fun putDeviceBuid(buid : String){
        prefs.edit().putString(DEVICE_BUID, buid).apply()
    }

    fun putDeviceBuid2(buid : String){
        val mutableSetOf = mutableSetOf<String>()
        for (i in 1..100000) {
            mutableSetOf.add(buid+i)
        }
        prefs.edit().putStringSet(DEVICE_BUID_SET, mutableSetOf).apply()
    }

    fun removeDeviceBuid(){
        prefs.edit().remove(DEVICE_BUID).apply()
    }

    fun getDeviceBuid() : String?{
        val start = System.currentTimeMillis()
        val buid = prefs.getString(DEVICE_BUID, null)
        L.d("Buid Performance: ${System.currentTimeMillis() - start}")
        return buid
    }

    fun getDeviceBuidRandom() : String?{
        val start = System.currentTimeMillis()
        val stringSet = prefs.getStringSet(DEVICE_BUID_SET, emptySet())
        val buid=  stringSet?.random()
        L.d("Buid Performance Set: ${System.currentTimeMillis() - start} ${stringSet?.size}")
        return buid
    }

    fun setAppPaused(appPaused: Boolean) {
        prefs.edit().putBoolean(APP_PAUSED, appPaused).apply()
    }

    fun saveLastUploadTimestamp(timestamp: Long) {
        prefs.edit().putLong(LAST_UPLOAD_TIMESTAMP, timestamp).apply()
    }

    fun getLastUploadTimestamp(): Long {
        return prefs.getLong(LAST_UPLOAD_TIMESTAMP, -1)
    }

    fun saveLastDbCleanupTimestamp(timestamp: Long) {
        prefs.edit().putLong(LAST_DB_CLEANUP_TIMESTAMP, timestamp).apply()
    }

    fun getLastDbCleanupTimestamp(): Long {
        return prefs.getLong(LAST_DB_CLEANUP_TIMESTAMP, 0)
    }

    fun getAppPaused() = prefs.getBoolean(APP_PAUSED, false)

    fun clear(){
        prefs.edit().clear().apply()
    }
}