package com.openjarvis.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class ScreenReader(context: Context) {
    // A service is also a Context. One constructor avoids ambiguous overloads.
    private val boundService = context as? JarvisAccessibilityService
    private val service: JarvisAccessibilityService?
        get() = boundService ?: JarvisAccessibilityService.instance

    fun extractAllText(): String {
        val root = service?.rootInActiveWindow ?: return ""
        return try {
            val builder = StringBuilder()
            extractTextRecursive(root, builder)
            builder.toString()
        } finally { root.recycle() }
    }

    private fun extractTextRecursive(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.takeIf { it.isNotBlank() }?.let { builder.append(it).append(' ') }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { builder.append(it).append(' ') }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { extractTextRecursive(child, builder) } finally { child.recycle() }
        }
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val normalized = text.lowercase()
        return findNode { node ->
            node.text?.toString()?.lowercase()?.contains(normalized) == true ||
                node.contentDescription?.toString()?.lowercase()?.contains(normalized) == true
        }
    }

    fun findNodeByHint(hint: String): AccessibilityNodeInfo? {
        val normalized = hint.lowercase()
        return findNode { it.hintText?.toString()?.lowercase()?.contains(normalized) == true }
    }

    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = service?.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        try {
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                var returned = false
                try {
                    if (matches(current)) {
                        returned = true
                        return current
                    }
                    for (index in 0 until current.childCount) current.getChild(index)?.let { queue.add(it) }
                } finally { if (!returned) current.recycle() }
            }
            return null
        } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
    }

    fun getFocusedNode(): AccessibilityNodeInfo? {
        val root = service?.rootInActiveWindow ?: return null
        return try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } finally { root.recycle() }
    }
}
