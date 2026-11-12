package com.tesobe.obp.billing.utils

object StringUtils {

  def isEmptyStr(str: String): Boolean = str == null || str.trim.isEmpty

  def isNotEmptyStr(str: String): Boolean = !isEmptyStr(str)

  /** Null-safe trim with line breaks collapsed to spaces, for text sent to Invoice Ninja. */
  def cleanText(str: String): String =
    Option(str).map(_.trim.replaceAll("\\r\\n|\\r|\\n", " ")).getOrElse("")
}
