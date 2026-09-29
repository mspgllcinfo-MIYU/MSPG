package com.mspg.poicat.album

import androidx.exifinterface.media.ExifInterface
import com.mspg.poicat.data.Photo
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * アルバム画面専用の表示名(YYMMDD-連番)を計算するだけの、独立した読み取り専用
 * ヘルパー。[Photo]/実ファイル/Drive/Firestoreへの書き込みは一切行わない — この
 * オブジェクトが返すのはメモリ上のMap<photoId, 表示名>だけで、Photo.filePath/
 * Photo.caption等の既存フィールドはどれも参照するだけで書き換えない。
 *
 * 呼び出し元([AlbumScreen])は写真一覧を読み込む(reload())たびに1回だけこの関数を
 * 呼び、結果をComposeのstateとして保持すること — 再コンポジションのたびにEXIFを
 * 読み直す実装にはしない。
 *
 * 【日付の決定】EXIF DateTimeOriginal → EXIF DateTime → [Photo.addedAt] の優先順。
 * 「今日の日付」を新たに採用することはしない — addedAtは実際にこの行がINSERTされた
 * 実在のタイムスタンプであり、架空の値ではない。
 *
 * 【同日内の連番】撮影時刻(EXIF優先、無ければaddedAt) → 最終タイブレークとして
 * [Photo.id]、の順で安定ソートする。同じ入力からは常に同じ結果になる純粋関数の
 * ため、再起動・再表示で番号が変わることはない。
 */
object AlbumPhotoDisplayName {

    private data class SortKey(val date: LocalDate, val timeMillis: Long, val photoId: Long)

    /**
     * [photos]から YYMMDD-連番 の表示名を計算する。ファイルI/O(EXIF読み取り)を
     * 伴うため[Dispatchers.IO]上で実行する。
     */
    suspend fun computeDisplayNames(photos: List<Photo>): Map<Long, String> = withContext(Dispatchers.IO) {
        val keyed = photos.map { it to sortKeyFor(it) }
        val byDate = keyed.groupBy { it.second.date }
        val result = mutableMapOf<Long, String>()
        for ((date, entries) in byDate) {
            val sorted = entries.sortedWith(compareBy({ it.second.timeMillis }, { it.second.photoId }))
            val datePrefix = "%02d%02d%02d".format(date.year % 100, date.monthValue, date.dayOfMonth)
            sorted.forEachIndexed { index, (photo, _) ->
                result[photo.id] = "$datePrefix-%02d".format(index + 1)
            }
        }
        result
    }

    private fun sortKeyFor(photo: Photo): SortKey {
        val exifMillis = readExifCaptureMillis(photo.filePath)
        val effectiveMillis = exifMillis ?: photo.addedAt
        val date = Instant.ofEpochMilli(effectiveMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return SortKey(date, effectiveMillis, photo.id)
    }

    /**
     * EXIF DateTimeOriginal→DateTimeの順で読み取るだけの読み取り専用処理。
     * [com.mspg.poicat.recovery.AlbumRecoveryScanner]のreadExifDateTime()と同じ
     * 考え方(該当タグを文字列のまま読むだけ)を参考にしつつ、Scanner自体のコードは
     * 一切参照・変更しない独立した実装 — ここではさらにepoch millisへ変換して
     * 日付の並べ替えに使う。
     */
    private fun readExifCaptureMillis(filePath: String): Long? {
        val raw = runCatching {
            val exif = ExifInterface(filePath)
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        }.getOrNull() ?: return null
        // EXIFの日時は "yyyy:MM:dd HH:mm:ss" 固定形式(ISO 8601ではない)。
        return runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time }.getOrNull()
    }
}
