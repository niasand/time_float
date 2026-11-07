package com.timefloat.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 字幕标记落盘（仅后台线程调用）：向固定的 road_test_srt.srt 逐行追加
 * 日志式记录 “yyyy-MM-dd HH:mm:ss：字幕”，文件不存在则自动创建。
 *
 * API 29+ 走 MediaStore（Download/TimeFloat，文件管理器可见且无需存储权限）；
 * API 26-28 降级到应用外部私有目录（Android/data/<pkg>/files），同样无权限。
 */
object MarkerWriter {

    private val lineFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private const val DIR = "TimeFloat"
    private const val FILE_NAME = "road_test_srt.srt"

    fun appendSrtLine(context: Context, caption: String, timeMs: Long): Boolean {
        val line = "${lineFmt.format(Date(timeMs))}：$caption\n"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val collection =
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                // MediaProvider 不支持 "wa" 追加写，存量内容用读-改-写
                val existingId = resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(FILE_NAME, "${Environment.DIRECTORY_DOWNLOADS}/$DIR/"),
                    null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

                if (existingId != null) {
                    val uri = ContentUris.withAppendedId(collection, existingId)
                    val prev = resolver.openInputStream(uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    resolver.openOutputStream(uri, "w")?.use {
                        it.write((prev + line).toByteArray(Charsets.UTF_8))
                    }
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}/$DIR"
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(collection, values) ?: return false
                    resolver.openOutputStream(uri)?.use {
                        it.write(line.toByteArray(Charsets.UTF_8))
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DIR)
                dir.mkdirs()
                File(dir, FILE_NAME).appendText(line, Charsets.UTF_8)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "appendSrtLine failed", e)
            false
        }
    }

    private const val TAG = "MarkerWriter"
}
