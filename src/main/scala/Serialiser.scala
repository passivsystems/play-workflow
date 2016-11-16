package workflow

/** Defines how to serialise a single step object to/from String */
trait Serialiser[A] {
  def serialise(a: A): String
  def deserialise(s: String): Option[A]
}

/** serialiser which uses upickle deault Reader and Writer for storing step results in session */
trait UpickleSerialiser {
  implicit def serialiser[A](implicit reader: upickle.default.Reader[A], writer: upickle.default.Writer[A]): Serialiser[A] = new Serialiser[A] {
    override def serialise(a: A) = upickle.default.write(a)
    override def deserialise(s: String) = scala.util.Try {
        upickle.default.read[A](s)
      }.recoverWith {
        case ex: Throwable => play.api.Logger.warn(s"Error deserializing session cookie: ${ex.getMessage}", ex); scala.util.Failure(ex)
      }.toOption
  }
}

object UpickleSerialiser extends UpickleSerialiser
