package com.raaghav99.btmanager

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BtPairingAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return

        val isPairingDialog = cls.contains("BluetoothPairing", ignoreCase = true) ||
            cls.contains("PairingDialog", ignoreCase = true) ||
            (pkg.contains("bluetooth", ignoreCase = true) && cls.contains("dialog", ignoreCase = true)) ||
            (pkg == "com.android.tv.settings" && cls.contains("dialog", ignoreCase = true))

        if (isPairingDialog) {
            val root = rootInActiveWindow ?: return
            clickConfirm(root)
        }
    }

    private fun clickConfirm(node: AccessibilityNodeInfo) {
        // Try standard Android positive button id first
        for (id in listOf("android:id/button1", "com.android.tv.settings:id/button1")) {
            node.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isVisibleToUser }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ?.let { return }
        }

        // Fallback: find any clickable button with confirm-like text
        val confirmWords = setOf("pair", "ok", "yes", "confirm", "accept", "allow")
        findClickable(node)
            .firstOrNull { n -> n.text?.toString()?.lowercase() in confirmWords }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findClickable(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (node.isClickable && node.isVisibleToUser) result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { result.addAll(findClickable(it)) }
        }
        return result
    }

    override fun onInterrupt() {}
}
