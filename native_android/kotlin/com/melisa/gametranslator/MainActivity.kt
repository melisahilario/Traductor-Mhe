package com.melisa.gametranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val channelName = "gametranslator/native"
    private val reqProjection = 1001
    private val reqNotifications = 1002
    private var pendingResult: MethodChannel.Result? = null

    private fun prefs(): SharedPreferences =
        getSharedPreferences("gt_prefs", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotifications)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasOverlayPermission" -> result.success(Settings.canDrawOverlays(this))

                    "requestOverlayPermission" -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                        result.success(null)
                    }

                    "getSettings" -> {
                        val p = prefs()
                        result.success(
                            mapOf(
                                "source" to (p.getString("source", "en") ?: "en"),
                                "target" to (p.getString("target", "es") ?: "es"),
                                "mode" to (p.getString("mode", "overlay") ?: "overlay")
                            )
                        )
                    }

                    "saveSettings" -> {
                        prefs().edit()
                            .putString("source", call.argument<String>("source") ?: "en")
                            .putString("target", call.argument<String>("target") ?: "es")
                            .putString("mode", call.argument<String>("mode") ?: "overlay")
                            .apply()
                        result.success(true)
                    }

                    "downloadModel" -> {
                        val source = call.argument<String>("source") ?: "en"
                        val target = call.argument<String>("target") ?: "es"
                        val translator = buildTranslator(source, target)
                        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                            .addOnSuccessListener {
                                translator.close()
                                result.success("ok")
                            }
                            .addOnFailureListener { e ->
                                translator.close()
                                result.error("DOWNLOAD", e.message, null)
                            }
                    }

                    "startOverlay" -> startProjection(result)

                    "stopOverlay" -> {
                        stopService(Intent(this, OverlayService::class.java))
                        result.success(true)
                    }

                    "isRunning" -> result.success(OverlayService.running)

                    else -> result.notImplemented()
                }
            }
    }

    private fun startProjection(result: MethodChannel.Result) {
        if (!Settings.canDrawOverlays(this)) {
            result.error("NO_OVERLAY", "Falta el permiso para mostrar sobre otras apps", null)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pendingResult = result
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == reqProjection) {
            val res = pendingResult
            pendingResult = null
            if (resultCode == Activity.RESULT_OK && data != null) {
                val svc = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                    putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(OverlayService.EXTRA_DATA, data)
                }
                ContextCompat.startForegroundService(this, svc)
                res?.success(true)
            } else {
                res?.success(false)
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }
}

