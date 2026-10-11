package com.jobsnap.assistant

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JobSnapAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: LinearLayout
    private lateinit var collapsedTab: TextView
    private lateinit var expandedDock: LinearLayout
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        createGlobalFloatingSidebar()
        Toast.makeText(this, "职捕AI侧边栏已启动，可打开招聘软件点击采集", Toast.LENGTH_LONG).show()
    }

    private fun createGlobalFloatingSidebar() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. 缩小后的侧边微缩胶囊
        collapsedTab = TextView(this).apply {
            text = "◀ 职捕AI"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(22, 32, 18, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB0F172A"))
                cornerRadii = floatArrayOf(36f, 36f, 0f, 0f, 0f, 0f, 36f, 36f)
                setStroke(2, Color.parseColor("#334155"))
            }
            visibility = View.GONE
            setOnClickListener {
                collapsedTab.visibility = View.GONE
                expandedDock.visibility = View.VISIBLE
            }
        }

        // 2. 展开后的四大按钮悬浮栏
        expandedDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F20F172A"))
                cornerRadius = 36f
                setStroke(2, Color.parseColor("#334155"))
            }
        }

        val btnCollapse = TextView(this).apply {
            text = "收起为侧边胶囊 ▶"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            gravity = Gravity.END
            setPadding(8, 4, 8, 14)
            setOnClickListener {
                expandedDock.visibility = View.GONE
                collapsedTab.visibility = View.VISIBLE
            }
        }

        val btn1Job = createDockButton("识别岗位信息", "#2563EB") {
            val screenText = extractCurrentScreenText()
            if (screenText.length < 10) {
                Toast.makeText(this, "当前屏幕未检测到足够的岗位文字", Toast.LENGTH_SHORT).show()
                return@createDockButton
            }
            analyzeJobFromScreen(screenText)
        }

        val btn2Company = createDockButton("识别业务公司", "#4F46E5") {
            val screenText = extractCurrentScreenText()
            if (screenText.length < 10) {
                Toast.makeText(this, "当前屏幕未检测到公司介绍文字", Toast.LENGTH_SHORT).show()
                return@createDockButton
            }
            analyzeCompanyFromScreen(screenText)
        }

        val btn3Intention = createDockButton("加入意向岗位", "#D97706") {
            val screenText = extractCurrentScreenText()
            captureJobRecord(screenText, "意向岗位")
        }

        val btn4Applied = createDockButton("标记已投递", "#16A34A") {
            val screenText = extractCurrentScreenText()
            captureJobRecord(screenText, "已投递")
        }

        expandedDock.addView(btnCollapse)
        expandedDock.addView(btn1Job)
        expandedDock.addView(btn2Company)
        expandedDock.addView(btn3Intention)
        expandedDock.addView(btn4Applied)

        // 支持上下拖动悬浮窗位置
        var initialY = 0
        var initialTouchY = 0f
        btnCollapse.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = layoutParams.y
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dy) > 10) {
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(rootContainer, layoutParams)
                        true
                    } else false
                }
                else -> false
            }
        }

        rootContainer.addView(collapsedTab)
        rootContainer.addView(expandedDock)
        windowManager.addView(rootContainer, layoutParams)
    }

    private fun createDockButton(label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11.5f
            isAllCaps = false
            setPadding(24, 16, 24, 16)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 22f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun extractCurrentScreenText(): String {
        val rootNode: AccessibilityNodeInfo = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        walkNodeTree(rootNode, sb)
        return sb.toString()
    }

    private fun walkNodeTree(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (!node.text.isNullOrBlank()) {
            sb.append(node.text.toString().trim()).append("\n")
        } else if (!node.contentDescription.isNullOrBlank()) {
            sb.append(node.contentDescription.toString().trim()).append("\n")
        }
        for (i in 0 until node.childCount) {
            walkNodeTree(node.getChild(i), sb)
        }
    }

    private fun analyzeJobFromScreen(screenText: String) {
        Toast.makeText(this, "正在扫描提炼当前屏幕岗位职责与要求...", Toast.LENGTH_SHORT).show()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("jobText", screenText)
                val res = postJson("${JobSnapConfig.BACKEND_URL}/api/analyze-job", payload)
                val summary = res.optString("oneSentenceSummary")
                val edu = res.optString("educationAndMajor")
                val exp = res.optString("experience")
                val skills = jsonArrayToList(res.optJSONArray("skills"))
                val hardReqs = jsonArrayToList(res.optJSONArray("hardRequirements"))

                val content = buildString {
                    append("【一句话概括职责核心工作】\n").append(summary).append("\n\n")
                    append("🎓 学历职业：").append(edu).append("\n")
                    append("⏳ 经验要求：").append(exp).append("\n\n")
                    append("🛠 技能要求：\n• ").append(skills.joinToString("\n• ")).append("\n\n")
                    append("⚠️ 硬性要求：\n• ").append(hardReqs.joinToString("\n• "))
                }
                withContext(Dispatchers.Main) {
                    showOverlayResultDialog("识别岗位信息 · ${res.optString("position", "当前岗位")}", content)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@JobSnapAccessibilityService, "识别失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun analyzeCompanyFromScreen(screenText: String) {
        Toast.makeText(this, "正在扫描识别业务公司与实际工作内容...", Toast.LENGTH_SHORT).show()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject()
                    .put("companyText", screenText)
                    .put("jobText", screenText)
                val res = postJson("${JobSnapConfig.BACKEND_URL}/api/analyze-company", payload)
                val workItems = jsonArrayToList(res.optJSONArray("roleWorkSummary"))

                val content = buildString {
                    append("🏷 所属行业：").append(res.optString("industry")).append("\n")
                    append("👥 公司规模：").append(res.optString("companyScale")).append("\n")
                    append("📈 发展阶段：").append(res.optString("developmentStage")).append("\n\n")
                    append("🏢 主营业务：\n").append(res.optString("mainBusiness")).append("\n\n")
                    append("🎯 根据岗位总结出的工作内容：\n• ").append(workItems.joinToString("\n• "))
                }
                withContext(Dispatchers.Main) {
                    showOverlayResultDialog("识别业务公司 · ${res.optString("companyName", "当前企业")}", content)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@JobSnapAccessibilityService, "识别失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun captureJobRecord(screenText: String, targetStatus: String) {
        Toast.makeText(this, "正在提取公司/岗位/薪资并记为【$targetStatus】...", Toast.LENGTH_SHORT).show()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("jobText", screenText)
                val res = postJson("${JobSnapConfig.BACKEND_URL}/api/analyze-job", payload)
                val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                val appliedDate = if (targetStatus == "已投递") nowStr else "—"

                val summaryMsg = buildString {
                    append("🏢 公司：").append(res.optString("company")).append("\n")
                    append("💼 岗位：").append(res.optString("position")).append("\n")
                    append("💰 薪资：").append(res.optString("salary")).append("\n")
                    append("📅 保存日期：").append(nowStr).append("\n")
                    append("🚀 投递日期：").append(appliedDate).append("\n")
                    append("📌 当前状态：").append(targetStatus)
                }
                withContext(Dispatchers.Main) {
                    showOverlayResultDialog("已成功采集入表 ($targetStatus)", summaryMsg)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@JobSnapAccessibilityService, "采集失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showOverlayResultDialog(title: String, message: String) {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val scrollView = ScrollView(this)
        val tv = TextView(this).apply {
            text = message
            textSize = 13.5f
            setTextColor(Color.parseColor("#1E293B"))
            setPadding(48, 36, 48, 24)
            setLineSpacing(0f, 1.35f)
        }
        scrollView.addView(tv)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("我知道了", null)
            .create()

        dialog.window?.setType(overlayType)
        dialog.show()
    }

    private fun postJson(urlString: String, body: JSONObject): JSONObject {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connectTimeout = 20000
            readTimeout = 25000
            doOutput = true
            outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        return JSONObject(responseText)
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            list.add(arr.optString(i))
        }
        return list
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
