package com.tesobe.obp.billing.utils

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.Duration

import com.tesobe.obp.billing.model.{PagingResponse, SoftDeletable}
import com.tesobe.obp.billing.utils.Props.getProperty
import net.liftweb.json
import net.liftweb.json.{Formats, JValue}

/** Thin HTTP layer over the JDK client plus lift-json helpers for OBP-API and Invoice Ninja. */
object HttpUtils {
  implicit val formats: Formats = JsonFormats.formats

  final class HttpError(val code: Int, val body: String, url: String)
    extends RuntimeException(s"HTTP $code from $url: $body")

  final case class Response(code: Int, body: String, url: String) {
    def isSuccess: Boolean = code >= 200 && code < 300
    def throwError: Response = if (isSuccess) this else throw new HttpError(code, body, url)
  }

  /** Immutable request builder. */
  final case class Request(url: String,
                           verb: String = "GET",
                           headers: Seq[(String, String)] = Nil,
                           queryParams: Seq[(String, String)] = Nil,
                           body: Option[String] = None,
                           connectTimeout: Duration = Duration.ofSeconds(30),
                           readTimeout: Duration = Duration.ofSeconds(60)) {

    def method(m: String): Request = copy(verb = m.toUpperCase)
    def header(key: String, value: String): Request = copy(headers = headers :+ (key -> value))
    def params(ps: Seq[(String, String)]): Request = copy(queryParams = queryParams ++ ps)
    def param(key: String, value: String): Request = params(Seq(key -> value))
    def postData(data: String): Request = copy(body = Some(data), verb = if (verb == "GET") "POST" else verb)
    def timeout(connect: Duration, read: Duration): Request = copy(connectTimeout = connect, readTimeout = read)

    def fullUrl: String =
      if (queryParams.isEmpty) url
      else {
        val sep = if (url.contains("?")) "&" else "?"
        url + sep + queryParams.map { case (k, v) => encode(k) + "=" + encode(v) }.mkString("&")
      }

    def send(): Response = {
      val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
      val builder = HttpRequest.newBuilder(URI.create(fullUrl)).timeout(readTimeout)
      headers.foreach { case (k, v) => builder.header(k, v) }
      val publisher = body.map(HttpRequest.BodyPublishers.ofString(_, StandardCharsets.UTF_8))
        .getOrElse(HttpRequest.BodyPublishers.noBody())
      val response = client.send(builder.method(verb, publisher).build(), HttpResponse.BodyHandlers.ofString())
      Response(response.statusCode(), response.body(), fullUrl)
    }
  }

  private def encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  private lazy val token: String = {
    println("Fetching OBP Direct Login token.")

    val username = getProperty("obp.username")
    val password = getProperty("obp.password")
    val consumerKey = getProperty("obp.consumerKey")
    val directloginUrl = getProperty("obp.api.directloginUrl")
    val authorizationHeader = s"""DirectLogin username="$username",password="$password",consumer_key="$consumerKey""""
    val response = Request(directloginUrl)
      .header("Content-Type", "application/json")
      .header("Authorization", authorizationHeader)
      .postData("")
      .timeout(Duration.ofSeconds(10), Duration.ofSeconds(10))
      .send()
    val obpToken = (json.parse(response.body) \ "token").extractOpt[String].getOrElse(
      throw new IllegalStateException(
        s"OBP Direct Login failed for user '$username' at $directloginUrl (HTTP ${response.code}): ${response.body}"))

    println("Got OBP token.")
    obpToken
  }

  lazy val product_key: String = getProperty("ninja.api.invoice.item.product_key")

  def buildObpRequest(relativeUrl: String): Request =
    Request(getProperty("obp.api.versionedUrl") + "/" + relativeUrl)
      .header("Content-Type", "application/json")
      .header("Authorization", s"""DirectLogin token="$token"""")

  def buildNinjaRequest(relativeUrl: String): Request =
    Request(getProperty("ninja.api.rootUrl") + "/" + relativeUrl)
      .header("Content-Type", "application/json")
      .header("X-Ninja-Token", getProperty("ninja.api.token"))

  /** Sends the request and returns the JSON at `dataPath` (dot separated, empty for the root). */
  private def requestJValue(urlBuilder: String => Request)(relativeUrl: String, dataPath: String)(fun: Request => Request): JValue = {
    val response = fun(urlBuilder(relativeUrl)).send().throwError
    val jValue = json.parse(response.body)
    dataPath.split('.').filter(_.nonEmpty).foldLeft(jValue)(_ \ _)
  }

  private def requestJson[T: Manifest](urlBuilder: String => Request)(relativeUrl: String, dataPath: String)(fun: Request => Request): T =
    requestJValue(urlBuilder)(relativeUrl, dataPath)(fun).extract[T]

  // --- OBP ---

  def getObpJValue(relativeUrl: String, dataPath: String = "", params: Seq[(String, String)] = Seq.empty): JValue =
    requestJValue(buildObpRequest)(relativeUrl, dataPath)(_.method("GET").params(params))

  def getObpJson[T: Manifest](relativeUrl: String, dataPath: String = "", params: Seq[(String, String)] = Seq.empty): T =
    requestJson[T](buildObpRequest)(relativeUrl, dataPath)(_.method("GET").params(params))

  // --- Invoice Ninja ---

  def requestNinjaJson[T: Manifest](relativeUrl: String, dataPath: String = "")(fun: Request => Request): T =
    requestJson[T](buildNinjaRequest)(relativeUrl, dataPath)(fun)

  def getNinjaJson[T <: PagingResponse[_] : Manifest](relativeUrl: String, params: Seq[(String, Any)] = Seq.empty): T =
    requestNinjaJson[T](relativeUrl)(_.method("GET").params(params.map { case (k, v) => k -> v.toString }))

  /** Iterates over every page of a paginated Invoice Ninja listing, lazily. */
  private def ninjaPages[D, T <: PagingResponse[D] : Manifest](relativeUrl: String,
                                                               params: Seq[(String, Any)],
                                                               pageSize: Int): Iterator[List[D]] = {
    val pagedParam = params :+ ("per_page" -> pageSize)
    val firstPage: T = getNinjaJson[T](relativeUrl, pagedParam)
    val rest = firstPage.pageRange(pageSize).iterator.drop(1)
      .map(page => getNinjaJson[T](relativeUrl, pagedParam :+ ("page" -> page)).data)
    Iterator(firstPage.data) ++ rest
  }

  def getAllNinjaJson[D, T <: PagingResponse[D] : Manifest](relativeUrl: String,
                                                            params: Seq[(String, Any)] = Seq.empty,
                                                            pageSize: Int = 20): List[D] =
    ninjaPages[D, T](relativeUrl, params, pageSize).flatten.toList

  def collectNinjaJson[D, E, T <: PagingResponse[D] : Manifest](relativeUrl: String,
                                                                params: Seq[(String, Any)] = Seq.empty,
                                                                pageSize: Int = 20)(pf: PartialFunction[D, E]): List[E] =
    ninjaPages[D, T](relativeUrl, params, pageSize).flatMap(_.collect(pf)).toList

  /** First non-deleted element matching `predicate`; stops requesting pages once found. */
  def findOneNinjaJson[D <: SoftDeletable, T <: PagingResponse[D] : Manifest](relativeUrl: String,
                                                                              params: Seq[(String, Any)] = Seq.empty,
                                                                              pageSize: Int = 20)
                                                                             (predicate: D => Boolean): Option[D] =
    ninjaPages[D, T](relativeUrl, params, pageSize).flatten.find(it => !it.is_deleted && predicate(it))

  /** Folds all non-deleted elements with `op`, or None when there are none. */
  def reduceLeftNinjaJson[D <: SoftDeletable, T <: PagingResponse[D] : Manifest](relativeUrl: String,
                                                                                 params: Seq[(String, Any)] = Seq.empty,
                                                                                 pageSize: Int = 20)
                                                                                (op: (D, D) => D): Option[D] =
    ninjaPages[D, T](relativeUrl, params, pageSize).flatten.filterNot(_.is_deleted).reduceLeftOption(op)

  /** Folds all elements matching `predicate` with `f`, or None when there are none. */
  def foldLeftNinjaElements[D, T <: PagingResponse[D] : Manifest](relativeUrl: String,
                                                                  predicate: D => Boolean = (_: D) => true,
                                                                  params: Seq[(String, Any)] = Seq.empty,
                                                                  pageSize: Int = 20)(f: (D, D) => D): Option[D] =
    ninjaPages[D, T](relativeUrl, params, pageSize).flatten.filter(predicate).reduceLeftOption(f)
}
