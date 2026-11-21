package com.symphonia.gate2.prototype.harness

import android.service.notification.NotificationListenerService

/**
 * NotificationListenerService stub (Fix 4).
 *
 * Required on Android 10+ so that MediaSessionManager.getActiveSessions(componentName)
 * can be legally called without triggering a SecurityException.
 */
class Gate2NotificationListenerService : NotificationListenerService()
