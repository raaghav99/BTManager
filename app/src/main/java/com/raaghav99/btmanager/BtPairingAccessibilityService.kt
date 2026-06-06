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
        for (id in listOf("android:id/button1", "com.android.tv.settings:id/button1",
                          "com.android.tv.settings:id/action_title")) {
            node.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isVisibleToUser && it.text?.toString()?.lowercase() in CONFIRM_WORDS }
                ?.let { clickNodeOrParent(it); return }
        }

        // Fallback: find any node with confirm-like text, click it or its clickable parent
        findByText(node)
            .firstOrNull { n -> n.text?.toString()?.lowercase() in CONFIRM_WORDS }
            ?.let { clickNodeOrParent(it) }
    }

    // Xiaomi TV: text nodes are not clickable — walk up to clickable ancestor
    private fun clickNodeOrParent(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.isVisibleToUser) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            n = n.parent
        }
    }

    private fun findByText(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (node.isVisibleToUser && !node.text.isNullOrEmpty()) result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { result.addAll(findByText(it)) }
        }
        return result
    }

    companion object {
        private val CONFIRM_WORDS = setOf("pair", "ok", "yes", "confirm", "accept", "allow")
    }

    override fun onInterrupt() {}
}
