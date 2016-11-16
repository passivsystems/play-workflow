package workflow

import play.api.mvc.{RequestHeader, Result}

/** Defines how to store/restore serialised step objects */
trait DataStorage {
  def withNewSession(result: Result)(implicit request: RequestHeader): Result
  def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result
  def readData(key: String)(implicit request: RequestHeader): Option[String]
}

/** Uses Play's default session which stores the data in a cookie.
 *  Each step has it's data stored as a new key.
 */
object SessionStorage extends DataStorage {
  override def withNewSession(result: Result)(implicit request: RequestHeader): Result =
    result.withNewSession

  override def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result =
    result.withSession(request.session + (key -> s))

  override def readData(key: String)(implicit request: RequestHeader): Option[String] =
     request.session.get(key)
}

/** Uses Play's default session which stores the data in a cookie.
 *  The data is stored under a key, so can participate with existing session data.
 */
case class SubSessionStorage(flowkey: String) extends DataStorage {

  private def writeToSession(result: Result, value: Map[String, String]): Result = {
    val flowValue = upickle.default.write(value)
    result.withSession((flowkey -> flowValue))
  }

  private def readFromSession(request: RequestHeader): Map[String, String] =
    request.session.get(flowkey).map { s => upickle.default.read[Map[String,String]](s) }.getOrElse(Map[String, String]())

  override def withNewSession(result: Result)(implicit request: RequestHeader): Result =
    writeToSession(result, Map[String, String]())

  override def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result =
    writeToSession(result, readFromSession(request) + (key -> s))

  override def readData(key: String)(implicit request: RequestHeader): Option[String] =
    readFromSession(request).get(key)
}

/** Uses Play's default session which stores the data in a cookie.
 *  The data is stored under a key, so can participate with existing session data.
 *  the data is also compressed with gzip
 */
case class GzippedSessionStorage(flowkey: String) extends DataStorage {

  private def writeToSession(result: Result, value: Map[String, String]): Result = {
    val flowValue = new sun.misc.BASE64Encoder().encode(compress(upickle.default.write(value)))
    result.withSession((flowkey -> flowValue))
  }

  private def readFromSession(request: RequestHeader): Map[String, String] =
    request.session.get(flowkey).flatMap { s =>
      scala.util.Try {
        upickle.default.read[Map[String, String]](decompress(new sun.misc.BASE64Decoder().decodeBuffer(s)))
      }.recoverWith {
        case ex: Throwable => play.api.Logger.warn(s"Error deserializing session cookie: ${ex.getMessage}", ex); scala.util.Failure(ex)
      }.toOption
    }.getOrElse(Map[String, String]())

  override def withNewSession(result: Result)(implicit request: RequestHeader): Result =
    writeToSession(result, Map[String, String]())

  override def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result =
    writeToSession(result, readFromSession(request) + (key -> s))

  override def readData(key: String)(implicit request: RequestHeader): Option[String] =
    readFromSession(request).get(key)

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
