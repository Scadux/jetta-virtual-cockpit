package com.jetta.virtualcockpit.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * ContentProvider mínimo para entregar o APK baixado ao instalador do
 * Android. Substitui o FileProvider do AndroidX (o projeto não usa
 * dependências externas) e só serve o arquivo `update.apk` do cache.
 */
class UpdateFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.jetta.virtualcockpit.updateprovider"
        const val APK_PATH = "update.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        fun uriForFile(@Suppress("UNUSED_PARAMETER") file: File): Uri =
            Uri.parse("content://$AUTHORITY/$APK_PATH")
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = APK_MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("Sem contexto")
        val file = File(ctx.cacheDir, APK_PATH)
        if (!file.exists()) throw FileNotFoundException("APK de atualização não encontrado")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val file = File(ctx.cacheDir, APK_PATH)
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        if (file.exists()) {
            cursor.addRow(arrayOf<Any>(APK_PATH, file.length()))
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
