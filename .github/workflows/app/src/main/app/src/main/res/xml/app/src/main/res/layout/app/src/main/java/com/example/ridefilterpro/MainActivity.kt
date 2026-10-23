package com.example.ridefilterpro

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AcceptService : AccessibilityService() {
  companion object { @Volatile var running = false }

  private var lastClick = 0L
  private var lastText = ""
  private var wm: WindowManager? = null
  private var fab: TextView? = null
  private var tts: TextToSpeech? = null

  private fun prefs() = getSharedPreferences("cfg", MODE_PRIVATE)

  override fun onServiceConnected() {
    super.onServiceConnected()
    running = true
    tts = TextToSpeech(this) { s ->
      if (s == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("en", "IN"))
    }
    showFab()
  }

  override fun onUnbind(intent: Intent?): Boolean {
    running = false
    fab?.let { try { wm?.removeView(it) } catch (e: Exception) {} }
    fab = null
    tts?.shutdown()
    return super.onUnbind(intent)
  }

  private fun paint() {
    val on = prefs().getBoolean("on", false)
    fab?.text = if (on) "ON" else "OFF"
    (fab?.background as? GradientDrawable)?.setColor(
      Color.parseColor(if (on) "#00A152" else "#757575"))
  }

  private fun showFab() {
    if (fab != null) return
    wm = getSystemService(WINDOW_SERVICE) as WindowManager
    val v = TextView(this)
    v.gravity = Gravity.CENTER
    v.setTextColor(Color.WHITE)
    v.textSize = 14f
    v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    val lp = WindowManager.LayoutParams(
      150, 150,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      PixelFormat.TRANSLUCENT)
    lp.gravity = Gravity.TOP or Gravity.START
    lp.x = 20
    lp.y = 300
    v.setOnTouchListener(object : View.OnTouchListener {
      var ix = 0; var iy = 0; var sx = 0f; var sy = 0f; var moved = false
      override fun onTouch(view: View?, e: MotionEvent): Boolean {
        when (e.action) {
          MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; sx = e.rawX; sy = e.rawY; moved = false }
          MotionEvent.ACTION_MOVE -> {
            val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
            if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
            if (moved) { lp.x = ix + dx; lp.y = iy + dy; wm?.updateViewLayout(v, lp) }
          }
          MotionEvent.ACTION_UP -> if (!moved) {
            val p = prefs()
            p.edit().putBoolean("on", !p.getBoolean("on", false)).apply()
            paint()
          }
        }
        return true
      }
    })
    fab = v
    wm?.addView(v, lp)
    paint()
  }

  private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>) {
    if (n == null) return
    n.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
    for (i in 0 until n.childCount) collect(n.getChild(i), out)
  }

  private fun log(line: String) {
    val p = prefs()
    val t = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
    val old = p.getString("hist", "") ?: ""
    val all = (listOf("$t  $line") + old.split("\n").filter { it.isNotBlank() }).take(40)
    p.edit().putString("hist", all.joinToString("\n")).apply()
  }

  private fun alert(trip: Double) {
    val p = prefs()
    if (p.getBoolean("snd", true)) try {
      RingtoneManager.getRingtone(this,
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play()
    } catch (e: Exception) {}
    if (p.getBoolean("vib", true)) try {
      val vb = getSystemService(VIBRATOR_SERVICE) as Vibrator
      vb.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) {}
    if (p.getBoolean("voice", true)) {
      tts?.speak("Booking accepted. Trip " + String.format("%.1f", trip) + " kilometre",
        TextToSpeech.QUEUE_FLUSH, null, "acc")
    }
  }

  private fun findBtn(root: AccessibilityNodeInfo, t: String): AccessibilityNodeInfo? =
    root.findAccessibilityNodeInfosByText(t)
      .firstOrNull { it.text?.toString()?.trim().equals(t, true) }

  private fun clickText(root: AccessibilityNodeInfo, t: String): Boolean {
    var n: AccessibilityNodeInfo? = findBtn(root, t) ?: return false
    while (n != null && !n.isClickable) n = n.parent
    return n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
  }

  private fun matches(p: SharedPreferences, all: String, pickup: Double, trip: Double, fare: Double?): Boolean {
    val minTrip = p.getFloat("min", 3.6f).toDouble()
    val maxTrip = p.getFloat("max", 149.9f).toDouble()
    val maxPick = p.getFloat("pick", 4.0f).toDouble()
    val minFare = p.getString("fare", "0")?.toDoubleOrNull() ?: 0.0
    val minRate = p.getString("rate", "0")?.toDoubleOrNull() ?: 0.0
    if (pickup > maxPick || trip < minTrip || trip > maxTrip) return false
    if (minFare > 0 && (fare == null || fare < minFare)) return false
    if (minRate > 0 && (fare == null || trip <= 0 || fare / trip < minRate)) return false
    val low = all.lowercase()
    val found = listOf("bike", "auto", "cab").filter { low.contains(it) }
    if (found.isNotEmpty() && found.none { p.getBoolean("t_$it", true) }) return false
    if (p.getBoolean("go_on", false)) {
      val areas = (p.getString("go_areas", "") ?: "").split(",")
        .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
      if (areas.isNotEmpty() && areas.none { low.contains(it) }) return false
    }
    return true
  }

  override fun onAccessibilityEvent(e: AccessibilityEvent?) {
    val p = prefs()
    if (!p.getBoolean("on", false)) return
    val pkg = e?.packageName?.toString() ?: return
    val target = p.getString("pkg", "com.rapido.rider") ?: ""
    if (target.isEmpty() || pkg != target) return
    val root = rootInActiveWindow ?: return
    val texts = ArrayList<String>()
    collect(root, texts)
    val all = texts.joinToString(" | ")
    if (all != lastText) {
      lastText = all
      p.edit().putString("last", all).apply()
    }
    val btnText = p.getString("btn", "Accept") ?: "Accept"
    if (findBtn(root, btnText) == null) return

    val kms = Regex("""(\d+(?:\.\d+)?)\s*km""", RegexOption.IGNORE_CASE)
      .findAll(all).map { it.groupValues[1].toDouble() }.toList()
    if (kms.size < 2) return
    val pickup = kms[0]
    val trip = kms[1]
    val fare = Regex("""₹\s*(\d+(?:\.\d+)?)""").find(all)?.groupValues?.get(1)?.toDouble()

    val gap = ((p.getString("gap", "2")?.toDoubleOrNull() ?: 2.0) * 1000).toLong()
    val now = System.currentTimeMillis()
    if (now - lastClick < gap) return
    val info = "pickup ${pickup}km, trip ${trip}km" + (if (fare != null) ", ₹$fare" else "")
    val active = p.getBoolean("active", false)

    if (!matches(p, all, pickup, trip, fare)) {
      if (active && p.getBoolean("decline", false)) {
        val dt = p.getString("dtext", "Skip") ?: "Skip"
        if (clickText(root, dt)) {
          lastClick = now
          log("DECLINED: $info")
        }
      }
      return
    }

    if (!active) {
      lastClick = now
      log("MONITOR match: $info")
      return
    }
    if (clickText(root, btnText)) {
      lastClick = now
      p.edit().putInt("count", p.getInt("count", 0) + 1).apply()
      log("ACCEPTED: $info")
      alert(trip)
    }
  }

  override fun onInterrupt() {}
}

class MainActivity : Activity() {
  private lateinit var p: SharedPreferences
  private lateinit var container: FrameLayout
  private val tabs = ArrayList<TextView>()
  private val names = listOf("Status", "Rules", "Activity", "Help", "Checks")
  private var cur = 0
  private var goOpen = false
  private var advOpen = false
  private val BLUE = Color.parseColor("#1A5DD8")
  private val GREEN = Color.parseColor("#00794A")
  private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
  private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun txt(s: String, size: Float = 15f, color: Int = Color.parseColor("#222222"), bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = s; t.textSize = size; t.setTextColor(color)
    if (bold) t.setTypeface(null, Typeface.BOLD)
    return t
  }

  private fun card(parent: LinearLayout): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.setPadding(dp(16), dp(14), dp(16), dp(14))
    val g = GradientDrawable()
    g.setColor(Color.WHITE); g.cornerRadius = dp(16).toFloat()
    g.setStroke(dp(1), Color.parseColor("#E3E6EC"))
    c.background = g
    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
    lp.setMargins(0, 0, 0, dp(12))
    parent.addView(c, lp)
    return c
  }

  private fun heading(parent: LinearLayout, t: String, sub: String) {
    parent.addView(txt(t, 30f, Color.BLACK, true))
    val s = txt(sub, 14f, Color.GRAY); s.setPadding(0, 0, 0, dp(16)); parent.addView(s)
  }

  private fun section(parent: LinearLayout, title: String, open: Boolean, onToggle: () -> Unit) {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.setPadding(0, dp(8), 0, dp(8))
    row.addView(txt(title, 18f, Color.BLACK, true), LinearLayout.LayoutParams(0, WRAP, 1f))
    val t = txt(if (open) "Hide" else "Show", 16f, BLUE, true)
    t.setOnClickListener { onToggle() }
    row.addView(t)
    parent.addView(row)
  }

  private fun btn(label: String, filled: Boolean, onClick: () -> Unit): Button {
    val b = Button(this)
    b.text = label; b.isAllCaps = false
    val g = GradientDrawable()
    g.cornerRadius = dp(12).toFloat()
    if (filled) { g.setColor(BLUE); b.setTextColor(Color.WHITE) }
    else { g.setColor(Color.WHITE); g.setStroke(dp(1), Color.parseColor("#C9CED8")); b.setTextColor(Color.parseColor("#333333")) }
    b.background = g
    b.setOnClickListener { onClick() }
    return b
  }

  private fun slider(parent: LinearLayout, label: String, key: String, maxKm: Float, def: Float, note: (Float) -> String) {
    val c = card(parent)
    val head = LinearLayout(this)
    head.orientation = LinearLayout.HORIZONTAL
    head.addView(txt(label, 17f), LinearLayout.LayoutParams(0, WRAP, 1f))
    val v = txt("", 17f, BLUE, true)
    head.addView(v)
    val n = txt("", 13f, Color.GRAY)
    val sb = SeekBar(this)
    sb.max = (maxKm * 10).toInt()
    sb.progress = (p.getFloat(key, def) * 10).toInt()
    sb.progressTintList = ColorStateList.valueOf(BLUE)
    sb.thumbTintList = ColorStateList.valueOf(BLUE)
    fun upd(x: Float) { v.text = String.format("%.1f km", x); n.text = note(x) }
    upd(p.getFloat(key, def))
    sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(s: SeekBar?, pr: Int, u: Boolean) {
        val x = pr / 10f
        upd(x)
        p.edit().putFloat(key, x).apply()
      }
      override fun onStartTrackingTouch(s: SeekBar?) {}
      override fun onStopTrackingTouch(s: SeekBar?) {}
    })
    c.addView(head); c.addView(sb); c.addView(n)
  }

  private fun toggle(parent: LinearLayout, label: String, key: String, def: Boolean) {
    val s = Switch(this)
    s.text = label; s.textSize = 16f
    s.isChecked = p.getBoolean(key, def)
    s.setOnCheckedChangeListener { _, c -> p.edit().putBoolean(key, c).apply() }
    parent.addView(s)
  }

  private fun field(parent: LinearLayout, label: String, key: String, def: String, numeric: Boolean) {
    parent.addView(txt(label, 14f, Color.GRAY))
    val e = EditText(this)
    e.setText(p.getString(key, def))
    if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    e.addTextChangedListener(object : TextWatcher {
      override fun afterTextChanged(s: Editable?) { p.edit().putString(key, s.toString().trim()).apply() }
      override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
      override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    parent.addView(e)
  }

  private fun build(i: Int): View {
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(18), dp(36), dp(18), dp(24))
    when (i) {
      0 -> {
        heading(col, "Status", "Auto accept ka control")
        val on = p.getBoolean("on", false)
        val c = card(col)
        c.addView(txt(if (on) "Auto accept ON" else "Auto accept OFF", 22f, if (on) GREEN else Color.GRAY, true))
        c.addView(txt("Mode: " + (if (p.getBoolean("active", false)) "Active" else "Monitor"), 15f, Color.GRAY))
        val b = btn(if (on) "Turn OFF" else "Turn ON", true) {
          p.edit().putBoolean("on", !p.getBoolean("on", false)).apply(); show(0)
        }
        c.addView(b, LinearLayout.LayoutParams(MATCH, WRAP))
        val c2 = card(col)
        c2.addView(txt("Accepted bookings: " + p.getInt("count", 0), 17f, Color.BLACK, true))
        c2.addView(txt("Floating ON/OFF circle bhi yahi switch chalata hai.", 13f, Color.GRAY))
      }
      1 -> {
        heading(col, "Rules", "Kaun si booking leni hai")
        col.addView(txt("Trip distance", 18f, Color.BLACK, true))
        slider(col, "Minimum", "min", 50f, 3.6f) { "Only rides longer than " + String.format("%.1f", it) + " km will be taken" }
        slider(col, "Maximum", "max", 300f, 149.9f) { "Rides longer than " + String.format("%.1f", it) + " km will be left" }
        col.addView(txt("Pickup distance", 18f, Color.BLACK, true))
        slider(col, "Maximum", "pick", 20f, 4.0f) { "Only pickups within " + String.format("%.1f", it) + " km will be taken" }

        col.addView(txt("Fare (optional)", 18f, Color.BLACK, true))
        val c = card(col)
        field(c, "Min fare Rs (0 = off)", "fare", "0", true)
        field(c, "Min Rs per km (0 = off)", "rate", "0", true)

        col.addView(txt("Which kinds of work you take", 18f, Color.BLACK, true))
        val w = card(col)
        toggle(w, "Bike", "t_bike", true)
        toggle(w, "Auto", "t_auto", true)
        toggle(w, "Cab", "t_cab", true)
        w.addView(txt("Offer text me jo type likha ho, wahi ON ho to accept hoga.", 13f, Color.GRAY))

        section(col, "Go To: where you are heading", goOpen) { goOpen = !goOpen; show(1) }
        if (goOpen) {
          val g = card(col)
          toggle(g, "Go To ON", "go_on", false)
          field(g, "Areas (comma se alag: Andheri, Bandra)", "go_areas", "", false)
          g.addView(txt("Offer ke text me inme se koi naam ho tabhi accept hoga.", 13f, Color.GRAY))
        }

        col.addView(txt("Unmatched offers", 18f, Color.BLACK, true))
        val u = card(col)
        toggle(u, "Decline unmatched offers", "decline", false)
        u.addView(txt("OFF = offer ko expire hone dega. ON = Decline button dabayega (sirf Active mode me).", 13f, Color.GRAY))

        col.addView(txt("Alerts", 18f, Color.BLACK, true))
        val a = card(col)
        toggle(a, "Voice alert", "voice", true)
        toggle(a, "Sound", "snd", true)
        toggle(a, "Vibration", "vib", true)

        section(col, "Advanced settings", advOpen) { advOpen = !advOpen; show(1) }
        if (advOpen) {
          val d = card(col)
          field(d, "Gap between taps (seconds)", "gap", "2", true)
          field(d, "Accept button text", "btn", "Accept", false)
          field(d, "Decline button text", "dtext", "Skip", false)
          field(d, "Ride app package", "pkg", "com.rapido.rider", false)
        }

        col.addView(txt("Reset", 18f, Color.BLACK, true))
        col.addView(btn("Reset all rules to default", false) {
          val ed = p.edit()
          for (k in listOf("min", "max", "pick", "fare", "rate", "gap", "dtext", "go_on", "go_areas",
            "decline", "t_bike", "t_auto", "t_cab", "voice", "snd", "vib")) ed.remove(k)
          ed.apply()
          Toast.makeText(this, "Rules reset", Toast.LENGTH_SHORT).show()
          show(1)
        })
      }
      2 -> {
        heading(col, "Activity", "Recent bookings")
        val c = card(col)
        val h = p.getString("hist", "") ?: ""
        c.addView(txt(if (h.isBlank()) "Abhi kuch nahi." else h, 14f))
        col.addView(btn("Clear history", false) {
          p.edit().remove("hist").putInt("count", 0).apply(); show(2)
        })
      }
      3 -> {
        heading(col, "Help", "Setup")
        val c = card(col)
        c.addView(txt("1. Checks tab me Accessibility ON karo.\n2. Rules set karo.\n3. Pehle Monitor mode me test karo, phir Active.\n4. Status me Turn ON karo.\n\nButton text, package aur gap: Rules → Advanced settings.", 15f))
        val c3 = card(col)
        c3.addView(txt("Last screen text (parsing check ke liye)", 14f, Color.GRAY))
        val l = txt(p.getString("last", "") ?: "", 13f)
        l.setTextIsSelectable(true)
        c3.addView(l)
      }
      else -> {
        heading(col, "Checks", "What the app has been doing, and anything standing in its way")
        val ok = AcceptService.running
        val c = card(col)
        c.addView(txt(if (ok) "Everything is working" else "Accessibility is OFF", 20f, if (ok) GREEN else Color.parseColor("#C62828"), true))
        c.addView(txt(if (ok) "Accessibility service connected." else "Neeche button se Ride Filter Pro ko ON karo.", 14f, Color.GRAY))
        col.addView(txt("Mode", 18f, Color.BLACK, true))
        val m = card(col)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val act = p.getBoolean("active", false)
        val b1 = btn("Monitor", !act) { p.edit().putBoolean("active", false).apply(); show(4) }
        val b2 = btn("Active", act) { p.edit().putBoolean("active", true).apply(); show(4) }
        row.addView(b1, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(b2, LinearLayout.LayoutParams(0, WRAP, 1f))
        m.addView(row)
        m.addView(txt("Monitor: sirf match dekhta hai, tap nahi karta.\nActive: match par Accept tap karta hai.", 13f, Color.GRAY))
        col.addView(btn("Open Accessibility Settings", true) {
          startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
      }
    }
    val sv = ScrollView(this)
    sv.setBackgroundColor(Color.parseColor("#F4F6FA"))
    sv.addView(col)
    return sv
  }

  private fun show(i: Int) {
    cur = i
    container.removeAllViews()
    container.addView(build(i))
    for ((k, t) in tabs.withIndex()) {
      t.setTextColor(if (k == i) BLUE else Color.GRAY)
      t.setTypeface(null, if (k == i) Typeface.BOLD else Typeface.NORMAL)
    }
  }

  override fun onResume() { super.onResume(); if (::container.isInitialized) show(cur) }

  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    p = getSharedPreferences("cfg", MODE_PRIVATE)
    setContentView(R.layout.activity_main)
    container = findViewById(R.id.container)
    val bar = findViewById<LinearLayout>(R.id.bar)
    for ((k, n) in names.withIndex()) {
      val t = txt(n, 14f, Color.GRAY)
      t.gravity = Gravity.CENTER
      t.setOnClickListener { show(k) }
      tabs.add(t)
      bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    show(0)
  }
}
