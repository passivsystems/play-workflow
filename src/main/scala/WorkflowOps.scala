package workflow

import cats.free.Free

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
      case Nil         => Free.pure(Nil)
    }
}
