package workflow

import play.api.Logger
import play.api.mvc.{RequestHeader, Result}

/** Defines how to store/restore serialised step objects */
trait DataStorage {
  /** start a new flow session, populated with the provided initial flow params */
  def withNewSession(result: Result, initParams: Map[String, String])(implicit request: RequestHeader): Result

  /** start a new flow session, preserving any initial flow params */
  def withNewSession(result: Result)(implicit request: RequestHeader): Result

  /** update the session with provided key,value */
  def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result

  /** extract the value for a key */
  def readData(key: String)(implicit request: RequestHeader): Option[String]

  /** extract the initial flow params */
  def readInitParams(implicit request: RequestHeader): Map[String, String]
}

trait StorageImpl extends DataStorage {
  val flowkey: String
  val initParamKey = flowkey + "_init"
  def serialiser: Serialiser[Map[String, String]]

  def writeToSession(result: Result, value: Map[String, String]): Result

  def readFromSession(request: RequestHeader): Map[String, String]

  val logger = Logger("application.workflow.Storage")

  override def withNewSession(result: Result, initParams: Map[String, String])(implicit request: RequestHeader): Result =
    writeToSession(result, Map(initParamKey -> serialiser.serialise(initParams)))

  override def withNewSession(result: Result)(implicit request: RequestHeader): Result =
    withNewSession(result, readInitParams)

  override def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result =
    writeToSession(result, readFromSession(request) + (key -> s))

  override def readData(key: String)(implicit request: RequestHeader): Option[String] =
    readFromSession(request).get(key)

  override def readInitParams(implicit request: RequestHeader): Map[String, String] =
    (for {
       s <- readData(initParamKey)
       m <- serialiser.deserialise(s)
              .orElse { logger.info("failed to deserialise init params - clearing"); None }
     } yield m
    ).getOrElse(Map[String, String]())
}

/** Uses Play's default session which stores the data in a cookie.
 *  The data is stored under a key, so can participate with existing session data.
 */
case class SessionStorage(flowkey: String)(implicit val serialiser: Serialiser[Map[String, String]]) extends StorageImpl {
  override def writeToSession(result: Result, value: Map[String, String]): Result =
    result.withSession((flowkey -> serialiser.serialise(value)))

  override def readFromSession(request: RequestHeader): Map[String, String] =
    (for {
       s <- request.session.get(flowkey)
       m <- serialiser.deserialise(s)
              .orElse { logger.info("failed to deserialise flow data - clearing"); None }
     } yield m
    ).getOrElse(Map[String, String]())
}

/** Uses Play's default session which stores the data in a cookie.
 *  The data is stored under a key, so can participate with existing session data.
 *  the data is also compressed with gzip
 */
case class GzippedSessionStorage(flowkey: String)(implicit val serialiser: Serialiser[Map[String, String]]) extends StorageImpl {
  override def writeToSession(result: Result, value: Map[String, String]): Result = {
    val flowValue = new sun.misc.BASE64Encoder().encode(compress(serialiser.serialise(value)))
    result.withSession((flowkey -> flowValue))
  }

  override def readFromSession(request: RequestHeader): Map[String, String] =
    (for {
       s <- request.session.get(flowkey)
       m <- serialiser.deserialise(decompress(new sun.misc.BASE64Decoder().decodeBuffer(s)))
              .orElse { logger.info("failed to deserialise flow data - clearing"); None }
     } yield m
    ).getOrElse(Map[String, String]())

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
