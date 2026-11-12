package com.tesobe.obp.billing

import java.util.Date
import java.util.concurrent.atomic.AtomicLong

import com.tesobe.obp.billing.model._
import com.tesobe.obp.billing.utils.DateUtils._
import com.tesobe.obp.billing.utils.HttpUtils._
import com.tesobe.obp.billing.utils.StringUtils.{cleanText, isNotEmptyStr}
import net.liftweb.json
import net.liftweb.json.JsonDSL._
import net.liftweb.json.{JArray, JObject}

/**
 * One billing run:
 *  1. fetch all OBP consumers;
 *  2. make sure each has an Invoice Ninja client (the consumer id is kept in the client's custom_value1);
 *  3. for each consumer, fetch daily aggregate metrics since its last invoice and create a draft invoice
 *     with one line per day that had API calls.
 */
object Main {

  private val invoiceNumberPrefix = "OBP-"
  private val consumerPageSize = 100
  private val epoch = "1970-01-01T00:00:00.000Z"

  /** Prefers the invoice whose custom_text_value1 (end of billed period) is the latest. */
  def getNewerInvoice(v1: Invoice, v2: Invoice): Invoice = {
    val firstIsTime = isTimeStr(v1.custom_text_value1)
    val secondIsTime = isTimeStr(v2.custom_text_value1)
    if (firstIsTime && secondIsTime) List(v1, v2).maxBy(_.custom_text_value1)
    else if (firstIsTime) v1
    else v2
  }

  /** Every consumer known to OBP, walking the paginated listing. */
  def fetchAllConsumers(): List[Consumer] = {
    @scala.annotation.tailrec
    def loop(offset: Int, seen: Set[String], acc: List[Consumer]): List[Consumer] = {
      val page = getObpJson[List[Consumer]]("management/consumers", "consumers",
        List("limit" -> consumerPageSize.toString, "offset" -> offset.toString, "from_date" -> epoch))
        .filter(c => isNotEmptyStr(c.consumer_id) && !seen.contains(c.consumer_id))
      val next = acc ::: page
      // Stop on a short page, or when the server ignored the offset and returned nothing new.
      if (page.size < consumerPageSize || page.isEmpty) next
      else loop(offset + consumerPageSize, seen ++ page.map(_.consumer_id), next)
    }
    loop(0, Set.empty, Nil)
  }

  /** Aggregate metrics for one consumer in a time window. Handles both the list (<= v5.1.0) and object (v6.0.0+) response shapes. */
  def getMetric(consumerId: String, from: Date, to: Date): AggregateMetric = {
    val fromDate = toTimeStr(from)
    val toDate = toTimeStr(to)
    println(s"Fetching metrics: consumer_id=$consumerId&from_date=$fromDate&to_date=$toDate")
    val metric = getObpJValue("management/aggregate-metrics", "",
      List("consumer_id" -> consumerId, "from_date" -> fromDate, "to_date" -> toDate)) match {
      case JArray(head :: _) => head.extract[AggregateMetric]
      case JArray(Nil)       => AggregateMetric.empty
      case obj: JObject      => obj.extract[AggregateMetric]
      case other             => throw new IllegalStateException(s"Unexpected aggregate-metrics response: $other")
    }
    println(s"Fetched metrics: $metric")
    metric
  }

  def buildInvoiceItems(dateTimeRange: Seq[Date], consumerId: String, ninjaProduct: NinjaProduct): List[InvoiceItem] = {
    println("Fetching metrics for the whole period.")
    val totalMetric = getMetric(consumerId, dateTimeRange.head, dateTimeRange.last)
    if (totalMetric.count == 0) Nil
    else {
      val days: List[(Date, Date)] = dateTimeRange.zip(dateTimeRange.tail).toList
      for {
        (from, to) <- days
        metric = getMetric(consumerId, from, to)
        if metric.count > 0
        itemCost = BigDecimal(ninjaProduct.cost) * BigDecimal(metric.count)
      } yield {
        Thread.sleep(100) // be gentle with OBP-API; rapid-fire requests can trip rate limiting
        InvoiceItem(product_key, toDateStr(from), itemCost.toDouble, metric.count)
      }
    }
  }

  def createNinjaClient(consumer: Consumer): Int = {
    val creator = consumer.created_by_user
    val appName = Option(consumer.app_name).filter(isNotEmptyStr)
      .getOrElse(s"Empty name created by ${creator.map(_.username).getOrElse("unknown user")}")
    val body =
      ("name" -> cleanText(appName)) ~
        ("public_notes" -> cleanText(consumer.description)) ~
        ("custom_value1" -> consumer.consumer_id) ~
        ("custom_value2" -> cleanText(consumer.description)) ~
        ("website" -> cleanText(consumer.redirect_url)) ~
        ("contact" -> ("email" -> cleanText(creator.map(_.email).orNull)))

    println(s"Creating client for consumer ${consumer.consumer_id}")
    val response = buildNinjaRequest("clients").method("POST").postData(json.compactRender(body)).send()
    if (!response.isSuccess)
      throw new IllegalStateException(s"Creating client for consumer ${consumer.consumer_id} failed: HTTP ${response.code} ${response.body}")
    val clientId = (json.parse(response.body) \ "data" \ "id").extract[Int]
    println(s"Created client for consumer ${consumer.consumer_id}: client_id=$clientId")
    clientId
  }

  def main(args: Array[String]): Unit =
    try run()
    catch {
      case e: Throwable =>
        val cause = e match {
          case init: ExceptionInInitializerError if init.getCause != null => init.getCause
          case other => other
        }
        System.err.println(s"Billing run failed: $cause")
        cause.printStackTrace()
        sys.exit(1)
    }

  def run(): Unit = {
    println("Fetching all OBP consumers.")
    val consumers = fetchAllConsumers()
    println(s"Got OBP consumers: ${consumers.size}")

    println("Fetching existing Invoice Ninja clients.")
    val allConsumerIds = consumers.map(_.consumer_id).toSet
    // consumerId -> client_id for consumers that already have a (non-deleted) client
    val existingClients: Map[String, Int] = collectNinjaJson[Client, (String, Int), ClientsResponse]("clients") {
      case client if !client.is_deleted && allConsumerIds.contains(client.custom_value1) => client.custom_value1 -> client.id
    }.toMap
    println(s"Got existing Invoice Ninja clients matching OBP consumers: ${existingClients.size}")

    val consumersWithoutClient = consumers.filterNot(c => existingClients.contains(c.consumer_id))
    if (consumersWithoutClient.nonEmpty)
      println(s"Consumers without an Invoice Ninja client: ${consumersWithoutClient.map(_.consumer_id).mkString(", ")}")

    val newClients: Map[String, Int] = consumersWithoutClient.map(c => c.consumer_id -> createNinjaClient(c)).toMap
    if (newClients.nonEmpty) println(s"Created new clients: ${newClients.size}")

    val consumerIdToClientId: Map[String, Int] = existingClients ++ newClients
    // The consumer's creation time is the start of its first billing period.
    val consumerIdToCreated: Map[String, Date] = consumers.map(c => c.consumer_id -> c.created).toMap

    val ninjaProduct: NinjaProduct = findOneNinjaJson[NinjaProduct, NinjaProductsResponse]("products")(_.product_key == product_key)
      .getOrElse(throw new IllegalStateException(s"Invoice Ninja has no product with product_key '$product_key'."))
    println(s"Using product '$product_key': $ninjaProduct")

    println("Creating invoices.")
    val newestInvoiceNumber: Long = foldLeftNinjaElements[Invoice, InvoicesResponse]("invoices", _.invoice_number.startsWith(invoiceNumberPrefix)) { (pre, cur) =>
      if (invoiceSequence(pre) > invoiceSequence(cur)) pre else cur
    }.map(invoiceSequence).getOrElse(0L)
    println(s"Current newest invoice number: $invoiceNumberPrefix$newestInvoiceNumber")

    val invoiceNumberBuilder = new AtomicLong(newestInvoiceNumber)
    var created = 0
    var failed = 0
    for ((consumerId, clientId) <- consumerIdToClientId) {
      val newestInvoice = reduceLeftNinjaJson[Invoice, InvoicesResponse]("invoices", List("client_id" -> clientId))(getNewerInvoice)
      val fromDate: Date = newestInvoice match {
        case Some(invoice) if isTimeStr(invoice.custom_text_value1) => parseTime(invoice.custom_text_value1)
        case _ => consumerIdToCreated(consumerId)
      }

      val dateTimeRange: Seq[Date] = buildRangeTime(fromDate)
      val invoiceItems: List[InvoiceItem] = buildInvoiceItems(dateTimeRange, consumerId, ninjaProduct)
      if (invoiceItems.nonEmpty) {
        val invoiceNumber = s"$invoiceNumberPrefix${invoiceNumberBuilder.incrementAndGet()}"
        val periodEnd = dateTimeRange.last
        val invoice = SimpleInvoice(
          client_id = clientId,
          invoice_number = invoiceNumber,
          invoice_date = toDateStr(periodEnd),
          due_date = toDateStr(addDate(periodEnd, 30)),
          public_notes = s"${toDateTime(dateTimeRange.head)} TO ${toDateTime(periodEnd)}",
          custom_text_value1 = toTimeStr(periodEnd), // where the next run starts from
          invoice_items = invoiceItems)

        val payload = json.compactRender(json.Extraction.decompose(invoice))
        println(s"Creating invoice: $payload")
        val response = buildNinjaRequest("invoices").method("POST").postData(payload).send()
        if (response.isSuccess) {
          created += 1
          println(s"Created invoice $invoiceNumber for consumer $consumerId")
        } else {
          failed += 1
          println(s"Creating invoice $invoiceNumber for consumer $consumerId failed: HTTP ${response.code} ${response.body}")
        }
      }
    }

    println(s"Done. Invoices created: $created, failed: $failed.")
    if (failed > 0) sys.exit(1)
  }

  private def invoiceSequence(invoice: Invoice): Long =
    invoice.invoice_number.stripPrefix(invoiceNumberPrefix).toLong
}
