package cz.covid19cz.erouska.utils

import android.content.Intent
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

class Telemetry(private val firebaseAnalytics: FirebaseAnalytics) {

    companion object {
            const val SERVICE_LIFECYCLE = "service_lifecycle"
            const val SERVICE_CREATED = "service_started"
            const val SERVICE_DESTROYED = "service_destroyed"
            const val SERVICE_TASK_REMOVED = "service_task_removed"
            const val INTENT_RECEIVED = "service_intent_received"
    }

    fun trackServiceCreated(serviceName: String, serviceId: String) {
        val bundle = Bundle()
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, serviceName)
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, serviceId)
        bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, SERVICE_LIFECYCLE)
        firebaseAnalytics.logEvent(SERVICE_CREATED, bundle)
    }

    fun trackServiceDestroyed(serviceName: String, serviceId: String) {
        val bundle = Bundle()
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, serviceName)
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, serviceId)
        bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, SERVICE_LIFECYCLE)
        firebaseAnalytics.logEvent(SERVICE_DESTROYED, bundle)
    }

    fun trackServiceTaskRemoved(serviceName: String, serviceId: String) {
        val bundle = Bundle()
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, serviceName)
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, serviceId)
        bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, SERVICE_LIFECYCLE)
        firebaseAnalytics.logEvent(SERVICE_TASK_REMOVED, bundle)
    }

    fun trackIntentReceived(serviceName: String, serviceId: String, intent: Intent?) {
        val bundle = Bundle()
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, serviceName)
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, serviceId)
        bundle.putString(FirebaseAnalytics.Param.VALUE, intent.toString())
        bundle.putString(FirebaseAnalytics.Param.ITEM_CATEGORY, SERVICE_LIFECYCLE)
        firebaseAnalytics.logEvent(INTENT_RECEIVED, bundle)
    }

}