package com.tesobe.obp.billing.utils

import java.io.{FileInputStream, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util.Properties

import scala.jdk.CollectionConverters._

/**
 * Configuration lookup.
 *
 * Sources, in order of precedence:
 *  1. an environment variable named after the key in upper case with dots replaced by
 *     underscores (obp.username -> OBP_USERNAME);
 *  2. the properties file named by the `obp.billing.config` system property or the
 *     `OBP_BILLING_CONFIG` environment variable;
 *  3. `application.properties` on the classpath.
 *
 * Values may reference other keys with `${key}`.
 */
object Props {
  val ConfigPathProperty = "obp.billing.config"
  val ConfigPathEnv = "OBP_BILLING_CONFIG"

  private val Placeholder = """\$\{([^}]+)\}""".r

  private lazy val props: Map[String, String] = {
    val (source, stream) = openConfig()
    val raw = try {
      val p = new Properties()
      p.load(new InputStreamReader(stream, StandardCharsets.UTF_8))
      p.asScala.toMap
    } finally stream.close()
    println(s"Loaded configuration from $source")
    resolvePlaceholders(raw)
  }

  private def openConfig(): (String, InputStream) = {
    val explicitPath = sys.props.get(ConfigPathProperty).orElse(sys.env.get(ConfigPathEnv)).map(_.trim).filter(_.nonEmpty)
    explicitPath match {
      case Some(path) =>
        path -> new FileInputStream(path)
      case None =>
        Option(getClass.getResourceAsStream("/application.properties")) match {
          case Some(in) => "classpath:application.properties" -> in
          case None =>
            throw new IllegalStateException(
              "No configuration found. Copy src/main/resources/application.properties.example to " +
                s"application.properties, or set $ConfigPathEnv / -D$ConfigPathProperty to a properties file.")
        }
    }
  }

  private def resolvePlaceholders(raw: Map[String, String]): Map[String, String] = {
    def lookup(key: String): String =
      sys.env.getOrElse(envName(key), raw.getOrElse(key,
        throw new IllegalArgumentException(s"Configuration refers to unknown key '$key'")))

    def expand(value: String, depth: Int): String =
      if (depth > 20) throw new IllegalArgumentException(s"Circular placeholder reference in '$value'")
      else if (!Placeholder.findFirstIn(value).isDefined) value
      else expand(Placeholder.replaceAllIn(value, m => java.util.regex.Matcher.quoteReplacement(lookup(m.group(1).trim))), depth + 1)

    raw.map { case (k, v) => k -> expand(v, 0) }
  }

  private def envName(key: String): String = key.toUpperCase.replace('.', '_')

  def getPropertyOpt(key: String): Option[String] =
    sys.env.get(envName(key)).orElse(props.get(key)).map(_.trim).filter(_.nonEmpty)

  def getProperty(key: String): String =
    getPropertyOpt(key).getOrElse(
      throw new IllegalArgumentException(s"Missing configuration key '$key' (or environment variable ${envName(key)})"))
}
