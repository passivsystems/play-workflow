package workflow

import scala.concurrent.Future
import cats.Functor
import cats.free.FreeT
import cats.implicits._
import play.api.libs.concurrent.Execution.Implicits._

import Workflow._

object WorkflowOps {
  implicit class ListWithSequence[A](l: List[Workflow[A]]) {
    def sequence: Workflow[List[A]] = WorkflowOps.sequence(l)
  }

  // TODO make tail recursive
  def sequence[A](l: List[Workflow[A]]): Workflow[List[A]] =
    l match {
      case wfa :: rest => for {
                             a   <- wfa
                             acc <- sequence(rest)
                           } yield a :: acc
      case Nil         => Workflow.Workflow.pure(Nil)
    }
}
