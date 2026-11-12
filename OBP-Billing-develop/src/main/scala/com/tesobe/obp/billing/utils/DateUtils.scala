package com.tesobe.obp.billing.utils

import java.text.{ParseException, SimpleDateFormat}
import java.util.{Date, TimeZone}

/** Date helpers. All formatting and day boundaries use UTC, matching OBP-API metrics. */
object DateUtils {
  private val oneDayMs: Long = 24 * 60 * 60 * 1000

  private def utc(pattern: String): SimpleDateFormat = {
    val f = new SimpleDateFormat(pattern)
    f.setTimeZone(TimeZone.getTimeZone("UTC"))
    f
  }

  private val dateWithMsFormat = utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
  private val dateTimeFormat = utc("yyyy-MM-dd HH:mm:ss")
  private val dateFormat = utc("yyyy-MM-dd")

  /** Adds `addDays` days and truncates the result to midnight UTC. */
  def addDate(date: Date, addDays: Int): Date =
    toDate(new Date(date.getTime + oneDayMs * addDays))

  /** Truncates to midnight UTC. */
  def toDate(date: Date): Date = dateFormat.parse(dateFormat.format(date))

  /**
   * The billing period `fromDate` .. `toDate` split at every midnight UTC in between.
   * The first element is `fromDate`, the last is `toDate`.
   */
  def buildRangeTime(fromDate: Date, toDate: Date = new Date()): Seq[Date] = {
    val secondDate: Date = addDate(fromDate, 1)
    val range = secondDate.getTime to (toDate.getTime, oneDayMs)
    fromDate +: range.map(new Date(_)) :+ toDate
  }

  def isTimeStr(str: String): Boolean =
    str != null && (try {
      dateWithMsFormat.parse(str)
      true
    } catch {
      case _: ParseException => false
    })

  def parseTime(str: String): Date = dateWithMsFormat.parse(str)
  def toTimeStr(date: Date): String = dateWithMsFormat.format(date)
  def parseDate(str: String): Date = dateFormat.parse(str)
  def toDateStr(date: Date): String = dateFormat.format(date)
  def toDateTime(date: Date): String = dateTimeFormat.format(date)
  def parseDateTime(str: String): Date = dateTimeFormat.parse(str)
}
