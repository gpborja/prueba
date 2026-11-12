package com.tesobe.obp.billing.utils

import com.tesobe.obp.billing.utils.DateUtils._

class DateUtilsSuite extends munit.FunSuite {

  test("time strings round-trip in UTC") {
    val s = "2026-03-01T13:45:10.123Z"
    assertEquals(toTimeStr(parseTime(s)), s)
    assert(isTimeStr(s))
    assert(!isTimeStr("2026-03-01"))
    assert(!isTimeStr(null))
  }

  test("addDate truncates to midnight UTC") {
    val d = addDate(parseTime("2026-03-01T13:45:10.123Z"), 30)
    assertEquals(toTimeStr(d), "2026-03-31T00:00:00.000Z")
  }

  test("buildRangeTime splits the period at each UTC midnight") {
    val from = parseTime("2026-03-01T13:45:10.123Z")
    val to = parseTime("2026-03-04T02:00:00.000Z")
    assertEquals(buildRangeTime(from, to).map(toTimeStr).toList, List(
      "2026-03-01T13:45:10.123Z",
      "2026-03-02T00:00:00.000Z",
      "2026-03-03T00:00:00.000Z",
      "2026-03-04T00:00:00.000Z",
      "2026-03-04T02:00:00.000Z"))
  }

  test("buildRangeTime within a single day has just the two endpoints") {
    val from = parseTime("2026-03-01T01:00:00.000Z")
    val to = parseTime("2026-03-01T02:00:00.000Z")
    assertEquals(buildRangeTime(from, to).toList, List(from, to))
  }
}
