package com.mspg.poicat.ocr

import com.mspg.poicat.brain.toEpochMilli
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * #POI画像OCR: OCR原文から抽出できた情報。[title]/[dateTime]/[locationText]は
 * 確実に読み取れなかった場合は必ずnull — 画像に存在しない情報を推測・補完・捏造
 * しない([TravelDocumentParser]/[OcrGeminiAnalyzer]共通の約束事)。
 */
data class ParsedTravelInfo(
    val title: String?,
    val dateTime: Long?,
    val locationText: String?,
)

/**
 * #POI画像OCR: OCR原文から、正規表現だけで機械的に確実に読み取れる範囲だけを抽出する
 * ローカルparser。AIを一切使わない — 呼び出し元([OcrIntake])は、ここでtitleまで
 * 組み立てられなかった場合にだけ、OCR原文(画像は渡さない)を[OcrGeminiAnalyzer]へ
 * 追加で渡す。
 *
 * 対応するのは航空券/新幹線チケット等によくある「YYYY年MM月DD日」「YYYY/MM/DD」の
 * ような日付表記、「HH:MM」の時刻表記、「NH248」のような2英字+3〜4桁の便名、
 * 「福岡→羽田」のような「→」区切りの出発地/到着地表記のみ。ホテル名・イベント名・
 * 病院名・住所等、パターンが定型でないものはここでは判定せず、常にnullを返す
 * (それらは[OcrGeminiAnalyzer]側の役割)。
 */
object TravelDocumentParser {
    private val datePattern = Regex("""(\d{4})[年/-](\d{1,2})[月/-](\d{1,2})""")
    private val timePattern = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")
    private val flightPattern = Regex("""\b([A-Z]{2})\s?(\d{3,4})\b""")
    private val routePattern = Regex("""([\p{L}]{2,})\s*(?:→|->|~>)\s*([\p{L}]{2,})""")

    // 画像に時刻が全く書かれていない(日付のみの予約票等)場合の時刻部分。BBの
    // DateTimeParser(brain/DateTimeParser.kt)が「日時未指定→09:00」で使っている
    // 既存の全社的な既定値と同じ数値を、この新しい別ファイルでも独立に採用する
    // (DateTimeParser自体は変更しない) — 確認画面で必ずユーザーが目にし、
    // 間違っていれば編集できるため、捏造ではなく単なる初期表示値として扱う。
    private val DEFAULT_HOUR_WHEN_TIME_UNKNOWN = LocalTime.of(9, 0)

    fun parse(ocrText: String): ParsedTravelInfo {
        val date = datePattern.find(ocrText)?.let { m ->
            runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull()
        }
        val time = timePattern.find(ocrText)?.let { m ->
            runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
        }
        val dateTime = date?.let { LocalDateTime.of(it, time ?: DEFAULT_HOUR_WHEN_TIME_UNKNOWN).toEpochMilli() }

        val flight = flightPattern.find(ocrText)?.value?.replace(" ", "")
        val route = routePattern.find(ocrText)
        val title = when {
            route != null && flight != null -> "${route.groupValues[1]} → ${route.groupValues[2]} $flight"
            route != null -> "${route.groupValues[1]} → ${route.groupValues[2]}"
            else -> null
        }

        return ParsedTravelInfo(title = title, dateTime = dateTime, locationText = null)
    }
}
