package com.timefloat.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 字幕标记产物落盘：整屏截图 PNG + 日志式 SRT 记录（仅后台线程调用）。
 *
 * API 29+ 走 MediaStore，相册/文件管理器直接可见且无需存储权限；
 * API 26-28 降级到应用外部私有目录（Android/data/<pkg>/files），同样无权限。
 * SRT 为日志式格式：“yyyy-MM-dd HH:mm:ss：字幕”，按天一个文件逐行追加。
 */
object MarkerWriter {

    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())   // 截图文件名
    private val lineFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) // SRT 行内时间
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())          // SRT 按天分文件

    private const val DIR = "TimeFloat"

    /** 截图存 Pictures/TimeFloat，文件名如 20261107_142530_停车前.png */
    fun saveScreenshot(context: Context, bitmap: Bitmap, caption: String, timeMs: Long): Boolean {
        val name = "${fileFmt.format(Date(timeMs))}_$caption.png"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$DIR"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                ) ?: return false
                resolver.openOutputStream(uri)?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), DIR)
                dir.mkdirs()
                FileOutputStream(File(dir, name)).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveScreenshot failed", e)
            false
        }
    }

    /** 追加一行记录到当天的 timefloat_yyyyMMdd.srt（Download/TimeFloat） */
    fun appendSrtLine(context: Context, caption: String, timeMs: Long): Boolean {
        val line = "${lineFmt.format(Date(timeMs))}：$caption\n"
        val name = "timefloat_${dayFmt.format(Date(timeMs))}.srt"
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
                    arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/$DIR/"),
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
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
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
                File(dir, name).appendText(line, Charsets.UTF_8)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "appendSrtLine failed", e)
            false
        }
    }

    private const val TAG = "MarkerWriter"
}
