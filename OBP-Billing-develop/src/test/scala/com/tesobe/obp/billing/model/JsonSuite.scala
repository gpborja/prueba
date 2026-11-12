package com.tesobe.obp.billing.model

import com.tesobe.obp.billing.utils.JsonFormats
import com.tesobe.obp.billing.utils.DateUtils.toTimeStr
import net.liftweb.json
import net.liftweb.json.Formats

class JsonSuite extends munit.FunSuite {
  implicit val formats: Formats = JsonFormats.formats

  test("consumer JSON with extra fields, a missing creator and a seconds-only date extracts") {
    val body =
      """{"consumers":[{
        |  "consumer_id":"abc","consumer_key":"k","app_name":"App","app_type":"Web",
        |  "description":"d","developer_email":"e@example.org","company":"Tesobe",
        |  "redirect_url":"https://example.org","certificate_pem":"",
        |  "enabled":true,"created":"2024-05-06T07:08:09Z","logo_url":null
        |}]}""".stripMargin
    val consumers = (json.parse(body) \ "consumers").extract[List[Consumer]]
    assertEquals(consumers.map(_.consumer_id), List("abc"))
    assertEquals(consumers.head.created_by_user, None)
    assertEquals(toTimeStr(consumers.head.created), "2024-05-06T07:08:09.000Z")
  }

  test("dates with milliseconds extract too") {
    val c = json.parse("""{"created":"2024-05-06T07:08:09.123Z"}""").extract[Map[String, java.util.Date]]
    assertEquals(toTimeStr(c("created")), "2024-05-06T07:08:09.123Z")
  }

  test("aggregate metrics extract from list and object shapes") {
    val metric = """{"count":7076,"average_response_time":65.21,"minimum_response_time":1.0,"maximum_response_time":9039.0,"distinct_user_count":41}"""
    assertEquals(json.parse(s"[$metric]").extract[List[AggregateMetric]].head.count, 7076)
    assertEquals(json.parse(metric).extract[AggregateMetric].count, 7076)
  }

  test("SimpleInvoice serialises with the draft status") {
    val invoice = SimpleInvoice(1, "OBP-7", "2026-03-04", "2026-04-03", "note", "2026-03-04T02:00:00.000Z",
      List(InvoiceItem("API-CALLS-PLAN-A", "2026-03-01", 0.5, 5)))
    val rendered = json.compactRender(json.Extraction.decompose(invoice))
    assert(rendered.contains(""""invoice_status_id":1"""))
    assert(rendered.contains(""""qty":5"""))
  }
}
