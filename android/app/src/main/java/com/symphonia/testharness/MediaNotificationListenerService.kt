package com.symphonia.testharness

import android.service.notification.NotificationListenerService

/**
 * Gate 1 Media Session Helper Service.
 *
 * Implements NotificationListenerService strictly to provide the bound ComponentName
 * required by MediaSessionManager.getActiveSessions(componentName).
 *
 * Per PRD §7.4 and Technical Architecture §3.3: Symphonia never reads, stores, logs,
 * or transmits notification text or payloads.
 */
class MediaNotificationListenerService : NotificationListenerService()
