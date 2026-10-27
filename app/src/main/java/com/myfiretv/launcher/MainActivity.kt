package com.myfiretv.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class AppItem(
    val label: String,
    val packageName: String,
    val banner: Drawable?,
    val icon: Drawable
)

data class Grad(val name: String, val top: Int, val bottom: Int)

val GRADIENTS = listOf(
    Grad("Ocean", Color.parseColor("#2563FF"), Color.parseColor("#0B3BB8")),
    Grad("Sunset", Color.parseColor("#FF7A18"), Color.parseColor("#8E1B4B")),
    Grad("Aurora", Color.parseColor("#14B8A6"), Color.parseColor("#1E3A8A")),
    Grad("Violet", Color.parseColor("#8B5CF6"), Color.parseColor("#2E1065")),
    Grad("Forest", Color.parseColor("#22C55E"), Color.parseColor("#052E16")),
    Grad("Crimson", Color.parseColor("#EF4444"), Color.parseColor("#450A0A")),
    Grad("Midnight", Color.parseColor("#1E293B"), Color.parseColor("#020617")),
    Grad("Graphite", Color.parseColor("#4B5563"), Color.parseColor("#111827"))
)

private const val PIN_SLOTS = 4
private const val REQ_STORAGE = 11

class MainActivity : Activity() {

    private lateinit var clock: TextView
    private lateinit var appName: TextView
    private lateinit var shelf: View
    private lateinit var dockRest: View
    private lateinit var gridPinned: RecyclerView
    private lateinit var gridRest: RecyclerView
    private lateinit var bg: ImageView
    private lateinit var scrim: View
    private lateinit var pinnedAdapter: AppAdapter
    private lateinit var restAdapter: AppAdapter
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val bgExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var allApps: List<AppItem> = emptyList()
    private var loading = false
    private var lastLaunch = 0L
    private var shelfExpanded = false
    private var collapsedTy = 0f

    private val tick = object : Runnable {
        override fun run() {
            clock.text = timeFormat.format(Date())
            handler.postDelayed(this, 10_000)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)

        clock = findViewById(R.id.clock)
        appName = findViewById(R.id.appName)
        shelf = findViewById(R.id.shelf)
        dockRest = findViewById(R.id.dockRest)
        gridPinned = findViewById(R.id.gridPinned)
        gridRest = findViewById(R.id.gridRest)
        bg = findViewById(R.id.bg)
        scrim = findViewById(R.id.scrim)

        pinnedAdapter = AppAdapter(
            onClick = { launch(it) },
            onFocus = { appName.text = it.label; setExpanded(false) }
        )
        restAdapter = AppAdapter(
            onClick = { launch(it) },
            onFocus = { appName.text = it.label; setExpanded(true) }
        )
        gridPinned.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        gridRest.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        gridPinned.adapter = pinnedAdapter
        gridRest.adapter = restAdapter
        gridPinned.itemAnimator = null
        gridRest.itemAnimator = null

        // Keep the second row "peeking" below the first when collapsed.
        shelf.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCollapsedOffset() }

        findViewById<View>(R.id.wifi).setOnClickListener {
            tryStart(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        findViewById<View>(R.id.settings).setOnClickListener {
            tryStart(
                Intent().setClassName(
                    "com.amazon.tv.settings.v2",
                    "com.amazon.tv.settings.v2.tv.TvSettingsActivity"
                ),
                Intent(Settings.ACTION_SETTINGS)
            )
        }
        findViewById<View>(R.id.customise).setOnClickListener { showCustomiseMenu() }
        listOf(R.id.wifi, R.id.settings, R.id.customise).forEach { id ->
            findViewById<View>(id).setOnFocusChangeListener { _, has -> if (has) setExpanded(false) }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)

        applyBackground()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (currentFocus == null) focusFirst()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while we're already running: go back to the first pinned app.
        focusFirst()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
        executor.shutdown()
        bgExecutor.shutdown()
    }

    // Back from the second row returns to the first; otherwise a launcher never "goes back".
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (shelfExpanded) focusFirst()
    }

    // ---------- shelf (two rows) ----------

    private fun updateCollapsedOffset() {
        val ty = if (dockRest.visibility == View.GONE || dockRest.height == 0) {
            0f
        } else {
            (dockRest.height - dp(52) + dp(32)).toFloat()
        }
        if (ty != collapsedTy) {
            collapsedTy = ty
            if (!shelfExpanded) shelf.translationY = ty
        }
    }

    private fun setExpanded(expand: Boolean) {
        if (shelfExpanded == expand) return
        shelfExpanded = expand
        shelf.animate()
            .translationY(if (expand) 0f else collapsedTy)
            .setDuration(220)
            .start()
    }

    private fun focusFirst() {
        val target = if (pinnedAdapter.itemCount > 0) gridPinned else gridRest
        target.post {
            target.scrollToPosition(0)
            target.post {
                target.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    // ---------- apps ----------

    private fun loadApps() {
        if (loading) return
        loading = true
        executor.execute {
            val apps = try {
                queryApps()
            } catch (e: Exception) {
                emptyList()
            }
            handler.post {
                loading = false
                allApps = apps
                refreshRows()
                if (currentFocus == null) focusFirst()
            }
        }
    }

    private fun queryApps(): List<AppItem> {
        val pm = packageManager
        val seen = HashSet<String>()
        val result = ArrayList<AppItem>()

        val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        for (category in categories) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue

                val banner: Drawable? = if (category == Intent.CATEGORY_LEANBACK_LAUNCHER) {
                    info.activityInfo.loadBanner(pm)
                        ?: info.activityInfo.applicationInfo.loadBanner(pm)
                } else null

                result.add(
                    AppItem(
                        label = info.loadLabel(pm).toString(),
                        packageName = pkg,
                        banner = banner,
                        icon = info.loadIcon(pm)
                    )
                )
            }
        }
        return result.sortedWith(compareBy({ it.banner == null }, { it.label.lowercase() }))
    }

    private fun launch(item: AppItem) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 800) return
        lastLaunch = now

        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(item.packageName)
            ?: pm.getLaunchIntentForPackage(item.packageName)
        if (intent == null) {
            Toast.makeText(this, "Can't open ${item.label}", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryStart(vararg intents: Intent) {
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Couldn't open that screen", Toast.LENGTH_SHORT).show()
    }

    // ---------- pinned apps ----------

    private fun readPinned(): List<String?> {
        val raw = prefs.getString("pinned", null) ?: return List(PIN_SLOTS) { null }
        val parts = raw.split("|")
        return List(PIN_SLOTS) { i -> parts.getOrNull(i)?.takeIf { it.isNotEmpty() } }
    }

    private fun savePinned(list: List<String?>) {
        val padded = List(PIN_SLOTS) { i -> list.getOrNull(i) }
        prefs.edit().putString("pinned", padded.joinToString("|") { it ?: "" }).apply()
    }

    private fun refreshRows() {
        if (!prefs.contains("pinned") && allApps.isNotEmpty()) {
            savePinned(allApps.take(PIN_SLOTS).map { it.packageName })
        }
        val byPkg = allApps.associateBy { it.packageName }
        val pinned = readPinned().mapNotNull { pkg -> pkg?.let { byPkg[it] } }
        val pinnedPkgs = pinned.map { it.packageName }.toSet()
        val rest = allApps.filter { it.packageName !in pinnedPkgs }

        pinnedAdapter.submit(pinned)
        restAdapter.submit(rest)
        dockRest.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        shelf.post { updateCollapsedOffset() }
    }

    private fun setPinned(slot: Int, pkg: String?) {
        val p = readPinned().toMutableList()
        if (pkg != null) {
            val j = p.indexOf(pkg)
            if (j >= 0 && j != slot) p[j] = p[slot]
        }
        p[slot] = pkg
        savePinned(p)
        refreshRows()
    }

    private fun showPinnedMenu() {
        val pinned = readPinned()
        val byPkg = allApps.associateBy { it.packageName }
        val labels = (0 until PIN_SLOTS).map { i ->
            "${i + 1}.  " + (pinned[i]?.let { byPkg[it]?.label } ?: "Empty")
        } + "Reset to default"
        AlertDialog.Builder(this)
            .setTitle("Pinned apps")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < PIN_SLOTS) {
                    chooseAppForSlot(which)
                } else {
                    prefs.edit().remove("pinned").apply()
                    refreshRows()
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun chooseAppForSlot(slot: Int) {
        val options: List<AppItem?> = listOf<AppItem?>(null) + allApps
        AlertDialog.Builder(this)
            .setTitle("Pinned app ${slot + 1}")
            .setAdapter(AppChoiceAdapter(this, options)) { _, which ->
                setPinned(slot, options[which]?.packageName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- customise menu / background ----------

    private fun showCustomiseMenu() {
        AlertDialog.Builder(this)
            .setTitle("Customise")
            .setItems(arrayOf("Change background", "Change pinned apps")) { _, which ->
                if (which == 0) showBackgroundMenu() else showPinnedMenu()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBackgroundMenu() {
        val names = GRADIENTS.map { it.name }.toMutableList()
        names.add("Photo from this device…")
        AlertDialog.Builder(this)
            .setTitle("Background")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < GRADIENTS.size) {
                    prefs.edit().putString("bg", "g:$which").apply()
                    applyBackground()
                } else {
                    pickPhoto()
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showGradient(index: Int) {
        val g = GRADIENTS[index.coerceIn(0, GRADIENTS.lastIndex)]
        bg.setImageDrawable(null)
        bg.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(g.top, g.bottom)
        )
        scrim.visibility = View.GONE
    }

    private fun applyBackground() {
        val mode = prefs.getString("bg", "g:0") ?: "g:0"
        val photoFile = File(filesDir, "wallpaper.jpg")
        if (mode == "photo" && photoFile.exists()) {
            showGradient(0) // placeholder while the photo loads
            bgExecutor.execute {
                val bmp = decodeSampled(photoFile.absolutePath, 1920, 1080, Bitmap.Config.ARGB_8888)
                handler.post {
                    if (bmp != null) {
                        bg.background = null
                        bg.setImageBitmap(bmp)
                        scrim.visibility = View.VISIBLE
                    }
                }
            }
        } else {
            showGradient(mode.removePrefix("g:").toIntOrNull() ?: 0)
        }
    }

    private fun pickPhoto() {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), REQ_STORAGE)
            return
        }
        bgExecutor.execute {
            val files = findPhotos()
            handler.post {
                if (files.isEmpty()) showMessage(
                    "No photos found",
                    "Copy a picture onto the stick first, for example from your PC with:\n\n" +
                        "adb push photo.jpg /sdcard/Pictures/\n\nThen try again."
                ) else showPhotoPicker(files)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickPhoto()
            } else {
                showMessage(
                    "Permission needed",
                    "To use your own pictures, grant storage access from your PC:\n\n" +
                        "adb shell pm grant com.myfiretv.launcher android.permission.READ_EXTERNAL_STORAGE\n\n" +
                        "Then try again."
                )
            }
        }
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun findPhotos(): List<File> {
        val seen = HashSet<String>()
        val out = ArrayList<File>()
        fun isImage(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
        }
        fun addDir(dir: File) {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && isImage(f.name) && seen.add(f.absolutePath)) out.add(f)
            }
        }
        val root = Environment.getExternalStorageDirectory()
        listOf("", "Pictures", "Download", "DCIM", "DCIM/Camera", "Wallpapers").forEach { addDir(File(root, it)) }

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DATA),
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val col = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                var n = 0
                while (c.moveToNext() && n < 80) {
                    val path = c.getString(col) ?: continue
                    val f = File(path)
                    if (f.isFile && seen.add(f.absolutePath)) {
                        out.add(f)
                        n++
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedByDescending { it.lastModified() }.take(80)
    }

    private fun showPhotoPicker(files: List<File>) {
        val rv = RecyclerView(this)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340))
        val dlg = AlertDialog.Builder(this)
            .setTitle("Choose a photo")
            .setView(rv)
            .setNegativeButton("Cancel", null)
            .create()
        rv.adapter = PhotoAdapter(files, bgExecutor, handler) { file ->
            dlg.dismiss()
            setPhoto(file.absolutePath)
        }
        dlg.show()
        dlg.window?.setLayout(dp(720), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun setPhoto(path: String) {
        Toast.makeText(this, "Setting background…", Toast.LENGTH_SHORT).show()
        bgExecutor.execute {
            val bmp = decodeSampled(path, 1920, 1080, Bitmap.Config.ARGB_8888)
            if (bmp == null) {
                handler.post {
                    Toast.makeText(this, "Couldn't load that picture", Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            try {
                FileOutputStream(File(filesDir, "wallpaper.jpg")).use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
                prefs.edit().putString("bg", "photo").apply()
            } catch (_: Exception) {
            }
            handler.post { applyBackground() }
        }
    }
}

// Decodes a downsampled bitmap (keeps memory low on small sticks) and fixes rotation.
fun decodeSampled(path: String, reqW: Int, reqH: Int, config: Bitmap.Config): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = config
            }
            val bmp = BitmapFactory.decodeFile(path, opts)
            if (bmp == null) {
                null
            } else {
                val orientation = ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val degrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees == 0f) {
                    bmp
                } else {
                    val m = Matrix().apply { postRotate(degrees) }
                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
            }
        }
    } catch (e: Throwable) {
        null
    }
}

class AppChoiceAdapter(context: Context, private val options: List<AppItem?>) :
    ArrayAdapter<AppItem?>(context, 0, options) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_choice, parent, false)
        val item = options[position]
        v.findViewById<ImageView>(R.id.choiceIcon).setImageDrawable(item?.icon)
        v.findViewById<TextView>(R.id.choiceLabel).text = item?.label ?: "Empty slot"
        return v
    }
}

class PhotoAdapter(
    private val files: List<File>,
    private val io: ExecutorService,
    private val ui: Handler,
    private val onPick: (File) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    private val cache = LruCache<String, Bitmap>(40)

    class VH(val img: ImageView) : RecyclerView.ViewHolder(img)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val d = parent.resources.displayMetrics.density
        val img = ImageView(parent.context)
        val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (112 * d).toInt())
        val m = (4 * d).toInt()
        lp.setMargins(m, m, m, m)
        img.layoutParams = lp
        val pad = (3 * d).toInt()
        img.setPadding(pad, pad, pad, pad)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.isFocusable = true
        img.isClickable = true
        img.setBackgroundResource(R.drawable.photo_frame)
        return VH(img)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = files[position]
        val path = f.absolutePath
        holder.img.tag = path
        val cached = cache.get(path)
        if (cached != null) {
            holder.img.setImageBitmap(cached)
        } else {
            holder.img.setImageDrawable(null)
            io.execute {
                val b = decodeSampled(path, 360, 200, Bitmap.Config.RGB_565)
                if (b != null) {
                    cache.put(path, b)
                    ui.post { if (holder.img.tag == path) holder.img.setImageBitmap(b) }
                }
            }
        }
        holder.img.setOnClickListener { onPick(f) }
    }

    override fun getItemCount() = files.size
}

class AppAdapter(
    private val onClick: (AppItem) -> Unit,
    private val onFocus: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppItem> = emptyList()

    fun submit(list: List<AppItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val label: TextView = view.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val radius = 12f * parent.resources.displayMetrics.density
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        v.clipToOutline = true
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val d = holder.itemView.resources.displayMetrics.density

        if (item.banner != null) {
            holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.image.setPadding(0, 0, 0, 0)
            holder.image.setImageDrawable(item.banner)
            holder.label.visibility = View.GONE
        } else {
            holder.image.scaleType = ImageView.ScaleType.FIT_CENTER
            holder.image.setPadding((52 * d).toInt(), (10 * d).toInt(), (52 * d).toInt(), (28 * d).toInt())
            holder.image.setImageDrawable(item.icon)
            holder.label.text = item.label
            holder.label.visibility = View.VISIBLE
        }

        holder.itemView.setOnClickListener {
            items.getOrNull(holder.bindingAdapterPosition)?.let(onClick)
        }

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.18f else 1f
            v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(if (hasFocus) 12f * d else 0f)
                .setDuration(140)
                .start()
            if (hasFocus) {
                items.getOrNull(holder.bindingAdapterPosition)?.let(onFocus)
                (v.parent as? RecyclerView)?.let { rv ->
                    val dx = (v.left + v.width / 2) - rv.width / 2
                    rv.smoothScrollBy(dx, 0)
                }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.itemView.translationZ = 0f
    }

    override fun getItemCount() = items.size
}
