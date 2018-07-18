package workflow

import play.api.Logger
import play.api.mvc.{RequestHeader, Result}

/** Defines how to store/restore serialised step objects */
trait DataStorage {
  def withNewSession(result: Result, initParams: Map[String, String])(implicit request: RequestHeader): Result
  def withNewSession(result: Result)(implicit request: RequestHeader): Result
  def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result
  def readData(key: String)(implicit request: RequestHeader): Option[String]
  def readInitParams(implicit request: RequestHeader): Map[String, String]
}

/** Uses Play's default session which stores the data in a cookie.
 *  The data is stored under a key, so can participate with existing session data.
 */
case class SessionStorage(flowkey: String)(implicit serialiser: Serialiser[Map[String, String]]) extends DataStorage {
  private val logger = Logger("application.workflow.SessionStorage")

  val initParamKey = flowkey + "_init"

  private def writeToSession(result: Result, value: Map[String, String]): Result =
    result.withSession((flowkey -> serialiser.serialise(value)))

  private def readFromSession(request: RequestHeader): Map[String, String] =
    request.session.get(flowkey).flatMap { s =>
      serialiser.deserialise(s)
    }.getOrElse(Map[String, String]())

  override def withNewSession(result: Result, initParams: Map[String, String])(implicit request: RequestHeader): Result =
    writeToSession(result, Map(initParamKey -> serialiser.serialise(initParams)))

  override def withNewSession(result: Result)(implicit request: RequestHeader): Result =
    readData(initParamKey) match {
      case Some(initParams) => serialiser.deserialise(initParams) match {
                                 case Some(initParams) => withNewSession(result, initParams)
                                 case None             => logger.warn("failed to deserialise init params")
                                                          writeToSession(result, Map[String, String]())
                               }
      case None             => writeToSession(result, Map[String, String]())
    }

  override def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result =
    writeToSession(result, readFromSession(request) + (key -> s))

  override def readData(key: String)(implicit request: RequestHeader): Option[String] =
    readFromSession(request).get(key)

  override def readInitParams(implicit request: RequestHeader): Map[String, String] =
    readData(initParamKey).flatMap { s =>
      serialiser.deserialise(s)
    }.getOrElse(Map[String, String]())
}

/** Uses Play's default session which stores the data in a cookie.
 *  The data is stored under a key, so can participate with existing session data.
 *  the data is also compressed with gzip
 */
case class GzippedSessionStorage(flowkey: String)(implicit serialiser: Serialiser[Map[String, String]]) extends DataStorage {
  private val logger = Logger("application.workflow.SessionStorage")

  val initParamKey = flowkey + "_init"

  private def writeToSession(result: Result, value: Map[String, String]): Result = {
    val flowValue = new sun.misc.BASE64Encoder().encode(compress(serialiser.serialise(value)))
    result.withSession((flowkey -> flowValue))
  }

  private def readFromSession(request: RequestHeader): Map[String, String] =
    request.session.get(flowkey).flatMap { s =>
      serialiser.deserialise(decompress(new sun.misc.BASE64Decoder().decodeBuffer(s)))
    }.getOrElse(Map[String, String]())

  override def withNewSession(result: Result, initParams: Map[String, String])(implicit request: RequestHeader): Result =
    writeToSession(result, Map(initParamKey -> serialiser.serialise(initParams)))

  override def withNewSession(result: Result)(implicit request: RequestHeader): Result =
    readData(initParamKey) match {
      case Some(initParams) => serialiser.deserialise(initParams) match {
                                 case Some(initParams) => withNewSession(result, initParams)
                                 case None             => logger.warn("failed to deserialise init params")
                                                          writeToSession(result, Map[String, String]())
                               }
      case None             => writeToSession(result, Map[String, String]())
    }

  override def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result =
    writeToSession(result, readFromSession(request) + (key -> s))

  override def readData(key: String)(implicit request: RequestHeader): Option[String] =
    readFromSession(request).get(key)

  override def readInitParams(implicit request: RequestHeader): Map[String, String] =
    readData(initParamKey).flatMap { s =>
      serialiser.deserialise(s)
    }.getOrElse(Map[String, String]())

  import java.io.{ByteArrayOutputStream, ByteArrayInputStream, BufferedReader, InputStreamReader}
  import java.util.zip.{GZIPOutputStream, GZIPInputStream}

  private def compress(str: String): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val gzip = new GZIPOutputStream(out)
    gzip.write(str.getBytes("UTF-8"))
    gzip.close()
    out.toByteArray
  }

  private def decompress(bytes: Array[Byte]): String = {
    val br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(bytes)), "UTF-8"))
    val res = Stream.continually(br.readLine()).takeWhile(_ != null).mkString("\n")
    br.close()
    res
  }
}
