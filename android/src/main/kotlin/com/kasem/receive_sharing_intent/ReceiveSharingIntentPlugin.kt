package com.kasem.receive_sharing_intent

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.NewIntentListener
import io.flutter.plugin.common.PluginRegistry.Registrar
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection

private const val MESSAGES_CHANNEL = "receive_sharing_intent/messages"
private const val EVENTS_CHANNEL_MEDIA = "receive_sharing_intent/events-media"
private const val EVENTS_CHANNEL_TEXT = "receive_sharing_intent/events-text"

class ReceiveSharingIntentPlugin : FlutterPlugin, ActivityAware, MethodCallHandler,
        EventChannel.StreamHandler, NewIntentListener {

    private var permissionResultListener: PluginRegistry.RequestPermissionsResultListener?=null
    private var initialMedia: JSONArray? = null
    private var latestMedia: JSONArray? = null

    private var initialText: String? = null
    private var latestText: String? = null
    private var permissionUri: Uri? = null

    private var eventSinkMedia: EventChannel.EventSink? = null
    private var eventSinkText: EventChannel.EventSink? = null

    private var binding: ActivityPluginBinding? = null
    private lateinit var applicationContext: Context

    private fun setupCallbackChannels(binaryMessenger: BinaryMessenger) {
        val mChannel = MethodChannel(binaryMessenger, MESSAGES_CHANNEL)
        mChannel.setMethodCallHandler(this)

        val eChannelMedia = EventChannel(binaryMessenger, EVENTS_CHANNEL_MEDIA)
        eChannelMedia.setStreamHandler(this)

        val eChannelText = EventChannel(binaryMessenger, EVENTS_CHANNEL_TEXT)
        eChannelText.setStreamHandler(this)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        setupCallbackChannels(binding.binaryMessenger)

    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        when (arguments) {
            "media" -> eventSinkMedia = events
            "text" -> eventSinkText = events
        }
    }

    override fun onCancel(arguments: Any?) {
        when (arguments) {
            "media" -> eventSinkMedia = null
            "text" -> eventSinkText = null
        }
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    //
    // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
    // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
    // depending on the user's project. onAttachedToEngine or registerWith must both be defined
    // in the same class.
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val instance = ReceiveSharingIntentPlugin()
            instance.applicationContext = registrar.context()
            instance.setupCallbackChannels(registrar.messenger())
        }
    }


    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getInitialMedia" -> result.success(initialMedia?.toString())
            "getInitialText" -> result.success(initialText)
            "reset" -> {
                initialMedia = null
                latestMedia = null
                initialText = null
                latestText = null
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun handleIntent(intent: Intent, initial: Boolean) {
        permissionUri=null
        when {
            (intent.type?.startsWith("text") != true)
                    && (intent.action == Intent.ACTION_SEND
                    || intent.action == Intent.ACTION_SEND_MULTIPLE) -> { // Sharing images or videos

                val value = getMediaUris(intent)
                if (initial) initialMedia = value
                latestMedia = value
                eventSinkMedia?.success(latestMedia?.toString())
            }
            (intent.type == null || intent.type?.startsWith("text") == true)
                    && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) -> { // Sharing text
                var uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri==null){
                    uri=intent.data;
                }
                // 进行权限请求
                val value: String?
                if(uri!=null){
                    // 这个会在下面进行解释
                    // 进行权限请求
                    Log.e("","getParcelableExtra")
                    val permission = ActivityCompat.checkSelfPermission(
                            applicationContext,
                            READ_EXTERNAL_STORAGE
                    )
                    if (permission != PERMISSION_GRANTED) {
                        if (permissionResultListener==null){
                            permissionResultListener= PluginRegistry.RequestPermissionsResultListener { requestCode, permissions, grantResults ->
                                Log.e("","onRequestPermissionsResult")
                                val permission = ActivityCompat.checkSelfPermission(
                                        applicationContext,
                                        READ_EXTERNAL_STORAGE
                                )
                                if (permission == PERMISSION_GRANTED) {
                                    if (permissionUri!=null){
                                        Log.e("","onRequestPermissionsResult　readCSV")
                                        eventSinkText?.success(readCSV(permissionUri))
                                        permissionUri=null;
                                    }
                                }
                                true
                            }
                        }
                        this.binding?.addRequestPermissionsResultListener(permissionResultListener!!)
                        permissionUri=uri
                        val permissionStorage: Array<String> = arrayOf(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE)
                        ActivityCompat.requestPermissions(
                                this.binding!!.activity,
                                permissionStorage,
                                1
                        )
                        return
                    }
                    // 获取Intent中携带的数据
                    value = readCSV(uri)
                    permissionUri=null
                }else{
                   value  = intent.getStringExtra(Intent.EXTRA_TEXT)
                }
                if (initial) initialText = value
                latestText = value
                eventSinkText?.success(latestText)
            }
            intent.action == Intent.ACTION_VIEW -> { // Opening URL
                val value = intent.dataString
                if (initial) initialText = value
                latestText = value
                eventSinkText?.success(latestText)
            }
        }
    }

    private fun readCSV(uri: Uri?): String? {
        val resolver = applicationContext.contentResolver
        if (uri != null) {
            resolver.openInputStream(uri).use {
                val reader = it?.reader()
                val fileContent = reader?.readText()
                if (fileContent != null) {
                    Log.e("", fileContent)
                }
              return fileContent
            }
        }
        return null
    }


    private fun getMediaUris(intent: Intent?): JSONArray? {
        if (intent == null) return null

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val path = uri?.let{ FileDirectory.getAbsolutePath(applicationContext, it) }
                if (path != null) {
                    val type = getMediaType(path)
                    val thumbnail = getThumbnail(path, type)
                    val duration = getDuration(path, type)
                    JSONArray().put(
                            JSONObject()
                                    .put("path", path)
                                    .put("type", type.ordinal)
                                    .put("thumbnail", thumbnail)
                                    .put("duration", duration)
                    )
                } else null
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                val value = uris?.mapNotNull { uri ->
                    val path = FileDirectory.getAbsolutePath(applicationContext, uri)
                            ?: return@mapNotNull null
                    val type = getMediaType(path)
                    val thumbnail = getThumbnail(path, type)
                    val duration = getDuration(path, type)
                    return@mapNotNull JSONObject()
                            .put("path", path)
                            .put("type", type.ordinal)
                            .put("thumbnail", thumbnail)
                            .put("duration", duration)
                }?.toList()
                if (value != null) JSONArray(value) else null
            }
            else -> null
        }
    }

    private fun getMediaType(path: String?): MediaType {
        val mimeType = URLConnection.guessContentTypeFromName(path)
        return when {
            mimeType?.startsWith("image") == true -> MediaType.IMAGE
            mimeType?.startsWith("video") == true -> MediaType.VIDEO
            else -> MediaType.FILE
        }
    }

    private fun getThumbnail(path: String, type: MediaType): String? {
        if (type != MediaType.VIDEO) return null // get video thumbnail only

        val videoFile = File(path)
        val targetFile = File(applicationContext.cacheDir, "${videoFile.name}.png")
        val bitmap = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
                ?: return null
        FileOutputStream(targetFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return targetFile.path
    }

    private fun getDuration(path: String, type: MediaType): Long? {
        if (type != MediaType.VIDEO) return null // get duration for video only
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        retriever.release()
        return duration
    }



    enum class MediaType {
        IMAGE, VIDEO, FILE;
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addOnNewIntentListener(this)
        handleIntent(binding.activity.intent, true)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        binding?.removeOnNewIntentListener(this)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivity() {
        binding?.removeOnNewIntentListener(this)
    }

    override fun onNewIntent(intent: Intent): Boolean {
        handleIntent(intent, false)
        return false
    }

}
