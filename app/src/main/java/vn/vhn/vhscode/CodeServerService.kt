package vn.vhn.vhscode

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import vn.vhn.vhscode.root.EditorHostActivity
import vn.vhn.vhscode.root.terminal.GlobalSessionsManager
import vn.vhn.vhscode.service_features.SessionsHost
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.Process
import java.util.zip.GZIPInputStream


class CodeServerService() : Service() {
    companion object {
        const val kActionStopService = "stop_service"

        const val TAG = "CodeServerService"

        @SuppressLint("SdCardPath")
        const val BASE_PATH = "/data/data/vn.vhn.vsc"
        const val ROOT_PATH = "${BASE_PATH}/files"
        const val HOME_PATH = "${ROOT_PATH}/home"
        const val TMP_PATH = "${ROOT_PATH}/tmp"
        const val PREFIX_PATH = ROOT_PATH
        const val BOOTJS = ".vsboot.js"
        const val ASSET_PREFIX = "/vscode_local_asset/"

        const val channelId = "VSCodeServer"
        const val channelName = "VSCodeServer"
        var instance: CodeServerService? = null
        private var isServerStarting = false
        private val kPrefListenOnAllInterfaces = "listenstar"
        private val kPreUseSSL = "ssl"

        fun isServerStarting(): Boolean {
            return isServerStarting
        }

        // region CodeServer setup

        fun loadZipBytes(): ByteArray? {
            System.loadLibrary("vsc-bootstrap")
            return getZip()
        }

        external fun getZip(): ByteArray?

        suspend fun extractServer(context: Context, progressChannel: Channel<Pair<Int, Int>>) {
            val userService: UserManager =
                context.getSystemService(Context.USER_SERVICE) as UserManager
            val isPrimaryUser =
                userService.getSerialNumberForUser(android.os.Process.myUserHandle()) == 0L
            if (!isPrimaryUser) {
                AlertDialog.Builder(context).setTitle(R.string.error_title)
                    .setMessage(R.string.error_not_primary_user_message)
                    .setOnDismissListener(DialogInterface.OnDismissListener { _: DialogInterface? ->
                        System.exit(
                            0
                        )
                    }).setPositiveButton(android.R.string.ok, null).show()
                return
            }
            with(context.getFileStreamPath("code-server")) {
                if (exists()) deleteRecursively()
            }
            for (file in listOf("libc_android24++_shared.so", "node")) {
                with(context.getFileStreamPath(file)) {
                    if (exists()) delete()
                }
            }
            extractTarGz(
                ByteArrayInputStream(loadZipBytes()),
                File(ROOT_PATH),
                progressChannel
            )

            //ensure exec permission of bash scripts in code-server
            context.getFileStreamPath("code-server")
                .walk()
                .filter { it.name.matches(".+\\.sh\$".toRegex()) }
                .forEach { it.setExecutable(true) }
            context.getFileStreamPath("node").setExecutable(true)
        }

        fun copyRawResource(context: Context, resource_id: Int, output_path: String) {
            val inStream = context.resources.openRawResource(resource_id)
            val targetFile = File(output_path)
            if (targetFile.parentFile?.exists() == false) {
                targetFile.parentFile?.mkdirs()
            }
            val outStream = FileOutputStream(targetFile)

            val bufSize = 4096
            val buffer = ByteArray(bufSize)
            while (true) {
                val cnt = inStream.read(buffer)
                if (cnt <= 0) break;
                outStream.write(buffer, 0, cnt)
            }

            inStream.close()
            outStream.close()
        }

        suspend fun extractTarGz(
            archiveFile: ByteArrayInputStream,
            outputDir: File,
            progressChannel: Channel<Pair<Int, Int>>,
        ) {
            val bufSize: Int = 4096
            val buffer = ByteArray(bufSize)

            var total = 0

            archiveFile.mark(0)
            var reader = TarArchiveInputStream(GZIPInputStream(archiveFile))
            var currentEntry = reader.nextTarEntry
            while (currentEntry != null) {
                total += 1
                currentEntry = reader.nextTarEntry
            }

            progressChannel.send(Pair(0, total))

            var currentFileIndex = 0
            archiveFile.reset()
            reader = TarArchiveInputStream(GZIPInputStream(archiveFile))
            currentEntry = reader.nextTarEntry
            val links: ArrayList<Pair<String, String>> = ArrayList(50)

            while (currentEntry != null) {
                currentFileIndex++
                progressChannel.send(Pair(currentFileIndex, total))
                val outputFile = File(outputDir.absolutePath + "/" + currentEntry.name)
                if (!outputFile.parentFile!!.exists()) {
                    outputFile.parentFile!!.mkdirs()
                }
                if (currentEntry.isDirectory) {
                    outputFile.mkdirs()
                } else if (currentEntry.isSymbolicLink) {
                    Log.d("SYMLINK", currentEntry.linkName + " <- " + outputFile.absolutePath)
                    Os.symlink(currentEntry.linkName, outputFile.absolutePath)
                } else if (currentEntry.isLink) {
                    links.add(
                        Pair(
                            outputDir.absolutePath + "/" + currentEntry.linkName,
                            outputFile.absolutePath
                        )
                    )
                } else {
                    val outStream = FileOutputStream(outputFile)
                    while (true) {
                        val size = reader.read(buffer)
                        if (size <= 0) break;
                        outStream.write(buffer, 0, size)
                    }
                    outStream.close()
                }
                currentEntry = reader.nextTarEntry
            }
            for (link in links) {
                Log.d("Link", link.first + " >> " + link.second)
                File(link.first).copyTo(File(link.second))
            }
        }
        // endregion

        fun homePath(ctx: Context): String {
            return ContextCompat.getExternalFilesDirs(ctx, null)[0].absolutePath
        }


        private fun addToEnvIfPresent(
            environment: MutableList<String>,
            name: String,
        ) {
            val value = System.getenv(name)
            if (value != null) {
                environment.add("$name=$value")
            }
        }

        fun buildEnv(): Array<String> {
            val envHome = ROOT_PATH

            val env = mutableListOf<String>()
            env.add("TERM=xterm-256color")
            env.add("HOME=${HOME_PATH}")
            env.add("LD_LIBRARY_PATH=${envHome}:${envHome}/usr/lib")
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                env.add("LD_PRELOAD=${envHome}/android_23.so")
            }
            env.add("PATH=${envHome}/bin:${envHome}/usr/bin:${envHome}/usr/bin/applets")
            env.add("NODE_OPTIONS=\"--require=${envHome}/globalinject.js\"")

            env.add("BOOTCLASSPATH=" + System.getenv("BOOTCLASSPATH"))
            env.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"))
            env.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"))
            env.add("EXTERNAL_STORAGE=" + System.getenv("EXTERNAL_STORAGE"))
            addToEnvIfPresent(env, "ANDROID_RUNTIME_ROOT")
            addToEnvIfPresent(env, "ANDROID_TZDATA_ROOT")
            env.add("LANG=en_US.UTF-8")
            env.add("TMPDIR=${PREFIX_PATH}/tmp")
            env.add("PREFIX=${PREFIX_PATH}")
            env.add("SHELL=${PREFIX_PATH}/usr/bin/bash")
            env.add("TERMUX_PKG_NO_MIRROR_SELECT=1")

            Log.d(TAG, "env = " + env.toString())

            return env.toTypedArray()
        }
    }

    private val mGlobalSessionsManager = GlobalSessionsManager()
    public val globalSessionsManager = mGlobalSessionsManager
    private val mSessionHost = SessionsHost(this, mGlobalSessionsManager)
    public val sessionsHost = mSessionHost

    /** This service is only bound from inside the same process and never uses IPC.  */
    internal class LocalBinder(self: CodeServerService) : Binder() {
        public val service = self
    }

    private val mBinder: IBinder = LocalBinder(this)

    override fun onBind(intent: Intent): IBinder {
        mSessionHost.onBinding()
        return mBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        mSessionHost.onUnbinding()
        mGlobalSessionsManager.activity = null
        return false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mSessionHost.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.apply {
            if (action == kActionStopService) {
                actionStopService()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun actionStopService() {
        mSessionHost.mWantsToStop = true
        mSessionHost.killAllSessions()
        stopForeground(true)
        stopSelf()
    }

    fun updateNotification() {
        val resultIntent = Intent(this, EditorHostActivity::class.java)
        val stackBuilder: TaskStackBuilder = TaskStackBuilder.create(this)
        stackBuilder.addNextIntentWithParentStack(resultIntent)
        val resultPendingIntent: PendingIntent =
            stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)

        val stopIntent = Intent(this, CodeServerService::class.java)
        stopIntent.action = kActionStopService
        val pendingStopIntent = PendingIntent.getService(this, 0, stopIntent, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_LOW
            )
            chan.lightColor = Color.BLUE
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
        }

        var ext = ""
        if (mSessionHost.hasWakeLock) ext += "with wake-lock"
        if (mSessionHost.hasWifiLock) {
            if (ext.isNotEmpty()) ext += ", "
            ext += "with wifi-lock"
        }
        if (ext.isNotEmpty()) ext = ", $ext"

        val notificationBuilder =
            NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.logo_b)
            .setContentTitle("VHEditor is running, ${mGlobalSessionsManager.sessionsHost?.mTermuxSessions?.size} terminal, ${mGlobalSessionsManager.sessionsHost?.mCodeServerSessions?.size} editor session(s)${ext}.")
            .setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(resultPendingIntent)
            .addAction(
                R.drawable.icon_trash,
                getString(R.string.stop_server),
                pendingStopIntent
            )
            .build()
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(1, notificationBuilder.build())
        startForeground(1, notification)
    }
}
