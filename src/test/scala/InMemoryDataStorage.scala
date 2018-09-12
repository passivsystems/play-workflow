package workflow

import play.api.mvc.{RequestHeader, Result}

class InMemoryDataStorage extends DataStorage {
  import java.util.concurrent.ConcurrentHashMap
  import collection.JavaConverters._
  private val initParams = new ConcurrentHashMap[String, String]()
  private val data = new ConcurrentHashMap[String, String]()

  override def withNewSession(result: Result, initParams: Map[String, String])(implicit request: RequestHeader): Result = {
    this.data.clear()
    this.initParams.clear()
    this.initParams.putAll(initParams.asJava)
    result
  }

  override def withNewSession(result: Result)(implicit request: RequestHeader): Result = {
    this.data.clear()
    this.initParams.clear()
    result
  }

  override def withUpdatedSession(result: Result, key: String, s: String)(implicit request: RequestHeader): Result = {
    this.data.put(key, s)
    result
  }

  override def readData(key: String)(implicit request: RequestHeader): Option[String] =
    Option(this.data.get(key))

  override def readInitParams(implicit request: RequestHeader): Map[String, String] =
    this.initParams.asScala.toMap

  // To assist testing

  def getInitParams =
    this.initParams.asScala.toMap

  def getData =
    this.data.asScala.toMap
}
