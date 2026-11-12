package com.tesobe.obp.billing.utils

import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}

import net.liftweb.json.{DateFormat, DefaultFormats, Formats}

import scala.util.Try

/** lift-json formats that accept OBP dates with or without milliseconds, always in UTC. */
object JsonFormats {
  private val patterns = Seq("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")

  private def formatter(pattern: String): SimpleDateFormat = {
    val f = new SimpleDateFormat(pattern)
    f.setTimeZone(TimeZone.getTimeZone("UTC"))
    f
  }

  val formats: Formats = new DefaultFormats {
    override val dateFormat: DateFormat = new DateFormat {
      def parse(s: String): Option[Date] =
        patterns.iterator.flatMap(p => Try(formatter(p).parse(s)).toOption).nextOption()

      def format(d: Date): String = formatter(patterns.head).format(d)

    }
  }
}
