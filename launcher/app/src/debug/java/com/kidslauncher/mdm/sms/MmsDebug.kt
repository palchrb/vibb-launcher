package com.kidslauncher.mdm.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.kidslauncher.mdm.calls.MmscRule
import java.net.HttpURLConnection
import java.net.URL

/**
 * Debug build: the emulator's fake MMS server, set by the debug receiver (src/debug). Two things the AVD can't do:
 * - its SIM's carrier config has MMS off (`enabledMMS=false`: the platform's MMS service refuses every download and
 *   send with error 7) - with [standIn] set, the platform's part is played here: the download fetched over HTTP and
 *   written through our own `MmsFileProvider` URI (its capped pipe), the send-req POSTed to [standIn], and the result
 *   sent to the same PendingIntent the platform would use (`MmsResultReceiver`);
 * - no carrier MMS server is on the policy's list - [hosts] adds one in this process's memory.
 * The release build's twin (src/release) has none of it.
 */
internal object MmsDebug {
    @Volatile
    var hosts: List<MmscRule> = emptyList()

    /** The fake MMSC's URL for sends; non-null = stand in for the platform's MMS service. */
    @Volatile
    var standIn: String? = null

    fun mmscRules(): List<MmscRule> = hosts

    fun download(context: Context, location: String, uri: Uri, result: PendingIntent): Boolean {
        if (standIn == null) return false
        Thread {
            var code = Activity.RESULT_OK
            var http = 0
            try {
                val connection = URL(location).openConnection() as HttpURLConnection
                http = connection.responseCode
                if (http != 200) {
                    code = 4 // MMS_ERROR_HTTP_FAILURE
                } else {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stand-in download: ${e.javaClass.simpleName} ${e.message}")
                code = 3 // MMS_ERROR_UNABLE_CONNECT_MMS
            }
            Log.i(TAG, "stand-in download of $location: result $code, http $http")
            result.send(context, code, Intent().putExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, http))
        }.start()
        return true
    }

    fun send(context: Context, uri: Uri, result: PendingIntent): Boolean {
        val target = standIn ?: return false
        Thread {
            var code = Activity.RESULT_OK
            var answer: ByteArray? = null
            try {
                val pdu = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val connection = URL(target).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/vnd.wap.mms-message")
                connection.outputStream.use { it.write(pdu) }
                if (connection.responseCode != 200) code = 4
                answer = connection.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(TAG, "stand-in send: ${e.javaClass.simpleName} ${e.message}")
                code = 3
            }
            Log.i(TAG, "stand-in send to $target: result $code, ${answer?.size ?: 0} bytes back")
            result.send(context, code, Intent().putExtra(SmsManager.EXTRA_MMS_DATA, answer))
        }.start()
        return true
    }

    private const val TAG = "MmsDebug"
}
