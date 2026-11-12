package com.tesobe.obp.billing.model

import java.util.Date

/** Invoice Ninja records carry a soft-delete flag. */
trait SoftDeletable {
  def is_deleted: Boolean
}

// ---------------------------------------------------------------------------
// OBP-API
// ---------------------------------------------------------------------------

/** Subset of the consumer JSON returned by GET /management/consumers. Extra fields are ignored. */
case class Consumer(consumer_id: String,
                    app_name: String,
                    app_type: String,
                    description: String,
                    developer_email: String,
                    redirect_url: String,
                    created_by_user: Option[ResourceUserJSON],
                    enabled: Boolean,
                    created: Date)

case class ResourceUserJSON(user_id: String,
                            email: String,
                            provider_id: String,
                            provider: String,
                            username: String)

/** Subset of GET /management/aggregate-metrics. Newer OBP versions add more fields, which are ignored. */
case class AggregateMetric(count: Int,
                           average_response_time: Double,
                           minimum_response_time: Double,
                           maximum_response_time: Double)

object AggregateMetric {
  val empty: AggregateMetric = AggregateMetric(0, 0, 0, 0)
}

// ---------------------------------------------------------------------------
// Invoice Ninja v4
// ---------------------------------------------------------------------------

case class Client(id: Int, name: String, display_name: String, is_deleted: Boolean, custom_value1: String)
  extends SoftDeletable

case class NinjaProduct(product_key: String,
                        notes: String,
                        cost: Double,
                        qty: Int,
                        tax_name1: String,
                        tax_name2: String,
                        tax_rate1: Float,
                        tax_rate2: Float,
                        is_owner: Boolean,
                        is_deleted: Boolean)
  extends SoftDeletable

case class InvoiceItem(product_key: String,
                       notes: String,
                       cost: Double,
                       qty: Int)

case class Invoice(amount: Float,
                   balance: Float,
                   client_id: Int,
                   invoice_status_id: Int,
                   invoice_number: String,
                   discount: Float,
                   po_number: String,
                   invoice_date: String,
                   due_date: String,
                   terms: String,
                   public_notes: String,
                   invoice_type_id: Int,
                   is_recurring: Boolean,
                   frequency_id: Int,
                   start_date: String,
                   end_date: String,
                   last_sent_date: String,
                   recurring_invoice_id: Int,
                   tax_name1: String,
                   tax_rate1: Float,
                   tax_name2: String,
                   tax_rate2: Float,
                   is_amount_discount: Boolean,
                   invoice_footer: String,
                   partial: Float,
                   has_tasks: Boolean,
                   auto_bill: Boolean,
                   custom_value1: Int,
                   custom_value2: Int,
                   custom_taxes1: Boolean,
                   custom_taxes2: Boolean,
                   has_expenses: Boolean,
                   quote_invoice_id: Int,
                   /** Holds the end time of the billed period, so the next run knows where to start. */
                   custom_text_value1: String,
                   custom_text_value2: String,
                   is_quote: Boolean,
                   is_public: Boolean,
                   invoice_items: List[InvoiceItem],
                   is_owner: Boolean,
                   is_deleted: Boolean)
  extends SoftDeletable

object Invoice {
  val STATUS_DRAFT = 1
  val STATUS_SENT = 2
  val STATUS_VIEWED = 3
  val STATUS_APPROVED = 4
  val STATUS_PARTIAL = 5
  val STATUS_PAID = 6
  val STATUS_OVERDUE = -1
  val STATUS_UNPAID = -2

  val TYPE_STANDARD = 1
  val TYPE_QUOTE = 2

  val FREQUENCY_WEEKLY = 1
  val FREQUENCY_TWO_WEEKS = 2
  val FREQUENCY_FOUR_WEEKS = 3
  val FREQUENCY_MONTHLY = 4
  val FREQUENCY_TWO_MONTHS = 5
  val FREQUENCY_THREE_MONTHS = 6
  val FREQUENCY_SIX_MONTHS = 7
  val FREQUENCY_ANNUALLY = 8

  val INVOICE_NR = "invoice_number"
}

/** The fields sent when creating an invoice. */
case class SimpleInvoice(client_id: Int,
                         invoice_number: String,
                         invoice_date: String,
                         due_date: String,
                         public_notes: String,
                         /** End time of the billed period, see [[Invoice.custom_text_value1]]. */
                         custom_text_value1: String,
                         invoice_items: List[InvoiceItem],
                         invoice_status_id: Int = Invoice.STATUS_DRAFT)

case class Pagination(total: Int,
                      count: Int,
                      per_page: Int,
                      current_page: Int,
                      total_pages: Int) {
  val next_page: Option[Int] = if (current_page < total_pages) Option(current_page + 1) else None
}

case class PaginationMeta(pagination: Pagination)

sealed trait PagingResponse[T] {
  val data: List[T]
  val meta: PaginationMeta
  val total: Int = meta.pagination.total

  def totalPage(pageSize: Int): Int = (total + pageSize - 1) / pageSize

  def pageRange(pageSize: Int): Seq[Int] = 1 to totalPage(pageSize)

  def hasNextPage: Boolean = meta.pagination.current_page < meta.pagination.total_pages
}

case class ClientsResponse(data: List[Client], meta: PaginationMeta) extends PagingResponse[Client]
case class NinjaProductsResponse(data: List[NinjaProduct], meta: PaginationMeta) extends PagingResponse[NinjaProduct]
case class InvoicesResponse(data: List[Invoice], meta: PaginationMeta) extends PagingResponse[Invoice]
